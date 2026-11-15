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
package com.google.tsunami.callbackserver.server.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.GoogleLogger;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Base implementation of an HTTP server in TCS. */
public abstract class HttpServer implements TcsServer {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final AttributeKey<Boolean> CONNECTION_ACCEPTED_ATTR =
      AttributeKey.valueOf(HttpServer.class, "connectionAccepted");

  @VisibleForTesting static final int DEFAULT_READ_TIMEOUT_SECONDS = 10;
  @VisibleForTesting static final int DEFAULT_MAX_ACTIVE_CONNECTIONS = 512;

  private final EventLoopGroup managerPool;
  private final EventLoopGroup workerGroup;
  private final int port;
  private final Duration readTimeout;
  private final ActiveConnectionLimiter connectionLimiter;

  private ChannelFuture channelFuture;

  protected HttpServer(EventLoopGroup managerPool, EventLoopGroup workerGroup, int port) {
    this(
        managerPool,
        workerGroup,
        port,
        Duration.ofSeconds(DEFAULT_READ_TIMEOUT_SECONDS),
        DEFAULT_MAX_ACTIVE_CONNECTIONS);
  }

  @VisibleForTesting
  protected HttpServer(
      EventLoopGroup managerPool,
      EventLoopGroup workerGroup,
      int port,
      Duration readTimeout,
      int maxActiveConnections) {
    checkArgument(!readTimeout.isNegative() && !readTimeout.isZero(), "readTimeout must be > 0");
    checkArgument(maxActiveConnections > 0, "maxActiveConnections must be > 0");
    this.managerPool = checkNotNull(managerPool);
    this.workerGroup = checkNotNull(workerGroup);
    this.port = port;
    this.readTimeout = checkNotNull(readTimeout);
    this.connectionLimiter = new ActiveConnectionLimiter(maxActiveConnections);
  }

  /**
   * Creates an instance of the {@link HttpHandler} for service the HTTP traffic.
   *
   * @return an instance of the HTTP serving handler.
   */
  protected abstract HttpHandler createRequestHandler();

  @Override
  public void start() throws IOException {
    final ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap
        .group(managerPool, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                ChannelPipeline p = ch.pipeline();
                p.addLast(name() + "ConnectionLimiter", connectionLimiter);
                // Close any connection that reads no bytes for readTimeout (default 10s) to bound
                // the cost of slowloris / idle-socket FD exhaustion (CWE-770).
                p.addLast(
                    name() + "ReadTimeoutHandler",
                    new ReadTimeoutHandler(readTimeout.toMillis(), MILLISECONDS));
                p.addLast(name() + "HttpServerCodec", new HttpServerCodec());
                p.addLast(name() + "HttpKeepAliveHandler", new HttpServerKeepAliveHandler());
                p.addLast(name() + "HttpObjectAggregator", new HttpObjectAggregator(65535));
                p.addLast(name() + "RequestHandler", createRequestHandler());
              }
            });
    channelFuture = bootstrap.bind(port).awaitUninterruptibly();
    if (!channelFuture.isSuccess()) {
      throw new IOException(
          String.format("Error starting the server %s on port %d.", name(), port),
          channelFuture.cause());
    }
    logger.atInfo().log("HTTP server %s started on port %d.", name(), port);
  }

  @VisibleForTesting
  public int boundPort() {
    return ((InetSocketAddress) channelFuture.channel().localAddress()).getPort();
  }

  @VisibleForTesting
  public int activeConnections() {
    return connectionLimiter.activeConnections.get();
  }

  @VisibleForTesting
  public void stop() throws InterruptedException {
    try {
      if (channelFuture != null) {
        channelFuture.channel().close().sync();
      }
    } finally {
      try {
        workerGroup.shutdownGracefully().sync();
      } finally {
        managerPool.shutdownGracefully().sync();
      }
    }
  }

  @Override
  public void waitForShutdown() {
    try {
      channelFuture.channel().closeFuture().sync();
      managerPool.shutdownGracefully().await();
      workerGroup.shutdownGracefully().await();
    } catch (InterruptedException e) {
      logger.atWarning().withCause(e).log("Interrupted while shutting down %s.", name());
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Bounds the number of concurrent active TCP connections handled by this {@link HttpServer} so a
   * burst of connections on one listener cannot exhaust process file descriptors.
   */
  @Sharable
  @SuppressWarnings("FutureReturnValueIgnored")
  private final class ActiveConnectionLimiter extends ChannelInboundHandlerAdapter {
    private final int maxActiveConnections;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    ActiveConnectionLimiter(int maxActiveConnections) {
      this.maxActiveConnections = maxActiveConnections;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      int current = activeConnections.incrementAndGet();
      if (current > maxActiveConnections) {
        activeConnections.decrementAndGet();
        logger.atFine().log(
            "Rejecting HTTP connection on %s: active connections (%d) exceeded limit (%d).",
            name(), current - 1, maxActiveConnections);
        ctx.close();
        return;
      }
      ctx.channel().attr(CONNECTION_ACCEPTED_ATTR).set(Boolean.TRUE);
      super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (!Boolean.TRUE.equals(ctx.channel().attr(CONNECTION_ACCEPTED_ATTR).get())) {
        ReferenceCountUtil.release(msg);
        return;
      }
      super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      if (Boolean.TRUE.equals(ctx.channel().attr(CONNECTION_ACCEPTED_ATTR).getAndSet(null))) {
        activeConnections.decrementAndGet();
      }
      super.channelInactive(ctx);
    }
  }
}
