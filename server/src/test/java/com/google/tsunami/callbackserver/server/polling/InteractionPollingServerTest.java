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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.util.Types;
import com.google.tsunami.callbackserver.common.Sha3CbidGenerator;
import com.google.tsunami.callbackserver.common.config.PollingServerConfig;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.callbackserver.server.common.TcsServer;
import com.google.tsunami.callbackserver.server.common.monitoring.NoOpTcsEventsObserver;
import com.google.tsunami.callbackserver.server.polling.Annotations.InteractionPollingServerBossGroup;
import com.google.tsunami.callbackserver.server.polling.Annotations.InteractionPollingServerPort;
import com.google.tsunami.callbackserver.server.polling.Annotations.InteractionPollingServerWorkerGroup;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore;
import io.netty.channel.EventLoopGroup;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InteractionPollingServer}. */
@RunWith(JUnit4.class)
public final class InteractionPollingServerTest {
  private static final PollingServerConfig CONFIG =
      PollingServerConfig.fromRawData(ImmutableMap.of("port", 80, "worker_pool_size", 10));

  @Test
  public void module_always_providesBossGroup() {
    Injector injector = getInjector();

    EventLoopGroup bossGroup1 =
        injector.getInstance(
            Key.get(EventLoopGroup.class, InteractionPollingServerBossGroup.class));
    EventLoopGroup bossGroup2 =
        injector.getInstance(
            Key.get(EventLoopGroup.class, InteractionPollingServerBossGroup.class));

    assertThat(bossGroup1).isNotNull();
    assertThat(bossGroup1).isSameInstanceAs(bossGroup2);
  }

  @Test
  public void module_always_providesWorkerGroup() {
    Injector injector = getInjector();

    EventLoopGroup workerGroup1 =
        injector.getInstance(
            Key.get(EventLoopGroup.class, InteractionPollingServerWorkerGroup.class));
    EventLoopGroup workerGroup2 =
        injector.getInstance(
            Key.get(EventLoopGroup.class, InteractionPollingServerWorkerGroup.class));

    assertThat(workerGroup1).isNotNull();
    assertThat(workerGroup1).isSameInstanceAs(workerGroup2);
  }

  @Test
  public void module_always_providesServerPort() {
    assertThat(
            getInjector().getInstance(Key.get(Integer.class, InteractionPollingServerPort.class)))
        .isEqualTo(80);
  }

  @Test
  public void module_always_bindsToTcsServerSet() {
    @SuppressWarnings("unchecked")
    Set<TcsServer> tcsServers =
        getInjector().getInstance((Key<Set<TcsServer>>) Key.get(Types.setOf(TcsServer.class)));
    assertThat(tcsServers).hasSize(1);
    assertThat(tcsServers.iterator().next().name()).isEqualTo("InteractionPollingServer");
  }

  private static Injector getInjector() {
    return Guice.createInjector(
        InteractionPollingServer.getModule(CONFIG),
        InMemoryInteractionStore.getModuleForTesting(),
        Sha3CbidGenerator.getModule(),
        new FakeUtcClockModule(),
        NoOpTcsEventsObserver.getModule());
  }
}
