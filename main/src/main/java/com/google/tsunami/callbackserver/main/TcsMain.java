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
package com.google.tsunami.callbackserver.main;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.tsunami.callbackserver.common.Sha3CbidGenerator;
import com.google.tsunami.callbackserver.common.config.TcsConfig;
import com.google.tsunami.callbackserver.common.time.SystemUtcClockModule;
import com.google.tsunami.callbackserver.server.common.TcsServer;
import com.google.tsunami.callbackserver.server.polling.InteractionPollingServer;
import com.google.tsunami.callbackserver.server.recording.DnsRecordingServer;
import com.google.tsunami.callbackserver.server.recording.HttpRecordingServer;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Set;
import javax.inject.Inject;

/** Main entrypoint for Tsunami callback server. */
public final class TcsMain {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final ImmutableSet<TcsServer> enabledServers;

  @Inject
  TcsMain(Set<TcsServer> enabledServers) {
    this.enabledServers = ImmutableSet.copyOf(enabledServers);
  }

  public void run() throws IOException {
    for (TcsServer server : enabledServers) {
      server.start();
    }

    for (TcsServer server : enabledServers) {
      server.waitForShutdown();
    }
  }

  private static final class TcsMainModule extends AbstractModule {
    private final TcsConfig tcsConfig;

    TcsMainModule(TcsConfig tcsConfig) {
      this.tcsConfig = checkNotNull(tcsConfig);
    }

    @Override
    protected void configure() {
      // Common bindings.
      bind(TcsConfig.class).toInstance(tcsConfig);
      bind(SecureRandom.class).toInstance(new SecureRandom());
      install(new SystemUtcClockModule());
      install(Sha3CbidGenerator.getModule());

      // Storage backend bindings.
      tcsConfig
          .storageConfig()
          .inMemoryStorageConfig()
          .ifPresentOrElse(
              config -> install(InMemoryInteractionStore.getModule(config)),
              () -> {
                throw new AssertionError("Missing required in-memory storage config.");
              });

      // Recording server bindings.
      tcsConfig
          .recordingServerConfig()
          .dnsRecordingServerConfig()
          .ifPresent(
              config ->
                  install(
                      DnsRecordingServer.getModule(
                          config,
                          tcsConfig.commonConfig().externalIp(),
                          tcsConfig.commonConfig().domain())));
      tcsConfig
          .recordingServerConfig()
          .httpRecordingServerConfig()
          .ifPresent(config -> install(HttpRecordingServer.getModule(config)));

      // Polling server bindings.
      install(InteractionPollingServer.getModule(tcsConfig.pollingServerConfig()));
    }
  }

  public static void main(String[] args) {
    try {
      logger.atInfo().log("Starting the server with these flags: %s", args);
      TcsCliOptions cliOptions = TcsCliOptions.parseArgs(args);
      TcsConfig tcsConfig = TcsConfig.fromYamlFile(cliOptions.customConfig);

      Injector injector = Guice.createInjector(new TcsMainModule(tcsConfig));
      injector.getInstance(TcsMain.class).run();
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log("TCS server error.");
      System.exit(1);
    }
  }
}
