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
import static com.google.common.net.HttpHeaders.HOST;
import static com.google.tsunami.callbackserver.common.CbidProcessor.extractCbidFromDomainInHttpProtocol;
import static com.google.tsunami.callbackserver.common.CbidProcessor.extractCbidFromUrl;
import static com.google.tsunami.callbackserver.server.common.RequestLogger.maybeGetClientAddrAsString;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.net.InetAddresses;
import com.google.protobuf.Message;
import com.google.tsunami.callbackserver.proto.HttpInteractionResponse;
import com.google.tsunami.callbackserver.server.common.HttpHandler;
import com.google.tsunami.callbackserver.server.common.monitoring.TcsEventsObserver;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore.InteractionType;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import javax.inject.Inject;

/** HTTP handler for recording interactions via HTTP requests. */
final class HttpRecordingHandler extends HttpHandler {
  private static final String ENDPOINT_NAME = "RECORDING";
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final ImmutableList<String> REDIRECT_PARAMS =
      ImmutableList.of("to", "redirect", "url");
  private static final ImmutableSet<String> APPROVED_REDIRECT_HOSTS =
      ImmutableSet.of("localhost", "127.0.0.1", "::1", "[::1]");
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

  @Override
  protected Optional<String> getRedirectUrl(FullHttpRequest request) {
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

  @Override
  protected Message handleRequest(FullHttpRequest request, Optional<InetAddress> clientAddr) {
    var url = getUrl(request);
    extractCbidFromUrl(url)
        .or(() -> extractCbidFromDomainInHttpProtocol(url))
        .ifPresent(
            cbid -> {
              logger.atInfo().log(
                  "Recording HTTP interaction with CBID '%s' from IP %s",
                  cbid, maybeGetClientAddrAsString(clientAddr));
              interactionStore.add(cbid, InteractionType.HTTP_INTERACTION);
              tcsEventsObserver.onHttpInteractionRecorded();
            });

    // We always reply OK, no matter if we found a callback id or not. This is done on purpose as we
    // don't want to trigger any error handling code in the client making the call.
    return HttpInteractionResponse.newBuilder().setStatus("OK").build();
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
