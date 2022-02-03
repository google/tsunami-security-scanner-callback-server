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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.flogger.GoogleLogger;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import java.io.IOException;

/** Base implementation of an HTTP server in TCS. */
public abstract class HttpServer implements TcsServer {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final EventLoopGroup bossGroup;
  private final EventLoopGroup workerGroup;
  private final int port;

  private ChannelFuture channelFuture;

  protected HttpServer(EventLoopGroup bossGroup, EventLoopGroup workerGroup, int port) {
    this.bossGroup = checkNotNull(bossGroup);
    this.workerGroup = checkNotNull(workerGroup);
    this.port = port;
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
        .group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                ChannelPipeline p = ch.pipeline();
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

  @Override
  public void waitForShutdown() {
    try {
      channelFuture.channel().closeFuture().sync();
      bossGroup.shutdownGracefully().await();
      workerGroup.shutdownGracefully().await();
    } catch (InterruptedException e) {
      logger.atWarning().withCause(e).log("Interrupted while shutting down %s.", name());
      Thread.currentThread().interrupt();
    }
  }
}
