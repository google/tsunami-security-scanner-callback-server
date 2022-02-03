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
import com.google.tsunami.callbackserver.common.config.DnsRecordingServerConfig;
import com.google.tsunami.callbackserver.server.common.DnsHandler;
import com.google.tsunami.callbackserver.server.common.DnsServer;
import com.google.tsunami.callbackserver.server.common.TcsServer;
import com.google.tsunami.callbackserver.server.recording.Annotations.AuthoritativeDnsDomain;
import com.google.tsunami.callbackserver.server.recording.Annotations.DnsRecordingServerPort;
import com.google.tsunami.callbackserver.server.recording.Annotations.DnsRecordingServerWorkerGroup;
import com.google.tsunami.callbackserver.server.recording.Annotations.IpForDnsAnswer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/** An {@link DnsServer} implementation to handle interactions via DNS requests. */
public final class DnsRecordingServer extends DnsServer {

  private final Provider<DnsRecordingHandler> handlerProvider;

  @Inject
  DnsRecordingServer(
      @DnsRecordingServerWorkerGroup EventLoopGroup workerGroup,
      @DnsRecordingServerPort int port,
      Provider<DnsRecordingHandler> handlerProvider) {
    super(workerGroup, port);
    this.handlerProvider = handlerProvider;
  }

  @Override
  public String name() {
    return "DnsRecordingServer";
  }

  @Override
  protected DnsHandler createRequestHandler() {
    return handlerProvider.get();
  }

  public static DnsRecordingServerModule getModule(
      DnsRecordingServerConfig config, String serverExternalIp, String authoritativeDnsDomain) {
    return new DnsRecordingServerModule(config, serverExternalIp, authoritativeDnsDomain);
  }

  private static class DnsRecordingServerModule extends AbstractModule {
    private final DnsRecordingServerConfig config;
    private final String serverExternalIp;
    private final String authoritativeDnsDomain;

    DnsRecordingServerModule(
        DnsRecordingServerConfig config, String serverExternalIp, String authoritativeDnsDomain) {
      this.config = checkNotNull(config);
      this.serverExternalIp = checkNotNull(serverExternalIp);
      this.authoritativeDnsDomain = checkNotNull(authoritativeDnsDomain);
    }

    @Override
    protected void configure() {
      Multibinder.newSetBinder(binder(), TcsServer.class).addBinding().to(DnsRecordingServer.class);
    }

    @Provides
    @Singleton
    @DnsRecordingServerWorkerGroup
    EventLoopGroup providesDnsRecordingServerWorkerGroup() {
      return new NioEventLoopGroup(
          config.workerPoolSize().orElse(0),
          new DefaultThreadFactory("dns-recording-worker-group", true));
    }

    @Provides
    @DnsRecordingServerPort
    int providesDnsRecordingServerPort() {
      return config.port();
    }

    @Provides
    @IpForDnsAnswer
    String providesIpForDnsAnswer() {
      return serverExternalIp;
    }

    @Provides
    @AuthoritativeDnsDomain
    String providesAuthoritativeDnsDomain() {
      return authoritativeDnsDomain;
    }
  }
}
