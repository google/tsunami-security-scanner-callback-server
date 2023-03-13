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

import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;

import com.google.common.net.InetAddresses;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.protobuf.Message;
import com.google.tsunami.callbackserver.common.Sha3CbidGenerator;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.callbackserver.proto.PollingResult;
import com.google.tsunami.callbackserver.server.common.NotFoundException;
import com.google.tsunami.callbackserver.server.common.monitoring.TcsEventsObserver;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore.InteractionType;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.util.Optional;
import javax.inject.Inject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link InteractionPollingHandler}. */
@RunWith(JUnit4.class)
public final class InteractionPollingHandlerTest {
  private static final String FAKE_SECRET = "a3d9ed89deadbeef";
  private static final String FAKE_CBID =
      "04041e8898e739ca33a250923e24f59ca41a8373f8cf6a45a1275f3b";
  private static final Optional<InetAddress> TEST_CLIENT_ADDRESS =
      Optional.of(InetAddresses.forString("1.2.3.4"));

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock TcsEventsObserver eventsObserverMock;

  @Inject private InteractionPollingHandler handler;
  @Inject private InteractionStore interactionStore;

  @Before
  public void setUp() {
    Guice.createInjector(
            InMemoryInteractionStore.getModuleForTesting(),
            Sha3CbidGenerator.getModule(),
            new FakeUtcClockModule(),
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(TcsEventsObserver.class).toInstance(eventsObserverMock);
              }
            })
        .injectMembers(this);
  }

  @Test
  public void handleRequest_whenCbidHasHttpInteraction_returnsRecordedInteraction() {
    interactionStore.add(FAKE_CBID, InteractionType.HTTP_INTERACTION);

    Message response =
        handler.handleRequest(
            new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/?secret=" + FAKE_SECRET),
            TEST_CLIENT_ADDRESS);

    assertThat(response)
        .isEqualTo(
            PollingResult.newBuilder()
                .setHasDnsInteraction(false)
                .setHasHttpInteraction(true)
                .build());
    verify(eventsObserverMock).onHttpInteractionFound();
  }

  @Test
  public void handleRequest_whenCbidHasDnsInteraction_returnsRecordedInteraction() {
    interactionStore.add(FAKE_CBID, InteractionType.DNS_INTERACTION);

    Message response =
        handler.handleRequest(
            new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/?secret=" + FAKE_SECRET),
            TEST_CLIENT_ADDRESS);

    assertThat(response)
        .isEqualTo(
            PollingResult.newBuilder()
                .setHasDnsInteraction(true)
                .setHasHttpInteraction(false)
                .build());
    verify(eventsObserverMock).onDnsInteractionFound();
  }

  @Test
  public void handleRequest_whenMissingSecret_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            handler.handleRequest(
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
                TEST_CLIENT_ADDRESS));
  }

  @Test
  public void handleRequest_whenCbidNotFoundInInteractionStore_throws() {
    assertThrows(
        NotFoundException.class,
        () ->
            handler.handleRequest(
                new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/?secret=not_found"),
                TEST_CLIENT_ADDRESS));
    verify(eventsObserverMock).onInteractionNotFound();
  }
}
