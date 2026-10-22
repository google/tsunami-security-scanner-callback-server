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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.net.HttpHeaders;
import com.google.common.net.InetAddresses;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.protobuf.Message;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClock;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.callbackserver.proto.HttpInteractionResponse;
import com.google.tsunami.callbackserver.proto.Interaction;
import com.google.tsunami.callbackserver.server.common.monitoring.TcsEventsObserver;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import javax.inject.Inject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

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
  private static final Optional<InetAddress> TEST_CLIENT_ADDRESS =
      Optional.of(InetAddresses.forString("1.2.3.4"));

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock TcsEventsObserver eventsObserverMock;

  @Inject private HttpRecordingHandler handler;
  @Inject private InteractionStore interactionStore;

  @Before
  public void setUp() {
    Guice.createInjector(
            InMemoryInteractionStore.getModuleForTesting(),
            new FakeUtcClockModule(fakeUtcClock),
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(TcsEventsObserver.class).toInstance(eventsObserverMock);
              }
            })
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
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenValidCbidInPathAndPortInHost_savesInteraction() {
    var unused =
        handler.handleRequest(buildRequest("127.0.0.1:8080", "/" + FAKE_CBID), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenValidCbidEmbeddedInPath_savesInteraction() {
    var unused =
        handler.handleRequest(
            buildRequest("127.0.0.1", "/img%20" + FAKE_CBID), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenValidCbidInQueryParameter_savesInteraction() {
    var unused =
        handler.handleRequest(
            buildRequest("127.0.0.1", "/fetch?url=http://127.0.0.1/" + FAKE_CBID),
            TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenInvalidCbidInPath_ignoresPathValue() {
    var unused =
        handler.handleRequest(buildRequest("127.0.0.1", "/RANDOM_PATH"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get("RANDOM_PATH")).isEmpty();
    verify(eventsObserverMock, never()).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenValidCbidInDomain_savesInteraction() {
    var unused =
        handler.handleRequest(buildRequest(FAKE_CBID + ".domain.com", "/"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenValidCbidInDomainWithPort_savesInteraction() {
    var unused =
        handler.handleRequest(
            buildRequest(FAKE_CBID + ".domain.com:8080", "/"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenInvalidCbidInDomain_ignoresPathValue() {
    var unused =
        handler.handleRequest(buildRequest("RANDOM_DOMAIN.domain.com", "/"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get("RANDOM_DOMAIN")).isEmpty();
    verify(eventsObserverMock, never()).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenMissingHostname_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> handler.handleRequest(buildRequest("", "/"), TEST_CLIENT_ADDRESS));
    verify(eventsObserverMock, never()).onHttpInteractionRecorded();
  }

  @Test
  public void channelRead0_whenRedirectParamTo_returnsFoundAndRecordsInteraction() {
    String destination = "http://127.0.0.1:5000/api/2.0/mlflow/experiments/list";
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest("127.0.0.1", "/" + FAKE_CBID + "?to=" + destination);

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertThat(response.status()).isEqualTo(HttpResponseStatus.FOUND);
    assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isEqualTo(destination);
    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void channelRead0_whenRedirectParamRedirect_returnsFoundAndRecordsInteraction() {
    String destination = "http://127.0.0.1:5000/api";
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest("127.0.0.1", "/" + FAKE_CBID + "?redirect=" + destination);

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertThat(response.status()).isEqualTo(HttpResponseStatus.FOUND);
    assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isEqualTo(destination);
    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void channelRead0_whenNoRedirectParam_returnsOkStatus() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request = buildRequest("127.0.0.1", "/" + FAKE_CBID);

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isNull();
    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void channelRead0_whenRedirectParamToWithEncodedUrl_returnsDecodedLocation() {
    String destination = "http://127.0.0.1:5000/api?param1=val1&param2=val2";
    String encodedDestination = "http%3A%2F%2F127.0.0.1%3A5000%2Fapi%3Fparam1%3Dval1%26param2%3Dval2";
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest("127.0.0.1", "/" + FAKE_CBID + "?to=" + encodedDestination);

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertThat(response.status()).isEqualTo(HttpResponseStatus.FOUND);
    assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isEqualTo(destination);
    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void channelRead0_whenRedirectParamIsEmpty_returnsOkStatus() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request = buildRequest("127.0.0.1", "/" + FAKE_CBID + "?to=");

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isNull();
    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void channelRead0_whenRedirectParamToLocalhost_returnsFoundAndRecordsInteraction() {
    String destination = "http://localhost:5000/api";
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest("127.0.0.1", "/" + FAKE_CBID + "?to=" + destination);

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertThat(response.status()).isEqualTo(HttpResponseStatus.FOUND);
    assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isEqualTo(destination);
    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void channelRead0_whenRedirectParamToUnapprovedHost_returnsOkStatusAndDoesNotRedirect() {
    String destination = "https://evil.com/phishing";
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest("127.0.0.1", "/" + FAKE_CBID + "?to=" + destination);

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isNull();
    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  private static DefaultFullHttpRequest buildRequest(String host, String path) {
    DefaultFullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    request.headers().add(HttpHeaders.HOST, host);
    return request;
  }
}
