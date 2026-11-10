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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Built-in safe HTTP response presets for out-of-band Brotli decoder differential checks (such as
 * CVE-2026-111497 via outbound HTTP client transports).
 *
 * <h3>Background: CVE-2026-111497 in {@code libbrotlidec}</h3>
 *
 * <p>In {@code libbrotlidec <= 1.1.0} (prior to commit {@code 929be52}), {@code
 * BrotliDecoderDecompressStream} maintains the remaining distance block length in a 32-bit signed
 * integer ({@code s->block_length[2]}). When a compressed metablock declares only one distance
 * block type ({@code NBLTYPESD == 1}), the decoder skips block-switch commands and initializes
 * {@code s->block_length[2] = 1 << 24} ({@code 16,777,216}), decrementing it once per distance code
 * without checking for underflow. After {@code 16,777,217} commands in a single metablock, the
 * counter wraps past zero. In unpatched {@code libbrotlidec}, decompression continues seamlessly to
 * the end of the stream; the upstream fix explicitly checks for {@code s->block_length[2] == 0} and
 * immediately aborts decompression with {@code BROTLI_DECODER_ERROR_FORMAT_BLOCK_LENGTH_2}.
 *
 * <h3>How Scanners Use These Presets</h3>
 *
 * <p>Many targets (such as WordPress {@code xmlrpc.php} {@code pingback.ping} over PHP/libcurl) can
 * be induced to make outbound HTTP {@code GET} requests to the Tsunami Callback Server (TCS) and
 * transparently decompress {@code Content-Encoding: br} responses when linked against {@code
 * libbrotlidec}. By selecting a preset via {@code ?response=<preset>}, a detector can distinguish
 * vulnerable and patched targets with zero heap corruption and zero target side effects:
 *
 * <ol>
 *   <li><b>Capability &amp; differential pre-checks ({@link #BROTLI_VALID} vs. {@link
 *       #BROTLI_INVALID})</b>: Both presets serve a 57-byte response with {@code Content-Encoding:
 *       br}. {@link #BROTLI_VALID} serves a valid RFC 7932 stream decompressing to {@link
 *       #DECOMPRESSED_PAYLOAD}, whereas {@link #BROTLI_INVALID} serves a deterministic invalid
 *       stream that every Brotli decoder rejects at byte 0. Comparing target behavior (or observing
 *       whether the HTTP client retries without {@code br} in {@code Accept-Encoding}) confirms
 *       that the target actively decompresses Brotli responses.
 *   <li><b>Zero-side-effect CVE-2026-111497 overflow probe ({@link #BROTLI_CVE_2026_111497})</b>:
 *       Streams a 23.07 MB valid Brotli bitstream containing {@code 16,777,217} zero-output
 *       dictionary commands followed by {@link #DECOMPRESSED_PAYLOAD}:
 *       <ul>
 *         <li>An <b>unpatched</b> {@code libbrotlidec} wraps {@code s->block_length[2]} at command
 *             {@code #16,777,217} without corrupting memory (because {@code NBLTYPESD == 1} never
 *             indexes the block-type ring buffer) and finishes decompressing {@link
 *             #DECOMPRESSED_PAYLOAD}.
 *         <li>A <b>patched</b> {@code libbrotlidec} aborts with {@code
 *             BROTLI_DECODER_ERROR_FORMAT_BLOCK_LENGTH_2} as soon as command {@code #16,777,217} in
 *             {@link #PROBE_TAIL} is reached. In HTTP clients such as WordPress {@code
 *             WP_Http_Curl}, a mid-stream decompression error triggers a second fallback request
 *             with {@code Accept-Encoding: none}, which TCS records under the auxiliary {@code
 *             &no_br_cbid=<cbid>} token.
 *       </ul>
 * </ol>
 *
 * <p>All preset responses use fixed, non-user-controlled payloads served as {@code text/plain;
 * charset=utf-8} with {@code X-Content-Type-Options: nosniff} so that publicly reachable TCS
 * domains cannot be abused to reflect HTML or scripts.
 */
enum BrotliResponsePresets implements RecordingResponsePreset {
  /**
   * Serves a 57-byte valid RFC 7932 Brotli stream ({@link #VALID_BROTLI}) with {@code
   * Content-Encoding: br} and records the primary callback ID.
   */
  BROTLI_VALID("brotli_valid") {
    @Override
    void handleAcceptedBrotli(
        ChannelHandlerContext ctx,
        Optional<String> primaryCbid,
        Consumer<String> interactionRecorder,
        Consumer<HttpResponseStatus> rpcCompletionRecorder) {
      replyFixedResponse(
          ctx, primaryCbid, buildValidBrotliResponse(), interactionRecorder, rpcCompletionRecorder);
    }
  },
  /**
   * Serves a 57-byte invalid Brotli stream ({@link #INVALID_BROTLI}) with {@code Content-Encoding:
   * br} that immediately fails RFC 7932 header validation, and records the primary callback ID.
   */
  BROTLI_INVALID("brotli_invalid") {
    @Override
    void handleAcceptedBrotli(
        ChannelHandlerContext ctx,
        Optional<String> primaryCbid,
        Consumer<String> interactionRecorder,
        Consumer<HttpResponseStatus> rpcCompletionRecorder) {
      replyFixedResponse(
          ctx,
          primaryCbid,
          buildInvalidBrotliResponse(),
          interactionRecorder,
          rpcCompletionRecorder);
    }
  },
  /**
   * Streams the 23.07 MB zero-output CVE-2026-111497 format-validation bitstream over 64 KiB
   * chunks, recording the primary callback ID right before the 93-byte tail only if the client
   * consumes all preceding chunks without disconnecting.
   */
  BROTLI_CVE_2026_111497("brotli_cve_2026_111497") {
    @Override
    void handleAcceptedBrotli(
        ChannelHandlerContext ctx,
        Optional<String> primaryCbid,
        Consumer<String> interactionRecorder,
        Consumer<HttpResponseStatus> rpcCompletionRecorder) {
      // Require a valid primary CBID before streaming 23 MB so unauthenticated scanners or random
      // crawlers hitting /?response=brotli_cve_2026_111497 cannot trigger large outbound streams.
      if (primaryCbid.isEmpty()) {
        replyFixedResponse(
            ctx,
            Optional.empty(),
            buildEmptyPlainResponse(),
            interactionRecorder,
            rpcCompletionRecorder);
        return;
      }
      String cbid = primaryCbid.get();
      streamCve2026111497Probe(
          ctx,
          () -> interactionRecorder.accept(cbid),
          () -> rpcCompletionRecorder.accept(HttpResponseStatus.OK));
    }
  };

  /**
   * Optional query parameter carrying a secondary 56-hex-character CBID that is recorded only when
   * a client requests a Brotli preset without advertising {@code br} in {@code Accept-Encoding}.
   *
   * <p>This allows detectors to observe when an HTTP client (such as WordPress {@code WP_Http})
   * catches a Brotli stream failure on the initial request and automatically retries the same URL
   * with {@code Accept-Encoding: none}.
   */
  private static final String NO_BR_CBID_QUERY_PARAM = "no_br_cbid";

  private static final AsciiString SAFE_CONTENT_TYPE =
      AsciiString.cached("text/plain; charset=utf-8");
  private static final AsciiString X_CONTENT_TYPE_OPTIONS =
      AsciiString.cached("X-Content-Type-Options");
  private static final AsciiString NOSNIFF = AsciiString.cached("nosniff");

  private static final Splitter COMMA_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();
  private static final Splitter SEMICOLON_SPLITTER =
      Splitter.on(';').trimResults().omitEmptyStrings();

  private static final long STREAM_DEADLINE_SECONDS = 30;

  /**
   * 53-byte static payload containing no {@code <title>} tag and no {@code <a>} link so WordPress
   * {@code pingback.ping} aborts before {@code wp_new_comment} with zero database writes while
   * returning a deterministic non-empty-body fault code ({@code faultCode 33} instead of {@code
   * faultCode 16}).
   */
  @VisibleForTesting
  static final ByteString DECOMPRESSED_PAYLOAD =
      ByteString.copyFromUtf8("<html><body>tsunami_brotli_check_7f9a2b</body></html>");

  /**
   * 57-byte valid RFC 7932 Brotli stream ({@code WBITS = 16}, single uncompressed metablock of 53
   * bytes, final empty metablock {@code 0x03}) decompressing to {@link #DECOMPRESSED_PAYLOAD}.
   */
  @VisibleForTesting
  static final ByteString VALID_BROTLI =
      ByteString.copyFrom(BaseEncoding.base16().lowerCase().decode("400310"))
          .concat(DECOMPRESSED_PAYLOAD)
          .concat(ByteString.copyFrom(new byte[] {0x03}));

  /**
   * 57-byte invalid Brotli stream matching the wire length of {@link #VALID_BROTLI} whose opening
   * byte (<code>'{'</code> = {@code 0x7b}) immediately fails RFC 7932 Section 9.2 padding
   * validation ({@code BROTLI_DECODER_ERROR_FORMAT_PADDING_2}) in any Brotli decoder.
   */
  @VisibleForTesting
  static final ByteString INVALID_BROTLI =
      ByteString.copyFromUtf8(
          Strings.padEnd("{tsunami_brotli_invalid_control_", VALID_BROTLI.size() - 1, '_') + "}");

  /**
   * 17-byte header for the zero-side-effect CVE-2026-111497 format-validation bitstream:
   *
   * <ul>
   *   <li>{@code WBITS = 16} (64 KiB sliding window) + 1-byte empty metadata metablock (which
   *       forces {@code libbrotlidec} to allocate the sliding window up front while {@code s->pos
   *       == 0}).
   *   <li>Compressed metablock header declaring {@code MLEN = 65,536}, {@code NBLTYPESL = 1},
   *       {@code NBLTYPESI = 1}, and {@code NBLTYPESD = 1} (so {@code s->block_length[2]} starts at
   *       the implicit default of {@code 1 << 24 = 16,777,216}).
   *   <li>Compact Huffman trees where a single 11-bit all-zero command code encodes {@code
   *       insert_len = 0}, {@code copy_len = 4} (referencing the 4-byte static dictionary word at
   *       index 0), and {@code dist_code = 49} (which selects static dictionary transform {@code 48
   *       = OmitLast7}). Applying {@code OmitLast7} to a 4-byte word emits {@code 0} output bytes,
   *       advancing the command counter without writing to the ringbuffer or advancing {@code
   *       s->pos}.
   * </ul>
   */
  @VisibleForTesting
  static final ByteString PROBE_HEADER =
      ByteString.copyFrom(
          BaseEncoding.base16().lowerCase().decode("0c28002082c8ffffff260880102404896f"));

  private static final int ZERO_CHUNK_SIZE = 65_536;
  private static final ByteString ZERO_CHUNK_64K = ByteString.copyFrom(new byte[ZERO_CHUNK_SIZE]);

  /**
   * {@code 16,777,216} commands * 11 bits / 8 = {@code 23,068,672} zero bytes ({@code 352 * 64
   * KiB}). Each 11-bit zero command decrements {@code s->block_length[2]} by 1 while emitting 0
   * output bytes, driving {@code s->block_length[2]} from {@code 16,777,216} down to {@code 0}.
   */
  private static final int PROBE_ZERO_CHUNK_COUNT = 352;

  @VisibleForTesting
  static final int PROBE_MIDDLE_ZERO_LEN = PROBE_ZERO_CHUNK_COUNT * ZERO_CHUNK_SIZE;

  /**
   * 93-byte tail completing the CVE-2026-111497 probe:
   *
   * <ul>
   *   <li>{@code 61b13e}: Command {@code #16,777,217} ({@code copy_len = 357} with {@code extra =
   *       353}, which cleanly terminates the compressed metablock because {@code meta_block_len =
   *       353 - 357 = -4 <= 0}). Because {@code s->block_length[2]} reached {@code 0} at the end of
   *       the 352 zero chunks, a patched {@code libbrotlidec} immediately aborts here with {@code
   *       BROTLI_DECODER_ERROR_FORMAT_BLOCK_LENGTH_2}, whereas an unpatched {@code libbrotlidec}
   *       underflows {@code s->block_length[2]} to {@code -1} and continues.
   *   <li>32-byte zero-filled metadata metablock: Ensures {@code s->pos == 0} remains invariant
   *       even when Netty/TCP splits the stream across arbitrary packet boundaries before the tail.
   *   <li>Uncompressed metablock carrying {@link #DECOMPRESSED_PAYLOAD} followed by the final empty
   *       metablock {@code 0x03}, allowing unpatched decoders to finish with a valid 53-byte HTML
   *       body.
   * </ul>
   */
  @VisibleForTesting
  static final ByteString PROBE_TAIL =
      ByteString.copyFrom(
              BaseEncoding.base16()
                  .lowerCase()
                  .decode(
                      "61b13e000000000000000000000000000000000000000000000000000000000000000000"
                          + "a00108"))
          .concat(DECOMPRESSED_PAYLOAD)
          .concat(ByteString.copyFrom(new byte[] {0x03}));

  @VisibleForTesting
  static final long PROBE_TOTAL_LENGTH =
      (long) PROBE_HEADER.size() + PROBE_MIDDLE_ZERO_LEN + PROBE_TAIL.size();

  private final String paramValue;

  BrotliResponsePresets(String paramValue) {
    this.paramValue = paramValue;
  }

  @Override
  public String paramValue() {
    return paramValue;
  }

  @Override
  public void handle(
      ChannelHandlerContext ctx,
      FullHttpRequest request,
      String requestUrl,
      Consumer<String> interactionRecorder,
      Consumer<HttpResponseStatus> rpcCompletionRecorder) {
    // If the client did not advertise Brotli support (for example, when libcurl/WP_Http retries a
    // failed Brotli stream with `Accept-Encoding: none`), serve an empty plain-text 200 OK response
    // and record the optional `no_br_cbid` token instead of the primary CBID. Note that Node.js
    // `fetch()` (`undici`) only includes `br` in `Accept-Encoding` on `https://` URLs and sends
    // `User-Agent: node` with `Accept-Encoding: gzip, deflate` on plain `http://` callback URLs
    // while still decompressing `Content-Encoding: br` responses via `node:zlib`.
    List<String> acceptEncodingHeaders = request.headers().getAll(HttpHeaderNames.ACCEPT_ENCODING);
    if (!acceptsBrotli(acceptEncodingHeaders)
        && !isNodeFetchPlainHttpBrotliClient(
            request.headers().get(HttpHeaderNames.USER_AGENT), acceptEncodingHeaders)) {
      replyFixedResponse(
          ctx,
          RecordingResponsePreset.extractValidCbidQueryParam(request.uri(), NO_BR_CBID_QUERY_PARAM),
          buildEmptyPlainResponse(),
          interactionRecorder,
          rpcCompletionRecorder);
      return;
    }
    handleAcceptedBrotli(
        ctx,
        RecordingResponsePreset.extractPrimaryCbidWithoutQuery(requestUrl),
        interactionRecorder,
        rpcCompletionRecorder);
  }

  abstract void handleAcceptedBrotli(
      ChannelHandlerContext ctx,
      Optional<String> primaryCbid,
      Consumer<String> interactionRecorder,
      Consumer<HttpResponseStatus> rpcCompletionRecorder);

  /**
   * Returns {@code true} if any {@code Accept-Encoding} header value advertises {@code br} with a
   * non-zero quality weight ({@code q > 0}).
   */
  @VisibleForTesting
  static boolean acceptsBrotli(List<String> acceptEncodingHeaders) {
    for (String headerValue : acceptEncodingHeaders) {
      for (String item : COMMA_SPLITTER.split(headerValue)) {
        if (isAcceptableBrotliCoding(item)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} when the request matches Node.js {@code fetch()} ({@code undici}) over
   * plain {@code http://} ({@code User-Agent: node} or {@code undici} with {@code Accept-Encoding:
   * gzip, deflate} and without {@code br;q=0}), which decompresses {@code Content-Encoding: br}
   * responses even though {@code undici} only advertises {@code br} on {@code https://} URLs.
   */
  @VisibleForTesting
  static boolean isNodeFetchPlainHttpBrotliClient(
      String userAgent, List<String> acceptEncodingHeaders) {
    if (userAgent == null) {
      return false;
    }
    String normalizedAgent = userAgent.trim();
    if (!Ascii.equalsIgnoreCase(normalizedAgent, "node")
        && !Ascii.equalsIgnoreCase(normalizedAgent, "undici")) {
      return false;
    }
    for (String headerValue : acceptEncodingHeaders) {
      String lower = Ascii.toLowerCase(headerValue);
      if (lower.contains("gzip") && lower.contains("deflate") && !lower.contains("br;q=0")) {
        return true;
      }
    }
    return false;
  }

  @VisibleForTesting
  static FullHttpResponse buildEmptyPlainResponse() {
    FullHttpResponse response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
    setCommonSecurityHeaders(response);
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
    return response;
  }

  @VisibleForTesting
  static FullHttpResponse buildValidBrotliResponse() {
    return buildFixedBrotliResponse(VALID_BROTLI);
  }

  @VisibleForTesting
  static FullHttpResponse buildInvalidBrotliResponse() {
    return buildFixedBrotliResponse(INVALID_BROTLI);
  }

  /**
   * Streams the 23 MB zero-output CVE-2026-111497 format-validation bitstream over {@code ctx}
   * using a shared static 64 KiB zero buffer without allocating a per-request 23 MB heap buffer.
   *
   * @param ctx the Netty channel handler context.
   * @param beforeTailCallback invoked synchronously right before the final 93-byte tail is written
   *     once all 352 preceding 64 KiB chunks have been accepted into TCS's local socket send buffer
   *     ({@code SO_SNDBUF}) on an active connection. Note that Netty {@code ChannelFuture}
   *     completion reflects local kernel buffer acceptance rather than remote peer application
   *     receipt.
   * @param onCompleteCallback invoked at most once when streaming finishes or aborts.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  private static void streamCve2026111497Probe(
      ChannelHandlerContext ctx, Runnable beforeTailCallback, Runnable onCompleteCallback) {
    checkNotNull(ctx);
    checkNotNull(beforeTailCallback);
    checkNotNull(onCompleteCallback);

    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    setCommonSecurityHeaders(response);
    response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.BR);
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, PROBE_TOTAL_LENGTH);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

    ctx.write(response);
    ByteBuf headerBuf = Unpooled.wrappedBuffer(PROBE_HEADER.asReadOnlyByteBuffer());
    ChannelFuture headerFuture = ctx.writeAndFlush(new DefaultHttpContent(headerBuf));
    headerFuture.addListener(new ZeroChunkStreamer(ctx, beforeTailCallback, onCompleteCallback));
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private static void replyFixedResponse(
      ChannelHandlerContext ctx,
      Optional<String> cbid,
      FullHttpResponse response,
      Consumer<String> interactionRecorder,
      Consumer<HttpResponseStatus> rpcCompletionRecorder) {
    cbid.ifPresent(interactionRecorder);
    ctx.writeAndFlush(response);
    rpcCompletionRecorder.accept(HttpResponseStatus.OK);
  }

  private static boolean isAcceptableBrotliCoding(String item) {
    List<String> parts = SEMICOLON_SPLITTER.splitToList(item);
    if (parts.isEmpty() || !Ascii.equalsIgnoreCase(parts.get(0), "br")) {
      return false;
    }
    for (int i = 1; i < parts.size(); i++) {
      String param = parts.get(i);
      if (Ascii.toLowerCase(param).startsWith("q=")
          && !isPositiveQualityWeight(param.substring(2).trim())) {
        return false;
      }
    }
    return true;
  }

  private static boolean isPositiveQualityWeight(String rawWeight) {
    try {
      double weight = Double.parseDouble(rawWeight);
      return weight > 0.0 && weight <= 1.0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static FullHttpResponse buildFixedBrotliResponse(ByteString body) {
    ByteBuf content = Unpooled.wrappedBuffer(body.asReadOnlyByteBuffer());
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
    setCommonSecurityHeaders(response);
    response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.BR);
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.size());
    return response;
  }

  private static void setCommonSecurityHeaders(HttpResponse response) {
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, SAFE_CONTENT_TYPE);
    response.headers().set(X_CONTENT_TYPE_OPTIONS, NOSNIFF);
    response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE);
  }

  /**
   * Backpressure-aware Netty listener that writes {@link #PROBE_ZERO_CHUNK_COUNT} slices of the
   * shared read-only {@link #ZERO_CHUNK_64K} buffer, pausing whenever the channel's outbound write
   * buffer becomes non-writable and aborting if the peer disconnects or {@link
   * #STREAM_DEADLINE_SECONDS} elapses.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  private static final class ZeroChunkStreamer implements ChannelFutureListener {
    private final ChannelHandlerContext ctx;
    private final Runnable beforeTailCallback;
    private final Runnable onCompleteCallback;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final ScheduledFuture<?> deadlineFuture;
    private int remainingChunks = PROBE_ZERO_CHUNK_COUNT;

    ZeroChunkStreamer(
        ChannelHandlerContext ctx, Runnable beforeTailCallback, Runnable onCompleteCallback) {
      this.ctx = ctx;
      this.beforeTailCallback = beforeTailCallback;
      this.onCompleteCallback = onCompleteCallback;
      this.deadlineFuture =
          ctx.executor()
              .schedule(
                  () -> {
                    ctx.close();
                    completeOnce();
                  },
                  STREAM_DEADLINE_SECONDS,
                  SECONDS);
    }

    @Override
    public void operationComplete(ChannelFuture future) {
      if (completed.get() || !future.isSuccess() || !ctx.channel().isActive()) {
        ctx.close();
        completeOnce();
        return;
      }

      if (remainingChunks > 0) {
        ChannelFuture lastWrite;
        do {
          remainingChunks--;
          ByteBuf zeroBuf = Unpooled.wrappedBuffer(ZERO_CHUNK_64K.asReadOnlyByteBuffer());
          lastWrite = ctx.write(new DefaultHttpContent(zeroBuf));
        } while (remainingChunks > 0 && ctx.channel().isWritable());
        ctx.flush();
        lastWrite.addListener(this);
        return;
      }

      try {
        beforeTailCallback.run();
      } catch (RuntimeException e) {
        ctx.close();
        completeOnce();
        throw e;
      }

      ByteBuf tailBuf = Unpooled.wrappedBuffer(PROBE_TAIL.asReadOnlyByteBuffer());
      ctx.writeAndFlush(new DefaultLastHttpContent(tailBuf))
          .addListener(ChannelFutureListener.CLOSE)
          .addListener(_ -> completeOnce());
    }

    private void completeOnce() {
      if (completed.compareAndSet(false, true)) {
        deadlineFuture.cancel(false);
        onCompleteCallback.run();
      }
    }
  }
}
