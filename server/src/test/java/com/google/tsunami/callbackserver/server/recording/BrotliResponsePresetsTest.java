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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.net.InetAddresses;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.protobuf.ByteString;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.callbackserver.server.common.monitoring.NoOpTcsEventsObserver;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BrotliResponsePresets}. */
@RunWith(JUnit4.class)
public final class BrotliResponsePresetsTest {
  private static final InetAddress LOOPBACK_ADDRESS = InetAddresses.forString("127.0.0.1");
  private static final String FAKE_CBID =
      "b0f3dc043a9c5c05f67651a8c9108b4c2b98e7246b2eea14cb204295";
  private static final String ABORTED_CBID =
      "11111111111111111111111111111111111111111111111111111111";

  private InteractionStore store;
  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private Channel serverChannel;
  private int serverPort;
  private BlockingQueue<Channel> acceptedChannels;

  @SuppressWarnings("deprecation") // Upstream build.gradle targets Netty 4.1 NioEventLoopGroup.
  private void startServer() throws Exception {
    Injector injector =
        Guice.createInjector(
            InMemoryInteractionStore.getModuleForTesting(),
            new FakeUtcClockModule(),
            new NoOpTcsEventsObserver.Module());
    store = injector.getInstance(InteractionStore.class);
    acceptedChannels = new LinkedBlockingQueue<>();

    bossGroup = new NioEventLoopGroup(1);
    workerGroup = new NioEventLoopGroup(1);
    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {
                    acceptedChannels.add(ch);
                    ch.pipeline()
                        .addLast(new HttpServerCodec())
                        .addLast(new HttpServerKeepAliveHandler())
                        .addLast(new HttpObjectAggregator(65535))
                        .addLast(injector.getInstance(HttpRecordingHandler.class));
                  }
                });
    serverChannel = bootstrap.bind(LOOPBACK_ADDRESS, 0).sync().channel();
    serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
  }

  @After
  public void tearDownServer() throws Exception {
    // Close serverChannel before shutting down its boss/worker event-loop groups.
    try {
      if (serverChannel != null) {
        serverChannel.close().sync();
      }
    } finally {
      try {
        if (workerGroup != null) {
          workerGroup.shutdownGracefully().sync();
        }
      } finally {
        if (bossGroup != null) {
          bossGroup.shutdownGracefully().sync();
        }
      }
    }
  }

  @Test
  public void paramValue_whenInspected_returnsExpectedPresetQueryParamNames() {
    assertThat(BrotliResponsePresets.BROTLI_VALID.paramValue()).isEqualTo("brotli_valid");
    assertThat(BrotliResponsePresets.BROTLI_INVALID.paramValue()).isEqualTo("brotli_invalid");
    assertThat(BrotliResponsePresets.BROTLI_CVE_2026_111497.paramValue())
        .isEqualTo("brotli_cve_2026_111497");
  }

  @Test
  public void recordingResponsePresetHelpers_whenCalled_extractExpectedPrimaryAndQueryCbids() {
    assertThat(
            RecordingResponsePreset.extractPrimaryCbidWithoutQuery(
                "http://"
                    + FAKE_CBID
                    + ".cb.localhost/?response=brotli_valid&no_br_cbid="
                    + ABORTED_CBID))
        .hasValue(FAKE_CBID);
    assertThat(
            RecordingResponsePreset.extractValidCbidQueryParam(
                "/?response=brotli_valid&no_br_cbid=" + FAKE_CBID.toUpperCase(), "no_br_cbid"))
        .hasValue(FAKE_CBID);
    assertThat(
            RecordingResponsePreset.extractValidCbidQueryParam(
                "/?response=brotli_valid&no_br_cbid=not_a_cbid", "no_br_cbid"))
        .isEmpty();
  }

  @Test
  public void acceptsBrotli_whenPositiveBrTokenPresent_returnsTrue() {
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("deflate, gzip, br, zstd")))
        .isTrue();
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("gzip;q=1.0, BR;q=0.5")))
        .isTrue();
  }

  @Test
  public void acceptsBrotli_whenZeroOrInvalidQualityWeight_returnsFalse() {
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("gzip, br;q=0"))).isFalse();
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("br;q=0.000"))).isFalse();
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("br;q=NaN"))).isFalse();
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("br;q=1.5"))).isFalse();
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("br;q=invalid"))).isFalse();
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("br;q="))).isFalse();
  }

  @Test
  public void acceptsBrotli_whenBrTokenAbsent_returnsFalse() {
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("none"))).isFalse();
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("deflate, gzip"))).isFalse();
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of("brx"))).isFalse();
    assertThat(BrotliResponsePresets.acceptsBrotli(ImmutableList.of())).isFalse();
  }

  @Test
  public void isNodeFetchPlainHttpBrotliClient_whenNodeOrUndiciWithGzipDeflate_returnsTrue() {
    assertThat(
            BrotliResponsePresets.isNodeFetchPlainHttpBrotliClient(
                "node", ImmutableList.of("gzip, deflate")))
        .isTrue();
    assertThat(
            BrotliResponsePresets.isNodeFetchPlainHttpBrotliClient(
                "undici", ImmutableList.of("gzip, deflate")))
        .isTrue();
    assertThat(
            BrotliResponsePresets.isNodeFetchPlainHttpBrotliClient(
                "node", ImmutableList.of("gzip, deflate, br;q=0")))
        .isFalse();
    assertThat(
            BrotliResponsePresets.isNodeFetchPlainHttpBrotliClient(
                "node", ImmutableList.of("none")))
        .isFalse();
    assertThat(
            BrotliResponsePresets.isNodeFetchPlainHttpBrotliClient(
                "curl/8.5.0", ImmutableList.of("gzip, deflate")))
        .isFalse();
    assertThat(
            BrotliResponsePresets.isNodeFetchPlainHttpBrotliClient(
                null, ImmutableList.of("gzip, deflate")))
        .isFalse();
  }

  @Test
  public void buildEmptyPlainResponse_whenCalled_setsSafeNoSniffHeadersAndEmptyBody() {
    FullHttpResponse response = BrotliResponsePresets.buildEmptyPlainResponse();
    try {
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(response.headers().contains(HttpHeaderNames.CONTENT_ENCODING)).isFalse();
      assertThat(response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo(0);
      assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
          .isEqualTo("text/plain; charset=utf-8");
      assertThat(response.headers().get("X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(response.headers().get(HttpHeaderNames.CACHE_CONTROL))
          .isEqualTo(HttpHeaderValues.NO_STORE.toString());
      assertThat(response.content().readableBytes()).isEqualTo(0);
    } finally {
      ReferenceCountUtil.release(response);
    }
  }

  @Test
  public void buildValidAndInvalidBrotliResponses_whenCalled_setSafeHeadersAndExpectedPayloads() {
    FullHttpResponse valid = BrotliResponsePresets.buildValidBrotliResponse();
    FullHttpResponse invalid = BrotliResponsePresets.buildInvalidBrotliResponse();
    try {
      assertThat(valid.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("br");
      assertThat(valid.headers().getInt(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo(57);
      assertThat(valid.headers().get(HttpHeaderNames.CACHE_CONTROL))
          .isEqualTo(HttpHeaderValues.NO_STORE.toString());
      assertThat(readByteString(valid.content())).isEqualTo(BrotliResponsePresets.VALID_BROTLI);

      assertThat(invalid.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("br");
      assertThat(invalid.headers().getInt(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo(57);
      assertThat(readByteString(invalid.content())).isEqualTo(BrotliResponsePresets.INVALID_BROTLI);
    } finally {
      ReferenceCountUtil.release(valid);
      ReferenceCountUtil.release(invalid);
    }
  }

  @Test
  public void probePayloads_whenInitialized_haveExpectedByteLengths() {
    assertThat(BrotliResponsePresets.DECOMPRESSED_PAYLOAD.size()).isEqualTo(53);
    assertThat(BrotliResponsePresets.PROBE_HEADER.size()).isEqualTo(17);
    assertThat(BrotliResponsePresets.PROBE_TAIL.size()).isEqualTo(93);
    assertThat(BrotliResponsePresets.PROBE_TOTAL_LENGTH).isEqualTo(23_068_782L);
  }

  @Test
  public void streamCve2026111497Probe_whenClientClosesEarly_doesNotRecordPrimaryCbid()
      throws Exception {
    startServer();
    try (Socket sock = new Socket(LOOPBACK_ADDRESS, serverPort)) {
      sock.setReceiveBufferSize(8192);
      OutputStream out = sock.getOutputStream();
      out.write(
          ("GET /"
                  + ABORTED_CBID
                  + "?response=brotli_cve_2026_111497 HTTP/1.1\r\n"
                  + "Host: 127.0.0.1:"
                  + serverPort
                  + "\r\n"
                  + "Accept-Encoding: br\r\n\r\n")
              .getBytes(UTF_8));
      out.flush();
      byte[] prefix = new byte[1024];
      ByteStreams.readFully(sock.getInputStream(), prefix);
    }

    Channel accepted = acceptedChannels.poll(5, SECONDS);
    assertThat(accepted).isNotNull();
    accepted.closeFuture().sync();
    assertThat(store.get(ABORTED_CBID)).isEmpty();
  }

  @Test
  public void streamCve2026111497Probe_whenClientReadsFullStream_recordsPrimaryCbid()
      throws Exception {
    startServer();
    try (Socket sock = new Socket(LOOPBACK_ADDRESS, serverPort)) {
      OutputStream out = sock.getOutputStream();
      out.write(
          ("GET /"
                  + FAKE_CBID
                  + "?response=brotli_cve_2026_111497 HTTP/1.1\r\n"
                  + "Host: 127.0.0.1:"
                  + serverPort
                  + "\r\n"
                  + "Accept-Encoding: br\r\n\r\n")
              .getBytes(UTF_8));
      out.flush();
      InputStream in = sock.getInputStream();
      long totalWireBytes = ByteStreams.exhaust(in);
      assertThat(totalWireBytes).isGreaterThan(BrotliResponsePresets.PROBE_TOTAL_LENGTH);
    }

    Channel accepted = acceptedChannels.poll(5, SECONDS);
    assertThat(accepted).isNotNull();
    accepted.closeFuture().sync();
    assertThat(store.get(FAKE_CBID)).hasSize(1);
  }

  private static ByteString readByteString(ByteBuf buf) {
    byte[] out = new byte[buf.readableBytes()];
    buf.getBytes(buf.readerIndex(), out);
    return ByteString.copyFrom(out);
  }
}
