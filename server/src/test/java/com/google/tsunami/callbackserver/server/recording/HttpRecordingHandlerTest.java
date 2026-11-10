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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.net.HttpHeaders;
import com.google.common.net.InetAddresses;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClock;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.callbackserver.proto.HttpInteractionResponse;
import com.google.tsunami.callbackserver.proto.Interaction;
import com.google.tsunami.callbackserver.server.common.monitoring.TcsEventsObserver;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayOutputStream;
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
  private static final String FAKE_NO_BR_CBID =
      "c1e4ed154bad6d16078762b9da219c5d3ca9f8357c3ffb25dc3153a6";
  private static final Interaction FAKE_HTTP_INTERACTION =
      Interaction.newBuilder()
          .setIsHttpInteraction(true)
          .setIsDnsInteraction(false)
          .setRecordTime(Timestamps.fromMillis(fakeUtcClock.instant().toEpochMilli()))
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
    var _ = handler.handleRequest(buildRequest("127.0.0.1", "/" + FAKE_CBID), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenValidCbidInPathAndPortInHost_savesInteraction() {
    var _ =
        handler.handleRequest(buildRequest("127.0.0.1:8080", "/" + FAKE_CBID), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenValidCbidEmbeddedInPath_savesInteraction() {
    var _ =
        handler.handleRequest(
            buildRequest("127.0.0.1", "/img%20" + FAKE_CBID), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenValidCbidInQueryParameter_savesInteraction() {
    var _ =
        handler.handleRequest(
            buildRequest("127.0.0.1", "/fetch?url=http://127.0.0.1/" + FAKE_CBID),
            TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenInvalidCbidInPath_ignoresPathValue() {
    var _ = handler.handleRequest(buildRequest("127.0.0.1", "/RANDOM_PATH"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get("RANDOM_PATH")).isEmpty();
    verify(eventsObserverMock, never()).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenValidCbidInDomain_savesInteraction() {
    var _ =
        handler.handleRequest(buildRequest(FAKE_CBID + ".domain.com", "/"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenValidCbidInDomainWithPort_savesInteraction() {
    var _ =
        handler.handleRequest(
            buildRequest(FAKE_CBID + ".domain.com:8080", "/"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    verify(eventsObserverMock).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenInvalidCbidInDomain_ignoresPathValue() {
    var _ =
        handler.handleRequest(buildRequest("RANDOM_DOMAIN.domain.com", "/"), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get("RANDOM_DOMAIN")).isEmpty();
    verify(eventsObserverMock, never()).onHttpInteractionRecorded();
  }

  @Test
  public void handleRequest_whenMissingHostname_throws() {
    DefaultFullHttpRequest request = buildRequest("", "/");

    assertThrows(
        IllegalArgumentException.class, () -> handler.handleRequest(request, TEST_CLIENT_ADDRESS));
    verify(eventsObserverMock, never()).onHttpInteractionRecorded();
  }

  @Test
  public void channelRead_whenUnknownResponsePreset_usesDefaultJsonBehavior() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest("127.0.0.1", "/" + FAKE_CBID + "?response=oauth_code");
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "deflate, gzip, br");

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
          .isEqualTo("application/json");
      assertThat(response.headers().contains(HttpHeaderNames.CONTENT_ENCODING)).isFalse();
      assertThat(response.content().toString(UTF_8)).contains("\"status\": \"OK\"");
      assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
    }
  }

  @Test
  public void channelRead_whenHeadRequestWithBrotliPreset_usesDefaultHandlerWithoutStreaming() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.HEAD,
            "/" + FAKE_CBID + "?response=brotli_cve_2026_111497");
    request.headers().add(HttpHeaders.HOST, "127.0.0.1");
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "br");

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
          .isEqualTo("application/json");
      assertThat(response.headers().contains(HttpHeaderNames.CONTENT_ENCODING)).isFalse();
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
    }
  }

  @Test
  public void channelRead_whenBrotliValidAndBrAccepted_recordsPrimaryCbidAndServesBrotli() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest(
            "127.0.0.1:8080",
            "/" + FAKE_CBID + "?response=brotli_valid&no_br_cbid=" + FAKE_NO_BR_CBID);
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "deflate, gzip, br, zstd");

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("br");
      assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
          .isEqualTo("text/plain; charset=utf-8");
      assertThat(response.headers().get("X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(response.headers().get(HttpHeaderNames.CACHE_CONTROL))
          .isEqualTo(HttpHeaderValues.NO_STORE.toString());
      assertThat(ByteString.copyFrom(readBytes(response.content())))
          .isEqualTo(BrotliResponsePresets.VALID_BROTLI);
      assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
      assertThat(interactionStore.get(FAKE_NO_BR_CBID)).isEmpty();
      verify(eventsObserverMock)
          .onSuccessfulHttpRpc(eq("RECORDING"), any(), eq(HttpResponseStatus.OK));
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
    }
  }

  @Test
  public void channelRead_whenBrotliInvalidAndBrAccepted_recordsPrimaryCbidAndServesInvalidBytes() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest(
            "127.0.0.1",
            "/" + FAKE_CBID + "?response=brotli_invalid&no_br_cbid=" + FAKE_NO_BR_CBID);
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "br");

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("br");
      assertThat(ByteString.copyFrom(readBytes(response.content())))
          .isEqualTo(BrotliResponsePresets.INVALID_BROTLI);
      assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
      assertThat(interactionStore.get(FAKE_NO_BR_CBID)).isEmpty();
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
    }
  }

  @Test
  public void channelRead_whenBrotliPresetAndAcceptEncodingNone_recordsNoBrCbidAndServesEmpty() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest(
            "127.0.0.1",
            "/" + FAKE_CBID + "?response=brotli_invalid&no_br_cbid=" + FAKE_NO_BR_CBID);
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "none");

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(response.headers().contains(HttpHeaderNames.CONTENT_ENCODING)).isFalse();
      assertThat(response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo(0);
      assertThat(response.headers().get("X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(response.content().readableBytes()).isEqualTo(0);
      assertThat(interactionStore.get(FAKE_CBID)).isEmpty();
      assertThat(interactionStore.get(FAKE_NO_BR_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
    }
  }

  @Test
  public void channelRead_whenNoAcceptEncodingHeaderAndBrotliPreset_recordsNoBrCbid() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest(
            "127.0.0.1", "/" + FAKE_CBID + "?response=brotli_valid&no_br_cbid=" + FAKE_NO_BR_CBID);

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(response.headers().contains(HttpHeaderNames.CONTENT_ENCODING)).isFalse();
      assertThat(interactionStore.get(FAKE_CBID)).isEmpty();
      assertThat(interactionStore.get(FAKE_NO_BR_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
    }
  }

  @Test
  public void
      channelRead_whenNodeFetchPlainHttpClientRequestsBrotliPreset_recordsPrimaryCbidAndServesBrotli() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest(
            "127.0.0.1:8080",
            "/" + FAKE_CBID + "?response=brotli_valid&no_br_cbid=" + FAKE_NO_BR_CBID);
    request.headers().set(HttpHeaderNames.USER_AGENT, "node");
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate");

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("br");
      assertThat(ByteString.copyFrom(readBytes(response.content())))
          .isEqualTo(BrotliResponsePresets.VALID_BROTLI);
      assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
      assertThat(interactionStore.get(FAKE_NO_BR_CBID)).isEmpty();
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
    }
  }

  @Test
  public void channelRead_whenMalformedNoBrCbidAndAcceptEncodingNone_ignoresMalformedCbid() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest("127.0.0.1", "/" + FAKE_CBID + "?response=brotli_invalid&no_br_cbid=not_hex");
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "none");

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(interactionStore.get(FAKE_CBID)).isEmpty();
      assertThat(interactionStore.get("not_hex")).isEmpty();
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
    }
  }

  @Test
  public void channelRead_whenDomainCbidAndNoBrCbidInQueryAndBrAccepted_recordsDomainCbid() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest(
            FAKE_CBID + ".domain.com",
            "/?response=brotli_valid&no_br_cbid=" + FAKE_NO_BR_CBID + "#" + FAKE_NO_BR_CBID);
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip, br");

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
      assertThat(interactionStore.get(FAKE_NO_BR_CBID)).isEmpty();
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
    }
  }

  @Test
  public void channelRead_whenBrotliCve2026111497WithoutValidCbid_servesEmptyWithoutStreaming() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest("127.0.0.1", "/?response=brotli_cve_2026_111497");
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "br");

    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(response.headers().contains(HttpHeaderNames.CONTENT_ENCODING)).isFalse();
      assertThat(response.content().readableBytes()).isEqualTo(0);
      verify(eventsObserverMock, never()).onHttpInteractionRecorded();
      verify(eventsObserverMock)
          .onSuccessfulHttpRpc(eq("RECORDING"), any(), eq(HttpResponseStatus.OK));
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
    }
  }

  @Test
  public void channelRead_whenBrotliCve2026111497AndBrAccepted_streamsFullProbeAndRecordsCbid() {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DefaultFullHttpRequest request =
        buildRequest(
            "127.0.0.1",
            "/" + FAKE_CBID + "?response=brotli_cve_2026_111497&no_br_cbid=" + FAKE_NO_BR_CBID);
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "deflate, gzip, br, zstd");

    try {
      channel.writeInbound(request);

      HttpResponse responseHeaders = null;
      boolean sawLastHttpContent = false;
      long totalBodyBytes = 0;
      ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
      byte[] trailingWindow = new byte[BrotliResponsePresets.PROBE_TAIL.size()];
      int zeroViolationsInMiddle = 0;

      Object msg;
      while ((msg = channel.readOutbound()) != null) {
        try {
          assertThat(msg).isInstanceOf(HttpObject.class);
          if (msg instanceof HttpResponse) {
            responseHeaders = (HttpResponse) msg;
          }
          if (msg instanceof HttpContent) {
            byte[] chunk = readBytes(((HttpContent) msg).content());
            for (byte b : chunk) {
              long pos = totalBodyBytes;
              if (pos < BrotliResponsePresets.PROBE_HEADER.size()) {
                headBytes.write(b);
              } else if (pos
                  < BrotliResponsePresets.PROBE_HEADER.size()
                      + BrotliResponsePresets.PROBE_MIDDLE_ZERO_LEN) {
                if (b != 0) {
                  zeroViolationsInMiddle++;
                }
              } else {
                int tailIdx =
                    (int)
                        (pos
                            - BrotliResponsePresets.PROBE_HEADER.size()
                            - BrotliResponsePresets.PROBE_MIDDLE_ZERO_LEN);
                trailingWindow[tailIdx] = b;
              }
              totalBodyBytes++;
            }
          }
          if (msg instanceof LastHttpContent) {
            sawLastHttpContent = true;
          }
        } finally {
          ReferenceCountUtil.release(msg);
        }
      }

      assertThat(responseHeaders).isNotNull();
      assertThat(responseHeaders.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(responseHeaders.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("br");
      assertThat(responseHeaders.headers().get(HttpHeaderNames.CONTENT_LENGTH))
          .isEqualTo(String.valueOf(BrotliResponsePresets.PROBE_TOTAL_LENGTH));
      assertThat(responseHeaders.headers().get("X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(responseHeaders.headers().get(HttpHeaderNames.CACHE_CONTROL))
          .isEqualTo(HttpHeaderValues.NO_STORE.toString());
      assertThat(responseHeaders.headers().get(HttpHeaderNames.CONNECTION))
          .isEqualTo(HttpHeaderValues.CLOSE.toString());
      assertThat(sawLastHttpContent).isTrue();
      assertThat(totalBodyBytes).isEqualTo(BrotliResponsePresets.PROBE_TOTAL_LENGTH);
      assertThat(ByteString.copyFrom(headBytes.toByteArray()))
          .isEqualTo(BrotliResponsePresets.PROBE_HEADER);
      assertThat(zeroViolationsInMiddle).isEqualTo(0);
      assertThat(ByteString.copyFrom(trailingWindow)).isEqualTo(BrotliResponsePresets.PROBE_TAIL);
      assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_HTTP_INTERACTION);
      assertThat(interactionStore.get(FAKE_NO_BR_CBID)).isEmpty();
      verify(eventsObserverMock)
          .onSuccessfulHttpRpc(eq("RECORDING"), any(), eq(HttpResponseStatus.OK));
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  private static byte[] readBytes(ByteBuf buf) {
    byte[] out = new byte[buf.readableBytes()];
    buf.getBytes(buf.readerIndex(), out);
    return out;
  }

  private static DefaultFullHttpRequest buildRequest(String host, String path) {
    DefaultFullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    request.headers().add(HttpHeaders.HOST, host);
    return request;
  }
}
