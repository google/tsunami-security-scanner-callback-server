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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.net.InetAddresses;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.util.Types;
import com.google.tsunami.callbackserver.common.Sha3CbidGenerator;
import com.google.tsunami.callbackserver.common.config.HttpRecordingServerConfig;
import com.google.tsunami.callbackserver.common.config.PollingServerConfig;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.callbackserver.server.common.HttpServer;
import com.google.tsunami.callbackserver.server.common.TcsServer;
import com.google.tsunami.callbackserver.server.common.monitoring.NoOpTcsEventsObserver;
import com.google.tsunami.callbackserver.server.polling.InteractionPollingServer;
import com.google.tsunami.callbackserver.server.recording.Annotations.HttpRecordingServerBossGroup;
import com.google.tsunami.callbackserver.server.recording.Annotations.HttpRecordingServerPort;
import com.google.tsunami.callbackserver.server.recording.Annotations.HttpRecordingServerWorkerGroup;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link HttpRecordingServer} and {@link HttpServer}. */
@RunWith(JUnit4.class)
public final class HttpRecordingServerTest {
  private static final HttpRecordingServerConfig CONFIG =
      HttpRecordingServerConfig.fromRawData(ImmutableMap.of("port", 80, "worker_pool_size", 10));
  private static final InetAddress LOOPBACK_ADDRESS = InetAddresses.forString("127.0.0.1");
  private static final String FAKE_SECRET = "a3d9ed89deadbeef";
  private static final String FAKE_CBID =
      "04041e8898e739ca33a250923e24f59ca41a8373f8cf6a45a1275f3b";

