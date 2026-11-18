/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.tsunami.callbackserver.server.recording;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.net.HttpHeaders.HOST;
import static com.google.tsunami.callbackserver.common.CbidProcessor.extractCbidFromDomainInHttpProtocol;
import static com.google.tsunami.callbackserver.common.CbidProcessor.extractCbidFromUrl;
import static com.google.tsunami.callbackserver.common.UrlParser.getQueryParameter;
import static com.google.tsunami.callbackserver.server.common.RequestLogger.maybeGetClientAddrAsString;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.net.InetAddresses;
import com.google.protobuf.Message;
import com.google.tsunami.callbackserver.proto.HttpInteractionResponse;
import com.google.tsunami.callbackserver.server.common.HttpHandler;
import com.google.tsunami.callbackserver.server.common.monitoring.TcsEventsObserver;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore.InteractionType;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.net.InetAddress;
import java.net.URI;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import javax.inject.Inject;

/** HTTP handler for recording interactions via HTTP requests. */
final class HttpRecordingHandler extends HttpHandler {
  private static final String ENDPOINT_NAME = "RECORDING";
  private static final String RESPONSE_QUERY_PARAM = "response";
  private static final ImmutableMap<String, RecordingResponsePreset> RESPONSE_PRESETS =
      EnumSet.allOf(BrotliResponsePresets.class).stream()
          .collect(toImmutableMap(RecordingResponsePreset::paramValue, preset -> preset));
  private static final ImmutableList<String> REDIRECT_PARAMS =
      ImmutableList.of("to", "redirect", "url");
  private static final ImmutableSet<String> APPROVED_REDIRECT_HOSTS =
      ImmutableSet.of("localhost", "127.0.0.1", "::1", "[::1]");
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private final InteractionStore interactionStore;

  @Inject
  HttpRecordingHandler(InteractionStore interactionStore, TcsEventsObserver tcsEventsObserver) {
    super(ENDPOINT_NAME, tcsEventsObserver);
    this.interactionStore = interactionStore;
  }

  private static boolean isApprovedRedirectUrl(String urlString) {
    try {
      URI uri = URI.create(urlString);
      String host = uri.getHost();
      if (isNullOrEmpty(host)) {
        return false;
      }
      String normalizedHost = host.toLowerCase(Locale.ROOT);
      if (APPROVED_REDIRECT_HOSTS.contains(normalizedHost)) {
        return true;
      }
      if (InetAddresses.isInetAddress(normalizedHost)) {
        return InetAddresses.forString(normalizedHost).isLoopbackAddress();
      }
      return false;
    } catch (IllegalArgumentException e) {
      logger.atInfo().withCause(e).log("Failed to parse redirect URL: %s", urlString);
      return false;
    }
  }

  private static Optional<String> getRedirectUrl(FullHttpRequest request) {
    try {
      var decoder = new QueryStringDecoder(request.uri());
      var params = decoder.parameters();
      for (String paramName : REDIRECT_PARAMS) {
        var values = params.get(paramName);
        if (values != null && !values.isEmpty() && !isNullOrEmpty(values.get(0))) {
          String candidate = values.get(0);
          if (isApprovedRedirectUrl(candidate)) {
            return Optional.of(candidate);
          } else {
            logger.atInfo().log("Rejecting redirect to unapproved destination: %s", candidate);
          }
        }
      }
    } catch (IllegalArgumentException e) {
      logger.atInfo().withCause(e).log("Failed to parse URI for redirect: %s", request.uri());
    }
    return Optional.empty();
  }

  private void replyRedirect(ChannelHandlerContext ctx, String location, Stopwatch stopwatch) {
    FullHttpResponse response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND, Unpooled.EMPTY_BUFFER);
    response.headers().set(HttpHeaderNames.LOCATION, location);
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
    ctx.writeAndFlush(response);
    recordSuccessfulHttpRpc(stopwatch, HttpResponseStatus.FOUND);
  }

  @Override
  protected boolean maybeHandleCustomResponse(
      ChannelHandlerContext ctx,
      FullHttpRequest request,
      Optional<InetAddress> clientAddr,
      Stopwatch stopwatch) {
    Optional<String> redirectUrl = getRedirectUrl(request);
    if (redirectUrl.isPresent()) {
      var url = getUrl(request);
      extractCbidFromUrl(url)
          .or(() -> extractCbidFromDomainInHttpProtocol(url))
          .ifPresent(cbid -> recordHttpInteraction(cbid, clientAddr));
      replyRedirect(ctx, redirectUrl.get(), stopwatch);
      return true;
    }

    if (!request.method().equals(HttpMethod.GET)) {
      return false;
    }
    Optional<RecordingResponsePreset> preset =
        getQueryParameter(request.uri(), RESPONSE_QUERY_PARAM).map(RESPONSE_PRESETS::get);
    if (preset.isEmpty()) {
      return false;
    }
    String url = getUrl(request);
    preset
        .get()
        .handle(
            ctx,
            request,
            url,
            cbid -> recordHttpInteraction(cbid, clientAddr),
            status -> recordSuccessfulHttpRpc(stopwatch, status));
    return true;
  }

  @Override
  protected Message handleRequest(FullHttpRequest request, Optional<InetAddress> clientAddr) {
    var url = getUrl(request);
    extractCbidFromUrl(url)
        .or(() -> extractCbidFromDomainInHttpProtocol(url))
        .ifPresent(cbid -> recordHttpInteraction(cbid, clientAddr));

    // We always reply OK, no matter if we found a callback id or not. This is done on purpose as we
    // don't want to trigger any error handling code in the client making the call.
    return HttpInteractionResponse.newBuilder().setStatus("OK").build();
  }

  private void recordHttpInteraction(String cbid, Optional<InetAddress> clientAddr) {
    logger.atInfo().log(
        "Recording HTTP interaction with CBID '%s' from IP %s",
        cbid, maybeGetClientAddrAsString(clientAddr));
    interactionStore.add(cbid, InteractionType.HTTP_INTERACTION);
    tcsEventsObserver.onHttpInteractionRecorded();
  }

  private static String getUrl(FullHttpRequest request) {
    String host = request.headers().get(HOST);
    if (isNullOrEmpty(host)) {
      throw new IllegalArgumentException(
          String.format("Received HTTP request without '%s' header: %s", HOST, request));
    }
    return "http://" + host + request.uri();
  }
}
