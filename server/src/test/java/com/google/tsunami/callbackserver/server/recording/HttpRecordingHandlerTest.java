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

import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.net.HttpHeaders;
import com.google.common.net.InetAddresses;
import com.google.inject.Guice;
import com.google.protobuf.Message;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClock;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.callbackserver.proto.HttpInteractionResponse;
import com.google.tsunami.callbackserver.proto.Interaction;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.time.Instant;
import javax.inject.Inject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link HttpRecordingHandler}. */
@RunWith(JUnit4.class)
public final class HttpRecordingHandlerTest {
  private static final FakeUtcClock fakeUtcClock =
      FakeUtcClock.create().setNow(Instant.parse("2020-01-01T00:00:00.00Z"));
  private static final String FAKE_CBID =
      "b0f3dc043a9c5c05f67651a8c9108b4c2b98e7246b2eea14cb204295";
  private static final Interaction FAKE_HTTP_INTERACTION =
      Interaction.newBuilder()
          .setIsHttpInteraction(true)
          .setIsDnsInteraction(false)
          .setRecordTime(Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
          .build();
  private static final InetAddress TEST_CLIENT_ADDRESS = InetAddresses.forString("1.2.3.4");

  @Inject private HttpRecordingHandler handler;
  @Inject private InteractionStore interactionStore;

  @Before
  public void setUp() {
    Guice.createInjector(
            InMemoryInteractionStore.getModuleForTesting(), new FakeUtcClockModule(fakeUtcClock))
        .injectMembers(this);
  }

  @Test
  public void handleRequest_whenNoErrors_alwaysReturnOkStatus() {
    Message response = handler.handleRequest(buildRequest("127.0.0.1", "/"), TEST_CLIENT_ADDRESS);

    assertThat(response).isEqualTo(HttpInteractionResponse.newBuilder().setStatus("OK").build());
  }

  @Test
  public void handleRequest_whenValidCbidInPath_savesInteraction() {
    var unused =
        handler.handleRequest(buildRequest("127.0.0.1", "/" + FAKE_CBID), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
  }

  @Test
  public void handleRequest_whenValidCbidInPathAndPortInHost_savesInteraction() {
    var unused =
        handler.handleRequest(buildRequest("127.0.0.1:8080", "/" + FAKE_CBID), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
  }

  @Test
  public void handleRequest_whenInvalidCbidInPath_ignoresPathValue() {
    var unused =
        handler.handleRequest(buildRequest("127.0.0.1", "/RANDOM_PATH"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get("RANDOM_PATH")).isEmpty();
  }

  @Test
  public void handleRequest_whenValidCbidInDomain_savesInteraction() {
    var unused =
        handler.handleRequest(buildRequest(FAKE_CBID + ".domain.com", "/"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
  }

  @Test
  public void handleRequest_whenValidCbidInDomainWithPort_savesInteraction() {
    var unused =
        handler.handleRequest(
            buildRequest(FAKE_CBID + ".domain.com:8080", "/"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
  }

  @Test
  public void handleRequest_whenInvalidCbidInDomain_ignoresPathValue() {
    var unused =
        handler.handleRequest(buildRequest("RANDOM_DOMAIN.domain.com", "/"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get("RANDOM_DOMAIN")).isEmpty();
  }

  @Test
  public void handleRequest_whenMissingHostname_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> handler.handleRequest(buildRequest("", "/"), TEST_CLIENT_ADDRESS));
  }

  private static DefaultFullHttpRequest buildRequest(String host, String path) {
    DefaultFullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    request.headers().add(HttpHeaders.HOST, host);
    return request;
  }
}
