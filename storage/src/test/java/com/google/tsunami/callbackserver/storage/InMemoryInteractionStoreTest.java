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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.callbackserver.common.config.InMemoryStorageConfig;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClock;
import com.google.tsunami.callbackserver.proto.Interaction;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore.TtlCache;
import com.google.tsunami.callbackserver.storage.InteractionStore.InteractionType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link InteractionStore}. */
@RunWith(JUnit4.class)
public final class InMemoryInteractionStoreTest {
  private static final String FAKE_CBID =
      "b0f3dc043a9c5c05f67651a8c9108b4c2b98e7246b2eea14cb204295";

  private final FakeUtcClock fakeUtcClock =
      FakeUtcClock.create().setNow(Instant.parse("2020-01-01T00:00:00.00Z"));

  private InMemoryInteractionStore interactionStore;
  private ConcurrentHashMap<String, List<Interaction>> data;
  private TtlCache storageBackend;

  @Before
  public void setup() throws Exception {
    data = new ConcurrentHashMap<>();
    storageBackend = new TtlCache(data, fakeUtcClock, InMemoryStorageConfig.createForTesting());
    interactionStore = new InMemoryInteractionStore(storageBackend);
  }

  @Test
  public void add_httpInteraction_createsNewHttpRecord() {
    interactionStore.add(FAKE_CBID, InteractionType.HTTP_INTERACTION);

    assertThat(data)
        .containsExactly(
            FAKE_CBID,
            ImmutableList.of(
                Interaction.newBuilder()
                    .setIsHttpInteraction(true)
                    .setRecordTime(Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
                    .build()));
  }

  @Test
  public void add_dnsInteraction_createsNewDnsRecord() {
    interactionStore.add(FAKE_CBID, InteractionType.DNS_INTERACTION);

    assertThat(data)
        .containsExactly(
            FAKE_CBID,
            ImmutableList.of(
                Interaction.newBuilder()
                    .setIsDnsInteraction(true)
                    .setRecordTime(Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
                    .build()));
  }

  @Test
  public void add_existingLog_appendsInteractionList() {
    Instant timeForFirstLog = fakeUtcClock.instant();
    interactionStore.add(FAKE_CBID, InteractionType.HTTP_INTERACTION);

    fakeUtcClock.advance(Duration.ofSeconds(1));
    Instant timeForSecondLog = fakeUtcClock.instant();
    interactionStore.add(FAKE_CBID, InteractionType.DNS_INTERACTION);

    assertThat(data)
        .containsExactly(
            FAKE_CBID,
            ImmutableList.of(
                Interaction.newBuilder()
                    .setIsHttpInteraction(true)
                    .setRecordTime(Timestamps.fromMillis(timeForFirstLog.toEpochMilli()))
                    .build(),
                Interaction.newBuilder()
                    .setIsDnsInteraction(true)
                    .setRecordTime(Timestamps.fromMillis(timeForSecondLog.toEpochMilli()))
                    .build()));
  }

  @Test
  public void add_existingLogSameInteractionType_appendsInteractionList() {
    Instant timeForFirstLog = fakeUtcClock.instant();
    interactionStore.add(FAKE_CBID, InteractionType.DNS_INTERACTION);

    fakeUtcClock.advance(Duration.ofSeconds(1));
    Instant timeForSecondLog = fakeUtcClock.instant();
    interactionStore.add(FAKE_CBID, InteractionType.DNS_INTERACTION);

    assertThat(data)
        .containsExactly(
            FAKE_CBID,
            ImmutableList.of(
                Interaction.newBuilder()
                    .setIsDnsInteraction(true)
                    .setRecordTime(Timestamps.fromMillis(timeForFirstLog.toEpochMilli()))
                    .build(),
                Interaction.newBuilder()
                    .setIsDnsInteraction(true)
                    .setRecordTime(Timestamps.fromMillis(timeForSecondLog.toEpochMilli()))
                    .build()));
  }

  @Test
  public void get_matchingCbid_returnsAllInteractions() {
    interactionStore.add(FAKE_CBID, InteractionType.HTTP_INTERACTION);

    assertThat(interactionStore.get(FAKE_CBID))
        .containsExactly(
            Interaction.newBuilder()
                .setIsHttpInteraction(true)
                .setRecordTime(Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
                .build());
  }

  @Test
  public void get_unknownCbid_returnsEmpty() {
    interactionStore.add(FAKE_CBID, InteractionType.HTTP_INTERACTION);

    assertThat(interactionStore.get("RANDOM_CBID")).isEmpty();
  }

  @Test
  public void delete_existingCbid_deletesAllInteractions() {
    interactionStore.add(FAKE_CBID, InteractionType.HTTP_INTERACTION);

    interactionStore.delete(FAKE_CBID);

    assertThat(data).isEmpty();
  }

  @Test
  public void delete_unknownCbid_doesNothing() {
    interactionStore.add(FAKE_CBID, InteractionType.HTTP_INTERACTION);

    interactionStore.delete("RANDOM_CBID");

    assertThat(data)
        .containsExactly(
            FAKE_CBID,
            ImmutableList.of(
                Interaction.newBuilder()
                    .setIsHttpInteraction(true)
                    .setRecordTime(Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
                    .build()));
  }

  @Test
  public void ttlCacheCleanUp_whenTtlSet_removedExpiredInteractionRecords() {
    // TTL = 10s
    storageBackend =
        new TtlCache(
            data,
            fakeUtcClock,
            InMemoryStorageConfig.fromRawData(
                ImmutableMap.of("interaction_ttl_secs", 10, "cleanup_interval_secs", 10)));
    interactionStore = new InMemoryInteractionStore(storageBackend);

    interactionStore.add("expired_cbid_1", InteractionType.DNS_INTERACTION); // T - 13s

    fakeUtcClock.advance(Duration.ofSeconds(2));
    interactionStore.add("expired_cbid_2", InteractionType.DNS_INTERACTION); // T - 11s

    fakeUtcClock.advance(Duration.ofSeconds(5));
    interactionStore.add("unexpired_cbid_1", InteractionType.DNS_INTERACTION); // T - 6s

    fakeUtcClock.advance(Duration.ofSeconds(6));
    interactionStore.add("unexpired_cbid_2", InteractionType.DNS_INTERACTION); // T

    ImmutableSet<String> cbidBeforeCleanup = ImmutableSet.copyOf(data.keySet());
    storageBackend.cleanup();
    ImmutableSet<String> cbidAfterCleanup = ImmutableSet.copyOf(data.keySet());

    assertThat(cbidBeforeCleanup)
        .containsExactly(
            "expired_cbid_1", "expired_cbid_2", "unexpired_cbid_1", "unexpired_cbid_2");
    assertThat(cbidAfterCleanup).containsExactly("unexpired_cbid_1", "unexpired_cbid_2");
  }

  @Test
  public void ttlCacheCleanUp_whenEarliestInteractionExpired_removedTheEntireCbidRecord() {
    // TTL = 10s
    storageBackend =
        new TtlCache(
            data,
            fakeUtcClock,
            InMemoryStorageConfig.fromRawData(
                ImmutableMap.of("interaction_ttl_secs", 10, "cleanup_interval_secs", 10)));
    interactionStore = new InMemoryInteractionStore(storageBackend);

    interactionStore.add("expired_cbid", InteractionType.DNS_INTERACTION); // T - 13s

    fakeUtcClock.advance(Duration.ofSeconds(2));
    interactionStore.add("expired_cbid", InteractionType.DNS_INTERACTION); // T - 11s

    fakeUtcClock.advance(Duration.ofSeconds(5));
    interactionStore.add("expired_cbid", InteractionType.DNS_INTERACTION); // T - 6s

    fakeUtcClock.advance(Duration.ofSeconds(6));
    interactionStore.add("expired_cbid", InteractionType.DNS_INTERACTION); // T

    ImmutableSet<String> cbidBeforeCleanup = ImmutableSet.copyOf(data.keySet());
    storageBackend.cleanup();
    ImmutableSet<String> cbidAfterCleanup = ImmutableSet.copyOf(data.keySet());

    assertThat(cbidBeforeCleanup).containsExactly("expired_cbid");
    assertThat(cbidAfterCleanup).isEmpty();
  }
}
