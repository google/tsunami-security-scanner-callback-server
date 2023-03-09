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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.util.Types;
import com.google.tsunami.callbackserver.common.config.HttpRecordingServerConfig;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.callbackserver.server.common.TcsServer;
import com.google.tsunami.callbackserver.server.common.monitoring.NoOpTcsEventsObserver;
import com.google.tsunami.callbackserver.server.recording.Annotations.HttpRecordingServerBossGroup;
import com.google.tsunami.callbackserver.server.recording.Annotations.HttpRecordingServerPort;
import com.google.tsunami.callbackserver.server.recording.Annotations.HttpRecordingServerWorkerGroup;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore;
import io.netty.channel.EventLoopGroup;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link HttpRecordingServer}. */
@RunWith(JUnit4.class)
public final class HttpRecordingServerTest {
  private static final HttpRecordingServerConfig CONFIG =
      HttpRecordingServerConfig.fromRawData(ImmutableMap.of("port", 80, "worker_pool_size", 10));

  @Test
  public void module_always_providesBossGroup() {
    Injector injector = getInjector();

    EventLoopGroup bossGroup1 =
        injector.getInstance(Key.get(EventLoopGroup.class, HttpRecordingServerBossGroup.class));
    EventLoopGroup bossGroup2 =
        injector.getInstance(Key.get(EventLoopGroup.class, HttpRecordingServerBossGroup.class));

    assertThat(bossGroup1).isNotNull();
    assertThat(bossGroup1).isSameInstanceAs(bossGroup2);
  }

  @Test
  public void module_always_providesWorkerGroup() {
    Injector injector = getInjector();

    EventLoopGroup workerGroup1 =
        injector.getInstance(Key.get(EventLoopGroup.class, HttpRecordingServerWorkerGroup.class));
    EventLoopGroup workerGroup2 =
        injector.getInstance(Key.get(EventLoopGroup.class, HttpRecordingServerWorkerGroup.class));

    assertThat(workerGroup1).isNotNull();
    assertThat(workerGroup1).isSameInstanceAs(workerGroup2);
  }

  @Test
  public void module_always_providesServerPort() {
    assertThat(getInjector().getInstance(Key.get(Integer.class, HttpRecordingServerPort.class)))
        .isEqualTo(80);
  }

  @Test
  public void module_always_bindsToTcsServerSet() {
    @SuppressWarnings("unchecked")
    Set<TcsServer> tcsServers =
        getInjector().getInstance((Key<Set<TcsServer>>) Key.get(Types.setOf(TcsServer.class)));
    assertThat(tcsServers).hasSize(1);
    assertThat(tcsServers.iterator().next().name()).isEqualTo("HttpRecordingServer");
  }

  private static Injector getInjector() {
    return Guice.createInjector(
        HttpRecordingServer.getModule(CONFIG),
        InMemoryInteractionStore.getModuleForTesting(),
        new FakeUtcClockModule(),
        NoOpTcsEventsObserver.getModule());
  }
}