  @Test
  public void module_always_providesBossGroup() {
    Injector injector = getInjector();

    EventLoopGroup managerPool1 =
        injector.getInstance(Key.get(EventLoopGroup.class, HttpRecordingServerBossGroup.class));
    EventLoopGroup managerPool2 =
        injector.getInstance(Key.get(EventLoopGroup.class, HttpRecordingServerBossGroup.class));

    assertThat(managerPool1).isNotNull();
    assertThat(managerPool1).isSameInstanceAs(managerPool2);
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

  @Test
  @SuppressWarnings("deprecation") // Upstream build.gradle targets Netty 4.1 NioEventLoopGroup.
  public void start_whenSlowlorisPartialRequest_closesIdleConnectionAfterReadTimeout()
      throws Exception {
    Injector injector = getInjector();
    HttpRecordingServer server =
        new HttpRecordingServer(
            new NioEventLoopGroup(1),
            new NioEventLoopGroup(1),
            0,
            injector.getProvider(HttpRecordingHandler.class),
            Duration.ofMillis(200),
            8);
    server.start();
    try (Socket slowlorisSocket = new Socket(LOOPBACK_ADDRESS, server.boundPort())) {
      slowlorisSocket.setSoTimeout(3000);
      OutputStream out = slowlorisSocket.getOutputStream();
      // Send an incomplete HTTP request line/headers without the terminating blank line.
      out.write("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(UTF_8));
      out.flush();

      // ReadTimeoutHandler should automatically close the idle channel after ~200ms.
      assertThat(slowlorisSocket.getInputStream().read()).isEqualTo(-1);
    } finally {
      server.stop();
    }
  }

  @Test
  @SuppressWarnings("deprecation") // Upstream build.gradle targets Netty 4.1 NioEventLoopGroup.
  public void start_whenRecordingConnectionsCapped_rejectsExcessAndPollingServerStillResponds()
      throws Exception {
    Injector injector =
        Guice.createInjector(
            InteractionPollingServer.getModule(
                PollingServerConfig.fromRawData(ImmutableMap.of("port", 0, "worker_pool_size", 1))),
            InMemoryInteractionStore.getModuleForTesting(),
            Sha3CbidGenerator.getModule(),
            new FakeUtcClockModule(),
            new NoOpTcsEventsObserver.Module());

    HttpRecordingServer recordingServer =
        new HttpRecordingServer(
            new NioEventLoopGroup(1),
            new NioEventLoopGroup(1),
            0,
            injector.getProvider(HttpRecordingHandler.class),
            Duration.ofSeconds(30),
            2);
    InteractionPollingServer pollingServer = injector.getInstance(InteractionPollingServer.class);

    recordingServer.start();
    pollingServer.start();
    try {
      // 1. Record a valid HTTP interaction before the Slowloris burst and wait for its channel to
      // close on the worker event loop so activeConnections returns to 0.
      String recordResponse = sendHttpGet(recordingServer.boundPort(), "/" + FAKE_CBID);
      assertThat(recordResponse).contains("200 OK");
      awaitActiveConnections(recordingServer, 0);

      // 2. Open 2 Slowloris connections sequentially to saturate recordingServer's
      // maxActiveConnections (2).
      try (Socket slow1 = openSlowlorisSocket(recordingServer.boundPort())) {
        awaitActiveConnections(recordingServer, 1);
        try (Socket slow2 = openSlowlorisSocket(recordingServer.boundPort())) {
          awaitActiveConnections(recordingServer, 2);

          // 3. A 3rd connection to recordingServer exceeds maxActiveConnections and is closed
          // immediately.
          try (Socket excess = new Socket(LOOPBACK_ADDRESS, recordingServer.boundPort())) {
            excess.setSoTimeout(2000);
            assertConnectionClosedByServer(excess);
          }
          assertThat(recordingServer.activeConnections()).isEqualTo(2);

          // 4. While recordingServer is saturated, pollingServer continues to accept and serve
          // scanner polling requests without FD exhaustion.
          String pollResponse = sendHttpGet(pollingServer.boundPort(), "/?secret=" + FAKE_SECRET);
          assertThat(pollResponse).contains("200 OK");
          assertThat(pollResponse).contains("\"hasHttpInteraction\": true");
        }
      }

      // 5. After Slowloris sockets close, recordingServer recovers and accepts new requests.
      awaitActiveConnections(recordingServer, 0);
      assertThat(sendHttpGet(recordingServer.boundPort(), "/" + FAKE_CBID)).contains("200 OK");
    } finally {
      try {
        recordingServer.stop();
      } finally {
        pollingServer.stop();
      }
    }
  }

  private static Socket openSlowlorisSocket(int port) throws IOException {
    Socket socket = new Socket(LOOPBACK_ADDRESS, port);
    socket.setSoTimeout(3000);
    OutputStream out = socket.getOutputStream();
    out.write("GET / HTTP/1.1\r\nHost: slowloris\r\n".getBytes(UTF_8));
    out.flush();
    return socket;
  }

  private static void assertConnectionClosedByServer(Socket socket) throws IOException {
    try {
      OutputStream out = socket.getOutputStream();
      out.write("GET / HTTP/1.1\r\nHost: excess\r\n".getBytes(UTF_8));
      out.flush();
      InputStream in = socket.getInputStream();
      assertThat(in.read()).isEqualTo(-1);
    } catch (SocketException expected) {
      // Connection reset by peer when writing/reading on a channel closed in channelActive.
    }
  }

  private static void awaitActiveConnections(HttpServer server, int expected)
      throws InterruptedException {
    for (int i = 0; i < 250; i++) {
      if (server.activeConnections() == expected) {
        return;
      }
      Thread.sleep(20);
    }
    assertThat(server.activeConnections()).isEqualTo(expected);
  }

  private static String sendHttpGet(int port, String path) throws IOException {
    try (Socket socket = new Socket(LOOPBACK_ADDRESS, port)) {
      socket.setSoTimeout(3000);
      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n")
              .getBytes(UTF_8));
      out.flush();
      return new String(ByteStreams.toByteArray(socket.getInputStream()), UTF_8);
    }
  }

  private static Injector getInjector() {
    return Guice.createInjector(
        HttpRecordingServer.getModule(CONFIG),
        InMemoryInteractionStore.getModuleForTesting(),
        new FakeUtcClockModule(),
        new NoOpTcsEventsObserver.Module());
  }
}
