/*
 * Copyright 2026 Google LLC
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

import static com.google.tsunami.callbackserver.common.CbidProcessor.extractCbidFromDomainInHttpProtocol;
import static com.google.tsunami.callbackserver.common.CbidProcessor.extractCbidFromUrl;
import static com.google.tsunami.callbackserver.common.UrlParser.getQueryParameter;

import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Pluggable handler for custom HTTP response presets served by {@link HttpRecordingHandler} when a
 * request specifies {@code ?response=<paramValue>}.
 */
interface RecordingResponsePreset {
  Pattern VALID_CBID_PATTERN = Pattern.compile("^[a-fA-F0-9]{56}$");

  /** Returns the {@code ?response=} query parameter value that selects this preset. */
  String paramValue();

  /**
   * Handles the incoming HTTP request by writing a response (or asynchronous stream) to {@code
   * ctx}, invoking {@code interactionRecorder} if a callback ID should be recorded, and invoking
   * {@code rpcCompletionRecorder} once the response completes.
   */
  void handle(
      ChannelHandlerContext ctx,
      FullHttpRequest request,
      String requestUrl,
      Consumer<String> interactionRecorder,
      Consumer<HttpResponseStatus> rpcCompletionRecorder);

  /**
   * Extracts the primary callback ID from the URL path or {@code Host} subdomain after stripping
   * any query string or fragment so auxiliary CBID query parameters cannot shadow a subdomain CBID.
   */
  static Optional<String> extractPrimaryCbidWithoutQuery(String requestUrl) {
    int cutIdx = CharMatcher.anyOf("?#").indexIn(requestUrl);
    String urlWithoutQuery = cutIdx >= 0 ? requestUrl.substring(0, cutIdx) : requestUrl;
    return extractCbidFromUrl(urlWithoutQuery)
        .or(() -> extractCbidFromDomainInHttpProtocol(requestUrl));
  }

  /**
   * Extracts and validates a 56-character hex callback ID from the named URI query parameter,
   * normalized to lowercase.
   */
  static Optional<String> extractValidCbidQueryParam(String requestUri, String paramName) {
    return getQueryParameter(requestUri, paramName)
        .filter(VALID_CBID_PATTERN.asMatchPredicate())
        .map(Ascii::toLowerCase);
  }
}
