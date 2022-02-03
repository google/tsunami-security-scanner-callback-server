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
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import java.io.IOException;

/** Base implementation of an DNS server in TCS. */
public abstract class DnsServer implements TcsServer {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final EventLoopGroup workerGroup;
  private final int port;

  private ChannelFuture channelFuture;

  protected DnsServer(EventLoopGroup workerGroup, int port) {
    this.workerGroup = checkNotNull(workerGroup);
    this.port = port;
  }

  /**
   * Creates an instance of the {@link DnsHandler} for serving the DNS traffic.
   *
   * @return an instance of the DNS serving handler.
   */
  protected abstract DnsHandler createRequestHandler();

  @Override
  public void start() throws IOException {
    final Bootstrap bootstrap = new Bootstrap();
    bootstrap
        .group(workerGroup)
        .channel(NioDatagramChannel.class)
        .handler(
            new ChannelInitializer<NioDatagramChannel>() {
              @Override
              protected void initChannel(NioDatagramChannel ch) {
                ChannelPipeline p = ch.pipeline();
                p.addLast(name() + "DnsQueryDecoder", new DatagramDnsQueryDecoder());
                p.addLast(name() + "DnsResponseEncoder", new DatagramDnsResponseEncoder());
                p.addLast(name() + "RequestHandler", createRequestHandler());
              }
            });
    channelFuture = bootstrap.bind(port).awaitUninterruptibly();
    if (!channelFuture.isSuccess()) {
      throw new IOException(
          String.format("Error starting the server %s on port %d.", name(), port),
          channelFuture.cause());
    }
    logger.atInfo().log("DNS server %s started on port %d.", name(), port);
  }

  @Override
  public void waitForShutdown() {
    try {
      channelFuture.channel().closeFuture().sync();
      workerGroup.shutdownGracefully().await();
    } catch (InterruptedException e) {
      logger.atWarning().withCause(e).log("Interrupted while shutting down %s.", name());
      Thread.currentThread().interrupt();
    }
  }
}
