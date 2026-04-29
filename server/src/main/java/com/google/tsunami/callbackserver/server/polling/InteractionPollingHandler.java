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
import static com.google.tsunami.callbackserver.server.common.RequestLogger.maybeGetClientAddrAsString;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.protobuf.Message;
import com.google.tsunami.callbackserver.common.CbidGenerator;
import com.google.tsunami.callbackserver.proto.Interaction;
import com.google.tsunami.callbackserver.proto.PollingResult;
import com.google.tsunami.callbackserver.server.common.HttpHandler;
import com.google.tsunami.callbackserver.server.common.NotFoundException;
import com.google.tsunami.callbackserver.server.common.monitoring.TcsEventsObserver;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import io.netty.handler.codec.http.FullHttpRequest;
import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

final class InteractionPollingHandler extends HttpHandler {
  private static final String ENDPOINT_NAME = "POLLING";
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  // Per-scan correlation secret used to be sent in the URL query string (?secret=...).
  // URL query strings are routinely captured by reverse-proxy access logs, cloud LB request
  // logs, browser history / Referer, ps-output of debugging curl invocations, and CDN
  // analytics — none of which are appropriate channels for a secret. Tsunami clients now
  // send the secret in this header instead. The query-string form remains accepted as a
  // deprecated fallback so older client releases keep polling correctly until they upgrade.
  static final String SECRET_HEADER_NAME = "X-Tsunami-TCS-Secret";

  private final InteractionStore interactionStore;
  private final CbidGenerator cbidGenerator;

  @Inject
  InteractionPollingHandler(
      InteractionStore interactionStore,
      CbidGenerator cbidGenerator,
      TcsEventsObserver tcsEventsObserver) {
    super(ENDPOINT_NAME, HttpHandler.LogNotFoundEx.DONT_LOG, tcsEventsObserver);
    this.interactionStore = interactionStore;
    this.cbidGenerator = cbidGenerator;
  }

  @Override
  protected Message handleRequest(FullHttpRequest request, Optional<InetAddress> clientAddr) {
    String secret = readSecret(request);
    String cbid = cbidGenerator.generate(secret);
    ImmutableList<Interaction> interactions = interactionStore.get(cbid);

    if (interactions.isEmpty()) {
      logger.atFine().log(
          "Interaction with secret '%s' NOT found and polled by IP %s",
          secret, maybeGetClientAddrAsString(clientAddr));
      tcsEventsObserver.onInteractionNotFound();
      throw new NotFoundException(
          // The message does NOT really matter here, since we don't log it but just use this to
          // reply with a 404.
          "Interaction with secret NOT found");
    } else {
      logger.atInfo().log(
          "Interaction with secret '%s' found and polled by IP %s",
          secret, maybeGetClientAddrAsString(clientAddr));
    }

    var hasDnsInteractions = interactions.stream().anyMatch(Interaction::getIsDnsInteraction);
    var hasHttpInteractions = interactions.stream().anyMatch(Interaction::getIsHttpInteraction);

    if (hasDnsInteractions) {
      tcsEventsObserver.onDnsInteractionFound();
    }
    if (hasHttpInteractions) {
      tcsEventsObserver.onHttpInteractionFound();
    }

    return PollingResult.newBuilder()
        .setHasDnsInteraction(hasDnsInteractions)
        .setHasHttpInteraction(hasHttpInteractions)
        .build();
  }

  // Reads the secret from the X-Tsunami-TCS-Secret header when present, falling back to the
  // legacy ?secret= query parameter so older Tsunami clients keep working through the
  // deprecation window. Header takes precedence when both are present.
  private static String readSecret(FullHttpRequest request) {
    String headerSecret = request.headers().get(SECRET_HEADER_NAME);
    if (headerSecret != null && !headerSecret.isEmpty()) {
      return headerSecret;
    }
    Optional<String> querySecret = getQueryParameter(request.uri(), "secret");
    if (querySecret.isPresent()) {
      logger.atInfo().atMostEvery(60, TimeUnit.SECONDS).log(
          "Polling request used the deprecated '?secret=' query parameter. Upgrade the"
              + " Tsunami client so the secret is sent via the %s header instead.",
          SECRET_HEADER_NAME);
      return querySecret.get();
    }
    throw new IllegalArgumentException(
        String.format(
            "Required secret not found. Expected '%s' header or '?secret=' query parameter.",
            SECRET_HEADER_NAME));
  }
}
