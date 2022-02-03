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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.multibindings.Multibinder;
import com.google.tsunami.callbackserver.common.config.HttpRecordingServerConfig;
import com.google.tsunami.callbackserver.server.common.HttpHandler;
import com.google.tsunami.callbackserver.server.common.HttpServer;
import com.google.tsunami.callbackserver.server.common.TcsServer;
import com.google.tsunami.callbackserver.server.recording.Annotations.HttpRecordingServerBossGroup;
import com.google.tsunami.callbackserver.server.recording.Annotations.HttpRecordingServerPort;
import com.google.tsunami.callbackserver.server.recording.Annotations.HttpRecordingServerWorkerGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/** An {@link HttpServer} implementation to handle interactions via HTTP requests. */
public final class HttpRecordingServer extends HttpServer {

  private final Provider<HttpRecordingHandler> handlerProvider;

  @Inject
  HttpRecordingServer(
      @HttpRecordingServerBossGroup EventLoopGroup bossGroup,
      @HttpRecordingServerWorkerGroup EventLoopGroup workerGroup,
      @HttpRecordingServerPort int port,
      Provider<HttpRecordingHandler> handlerProvider) {
    super(bossGroup, workerGroup, port);
    this.handlerProvider = handlerProvider;
  }

  @Override
  public String name() {
    return "HttpRecordingServer";
  }

  @Override
  protected HttpHandler createRequestHandler() {
    return handlerProvider.get();
  }

  public static HttpRecordingServerModule getModule(HttpRecordingServerConfig config) {
    return new HttpRecordingServerModule(config);
  }

  private static class HttpRecordingServerModule extends AbstractModule {
    private final HttpRecordingServerConfig config;

    HttpRecordingServerModule(HttpRecordingServerConfig config) {
      this.config = checkNotNull(config);
    }

    @Override
    protected void configure() {
      Multibinder.newSetBinder(binder(), TcsServer.class)
          .addBinding()
          .to(HttpRecordingServer.class);
    }

    @Provides
    @Singleton
    @HttpRecordingServerBossGroup
    EventLoopGroup providesHttpRecordingServerBossGroup() {
      return new NioEventLoopGroup(1, new DefaultThreadFactory("http-recording-boss-group", true));
    }

    @Provides
    @Singleton
    @HttpRecordingServerWorkerGroup
    EventLoopGroup providesHttpRecordingServerWorkerGroup() {
      return new NioEventLoopGroup(
          config.workerPoolSize().orElse(0),
          new DefaultThreadFactory("http-recording-worker-group", true));
    }

    @Provides
    @HttpRecordingServerPort
    int providesHttpRecordingServerPort() {
      return config.port();
    }
  }
}
