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
package com.google.tsunami.callbackserver.server.polling;

import static com.google.tsunami.callbackserver.common.UrlParser.getQueryParameter;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;
import com.google.tsunami.callbackserver.common.CbidGenerator;
import com.google.tsunami.callbackserver.proto.Interaction;
import com.google.tsunami.callbackserver.proto.PollingResult;
import com.google.tsunami.callbackserver.server.common.HttpHandler;
import com.google.tsunami.callbackserver.server.common.NotFoundException;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import io.netty.handler.codec.http.FullHttpRequest;
import javax.inject.Inject;

final class InteractionPollingHandler extends HttpHandler {
  private final InteractionStore interactionStore;
  private final CbidGenerator cbidGenerator;

  @Inject
  InteractionPollingHandler(InteractionStore interactionStore, CbidGenerator cbidGenerator) {
    this.interactionStore = interactionStore;
    this.cbidGenerator = cbidGenerator;
  }

  @Override
  protected Message handleRequest(FullHttpRequest request) {
    String secret =
        getQueryParameter(request.uri(), "secret")
            .orElseThrow(
                () -> new IllegalArgumentException("Required parameter 'secret' not found."));
    String cbid = cbidGenerator.generate(secret);
    ImmutableList<Interaction> interactions = interactionStore.get(cbid);
    if (interactions.isEmpty()) {
      throw new NotFoundException(String.format("Interaction with secret %s not found.", secret));
    }
    return PollingResult.newBuilder()
        .setHasDnsInteraction(interactions.stream().anyMatch(Interaction::getIsDnsInteraction))
        .setHasHttpInteraction(interactions.stream().anyMatch(Interaction::getIsHttpInteraction))
        .build();
  }
}
