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
import static com.google.tsunami.callbackserver.common.CbidProcessor.extractCbidFromDomain;
import static com.google.tsunami.callbackserver.common.CbidProcessor.extractCbidFromUrl;

import com.google.protobuf.Message;
import com.google.tsunami.callbackserver.proto.HttpInteractionResponse;
import com.google.tsunami.callbackserver.server.common.HttpHandler;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore.InteractionType;
import io.netty.handler.codec.http.FullHttpRequest;
import javax.inject.Inject;

/** HTTP handler for recording interactions via HTTP requests. */
final class HttpRecordingHandler extends HttpHandler {
  private final InteractionStore interactionStore;

  @Inject
  HttpRecordingHandler(InteractionStore interactionStore) {
    this.interactionStore = interactionStore;
  }

  @Override
  protected Message handleRequest(FullHttpRequest request) {
    String url = getUrl(request);
    extractCbidFromUrl(url)
        .or(() -> extractCbidFromDomain(url))
        .ifPresent(cbid -> interactionStore.add(cbid, InteractionType.HTTP_INTERACTION));
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
