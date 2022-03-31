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
package com.google.tsunami.callbackserver.storage;

import static com.google.common.truth.Truth.assertThat;
import static com.google.tsunami.callbackserver.storage.RedisInteractionStore.INTERACTION_STORE_CMD;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClock;
import com.google.tsunami.callbackserver.proto.Interaction;
import com.google.tsunami.callbackserver.storage.InteractionStore.InteractionType;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** Tests for {@link RedisInteractionStore}. */
@RunWith(JUnit4.class)
public final class RedisInteractionStoreTest {
  private static final String FAKE_CBID =
      "b0f3dc043a9c5c05f67651a8c9108b4c2b98e7246b2eea14cb204295";
  private static final Duration FAKE_TTL = Duration.ofSeconds(Integer.MAX_VALUE);
  private final FakeUtcClock fakeUtcClock =
      FakeUtcClock.create().setNow(Instant.parse("2020-01-01T00:00:00.00Z"));

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private JedisPool mockJedisPool;
  @Mock private Jedis mockJedis;
  private RedisInteractionStore interactionStore;

  @Before
  public void setUp() throws IOException {
    doReturn(mockJedis).when(mockJedisPool).getResource();

    interactionStore =
        new RedisInteractionStore(fakeUtcClock, FAKE_TTL, mockJedisPool, mockJedisPool);
  }

  @Test
  public void add_httpInteraction_callsEval() {
    interactionStore.add(FAKE_CBID, InteractionType.HTTP_INTERACTION);

    verify(mockJedis, times(1))
        .eval(
            INTERACTION_STORE_CMD.getBytes(UTF_8),
            1,
            FAKE_CBID.getBytes(UTF_8),
            Interaction.newBuilder()
                .setIsHttpInteraction(true)
                .setRecordTime(Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
                .build()
                .toByteArray(),
            String.valueOf(FAKE_TTL.toSeconds()).getBytes(UTF_8));
  }

  @Test
  public void add_dnsInteraction_callsEval() {
    interactionStore.add(FAKE_CBID, InteractionType.DNS_INTERACTION);

    verify(mockJedis, times(1))
        .eval(
            INTERACTION_STORE_CMD.getBytes(UTF_8),
            1,
            FAKE_CBID.getBytes(UTF_8),
            Interaction.newBuilder()
                .setIsDnsInteraction(true)
                .setRecordTime(Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
                .build()
                .toByteArray(),
            String.valueOf(FAKE_TTL.toSeconds()).getBytes(UTF_8));
  }

  @Test
  public void get_always_callsLrange() {
    interactionStore.get(FAKE_CBID);

    verify(mockJedis, times(1)).lrange(FAKE_CBID.getBytes(UTF_8), 0, -1);
  }

  @Test
  public void get_withValidData_returnsInteractionsList() {
    Interaction httpInteraction =
        Interaction.newBuilder()
            .setIsHttpInteraction(true)
            .setRecordTime(Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
            .build();
    Interaction dnsInteraction =
        Interaction.newBuilder()
            .setIsDnsInteraction(true)
            .setRecordTime(Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
            .build();
    doReturn(ImmutableList.of(httpInteraction.toByteArray(), dnsInteraction.toByteArray()))
        .when(mockJedis)
        .lrange(FAKE_CBID.getBytes(UTF_8), 0, -1);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(httpInteraction, dnsInteraction);
  }

  @Test
  public void get_withInvalidData_throws() {
    doReturn(ImmutableList.of(new byte[] {0x00, 0x00, 0x00}))
        .when(mockJedis)
        .lrange(FAKE_CBID.getBytes(UTF_8), 0, -1);

    assertThrows(AssertionError.class, () -> interactionStore.get(FAKE_CBID));
  }

  @Test
  public void delete_always_callsDel() {
    interactionStore.delete(FAKE_CBID);

    verify(mockJedis, times(1)).del(FAKE_CBID.getBytes(UTF_8));
  }
}
