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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.multibindings.Multibinder;
import com.google.tsunami.callbackserver.common.config.PollingServerConfig;
import com.google.tsunami.callbackserver.server.common.HttpHandler;
import com.google.tsunami.callbackserver.server.common.HttpServer;
import com.google.tsunami.callbackserver.server.common.TcsServer;
import com.google.tsunami.callbackserver.server.polling.Annotations.InteractionPollingServerBossGroup;
import com.google.tsunami.callbackserver.server.polling.Annotations.InteractionPollingServerPort;
import com.google.tsunami.callbackserver.server.polling.Annotations.InteractionPollingServerWorkerGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/** An {@link HttpServer} implementation to serve interaction polling requests. */
public final class InteractionPollingServer extends HttpServer {

  private final Provider<InteractionPollingHandler> handlerProvider;

  @Inject
  InteractionPollingServer(
      @InteractionPollingServerBossGroup EventLoopGroup bossGroup,
      @InteractionPollingServerWorkerGroup EventLoopGroup workerGroup,
      @InteractionPollingServerPort int port,
      Provider<InteractionPollingHandler> handlerProvider) {
    super(bossGroup, workerGroup, port);
    this.handlerProvider = handlerProvider;
  }

  @Override
  public String name() {
    return "InteractionPollingServer";
  }

  @Override
  protected HttpHandler createRequestHandler() {
    return handlerProvider.get();
  }

  public static InteractionPollingServerModule getModule(PollingServerConfig config) {
    return new InteractionPollingServerModule(config);
  }

  private static class InteractionPollingServerModule extends AbstractModule {
    private final PollingServerConfig config;

    InteractionPollingServerModule(PollingServerConfig config) {
      this.config = checkNotNull(config);
    }

    @Override
    protected void configure() {
      Multibinder.newSetBinder(binder(), TcsServer.class)
          .addBinding()
          .to(InteractionPollingServer.class);
    }

    @Provides
    @Singleton
    @InteractionPollingServerBossGroup
    EventLoopGroup providesInteractionPollingServerBossGroup() {
      return new NioEventLoopGroup(1, new DefaultThreadFactory("http-recording-boss-group", true));
    }

    @Provides
    @Singleton
    @InteractionPollingServerWorkerGroup
    EventLoopGroup providesInteractionPollingServerWorkerGroup() {
      return new NioEventLoopGroup(
          config.workerPoolSize().orElse(0),
          new DefaultThreadFactory("http-recording-worker-group", true));
    }

    @Provides
    @InteractionPollingServerPort
    int providesHttpRecordingServerPort() {
      return config.port();
    }
  }
}
