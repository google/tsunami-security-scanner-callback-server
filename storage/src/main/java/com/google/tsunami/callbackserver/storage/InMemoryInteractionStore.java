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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.callbackserver.common.config.InMemoryStorageConfig;
import com.google.tsunami.callbackserver.common.time.UtcClock;
import com.google.tsunami.callbackserver.proto.Interaction;
import com.google.tsunami.callbackserver.storage.Annotations.InMemoryStorageBackend;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * An in-memory implementation of the {@link InteractionStore}, which under the hood uses a
 * thread-safe hashmap as the storage backend.
 */
public final class InMemoryInteractionStore implements InteractionStore {
  private final TtlCache storage;

  @Inject
  InMemoryInteractionStore(@InMemoryStorageBackend TtlCache storage) {
    this.storage = storage;
  }

  @Override
  public void add(String cbid, InteractionType type) {
    storage.add(cbid, type);
  }

  @Override
  public ImmutableList<Interaction> get(String cbid) {
    return storage.get(cbid);
  }

  @Override
  public void delete(String cbid) {
    storage.delete(cbid);
  }

  public static InMemoryStorageModule getModule(InMemoryStorageConfig config) {
    return new InMemoryStorageModule(config);
  }

  public static InMemoryStorageModule getModuleForTesting() {
    return new InMemoryStorageModule(InMemoryStorageConfig.createForTesting());
  }

  private static class InMemoryStorageModule extends AbstractModule {

    private final InMemoryStorageConfig config;

    private InMemoryStorageModule(InMemoryStorageConfig config) {
      this.config = config;
    }

    @Override
    protected void configure() {
      bind(InteractionStore.class).to(InMemoryInteractionStore.class);
    }

    @Provides
    @Singleton
    @InMemoryStorageBackend
    TtlCache providesInMemoryStorageBackend(@UtcClock Clock utcClock) {
      TtlCache cache = new TtlCache(new ConcurrentHashMap<>(), utcClock, config);
      cache.init();
      return cache;
    }
  }

  static final class TtlCache {
    private final ConcurrentHashMap<String, List<Interaction>> storage;
    private final Duration interactionTtl;
    private final Duration cleanupInterval;
    private final Clock utcClock;
    /** Executor which schedules the cleanup threads */
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat(this.getClass().getSimpleName() + "-%d")
                .setPriority(Thread.NORM_PRIORITY + 1)
                .build());

    TtlCache(
        ConcurrentHashMap<String, List<Interaction>> storage,
        Clock utcClock,
        InMemoryStorageConfig config) {
      this.storage = storage;
      this.utcClock = utcClock;
      this.interactionTtl = config.interactionTtl();
      this.cleanupInterval = config.interactionCleanupInterval();
    }

    void add(String cbid, InteractionType type) {
      storage.compute(
          cbid,
          (k, v) -> {
            List<Interaction> interactions = v;
            if (interactions == null) {
              interactions = new ArrayList<>();
            }

            Interaction.Builder interactionBuilder = Interaction.newBuilder();
            switch (type) {
              case HTTP_INTERACTION:
                interactions.add(
                    interactionBuilder
                        .setIsHttpInteraction(true)
                        .setRecordTime(Timestamps.fromMillis(Instant.now(utcClock).toEpochMilli()))
                        .build());
                break;
              case DNS_INTERACTION:
                interactions.add(
                    interactionBuilder
                        .setIsDnsInteraction(true)
                        .setRecordTime(Timestamps.fromMillis(Instant.now(utcClock).toEpochMilli()))
                        .build());
                break;
            }
            return interactions;
          });
    }

    ImmutableList<Interaction> get(String cbid) {
      return ImmutableList.copyOf(storage.getOrDefault(cbid, ImmutableList.of()));
    }

    void delete(String cbid) {
      storage.remove(cbid);
    }

    void init() {
      @SuppressWarnings("unused")
      Future<?> ignored =
          scheduler.scheduleAtFixedRate(
              this::cleanup, cleanupInterval.toMillis(), cleanupInterval.toMillis(), MILLISECONDS);
    }

    void cleanup() {
      ImmutableList<String> expiredCbids =
          storage.entrySet().stream()
              .filter(entry -> isExpired(entry.getValue()))
              .map(Map.Entry::getKey)
              .collect(toImmutableList());
      storage.keySet().removeAll(expiredCbids);
    }

    @SuppressWarnings("ProtoTimestampGetSecondsGetNano")
    private boolean isExpired(List<Interaction> interactions) {
      return interactions.stream()
          .anyMatch(
              interaction -> {
                Instant recordTime =
                    Instant.ofEpochSecond(
                        interaction.getRecordTime().getSeconds(),
                        interaction.getRecordTime().getNanos());
                return Instant.now(utcClock).isAfter(recordTime.plus(interactionTtl));
              });
    }
  }
}
