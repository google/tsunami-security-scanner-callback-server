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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.callbackserver.common.config.RedisStorageConfig;
import com.google.tsunami.callbackserver.common.time.UtcClock;
import com.google.tsunami.callbackserver.proto.Interaction;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/** A Redis implementation of the {@link InteractionStore}. */
public final class RedisInteractionStore implements InteractionStore {
  // We use Redis EVAL command with this lua script to ensure atomicity when storing an interaction
  // with a TTL.
  @VisibleForTesting
  static final String INTERACTION_STORE_CMD =
      "redis.call('RPUSH', KEYS[1], ARGV[1]); local ttl = redis.call('TTL', KEYS[1]); if ttl < 0"
          + " then redis.call('EXPIRE', KEYS[1], ARGV[2]); end";

  private final Clock utcClock;
  private final Duration interactionTtl;
  private final JedisPool readJedisPool;
  private final JedisPool writeJedisPool;

  @Inject
  RedisInteractionStore(
      @UtcClock Clock utcClock,
      @RedisInteractionTtl Duration interactionTtl,
      @ReadJedisPool JedisPool readJedisPool,
      @WriteJedisPool JedisPool writeJedisPool) {
    this.utcClock = utcClock;
    this.interactionTtl = interactionTtl;
    this.readJedisPool = readJedisPool;
    this.writeJedisPool = writeJedisPool;
  }

  @Override
  public void add(String cbid, InteractionType type) {
    byte[] key = cbid.getBytes(UTF_8);
    try (Jedis jedis = writeJedisPool.getResource()) {
      switch (type) {
        case HTTP_INTERACTION:
          jedis.eval(
              INTERACTION_STORE_CMD.getBytes(UTF_8),
              1,
              key,
              Interaction.newBuilder()
                  .setIsHttpInteraction(true)
                  .setRecordTime(Timestamps.fromMillis(Instant.now(utcClock).toEpochMilli()))
                  .build()
                  .toByteArray(),
              String.valueOf(interactionTtl.toSeconds()).getBytes(UTF_8));
          break;
        case DNS_INTERACTION:
          jedis.eval(
              INTERACTION_STORE_CMD.getBytes(UTF_8),
              1,
              key,
              Interaction.newBuilder()
                  .setIsDnsInteraction(true)
                  .setRecordTime(Timestamps.fromMillis(Instant.now(utcClock).toEpochMilli()))
                  .build()
                  .toByteArray(),
              String.valueOf(interactionTtl.toSeconds()).getBytes(UTF_8));
          break;
      }
    }
  }

  @Override
  @SuppressWarnings("ProtoParseWithRegistry")
  public ImmutableList<Interaction> get(String cbid) {
    try (Jedis jedis = readJedisPool.getResource()) {
      return jedis.lrange(cbid.getBytes(UTF_8), 0, -1).stream()
          .map(
              rawBytes -> {
                try {
                  return Interaction.parseFrom(rawBytes);
                } catch (InvalidProtocolBufferException e) {
                  throw new AssertionError("Redis data cannot be parsed as Interaction proto.", e);
                }
              })
          .collect(toImmutableList());
    }
  }

  @Override
  public void delete(String cbid) {
    try (Jedis jedis = writeJedisPool.getResource()) {
      jedis.del(cbid.getBytes(UTF_8));
    }
  }

  public static RedisInteractionStoreModule getModule(RedisStorageConfig config) {
    return new RedisInteractionStoreModule(config);
  }

  private static class RedisInteractionStoreModule extends AbstractModule {

    private final RedisStorageConfig config;

    private RedisInteractionStoreModule(RedisStorageConfig config) {
      this.config = config;
    }

    @Override
    protected void configure() {
      bind(InteractionStore.class).to(RedisInteractionStore.class);
    }

    @Provides
    @RedisInteractionTtl
    Duration providesRedisInteractionTtl() {
      return config.interactionTtl();
    }

    @Provides
    @Singleton
    @ReadJedisPool
    @SuppressWarnings("CloseableProvides") // JedisPool will never be closed while server is running
    JedisPool providesReadJedisPool() {
      JedisPoolConfig readPoolConfig = new JedisPoolConfig();
      readPoolConfig.setMaxTotal(128);
      readPoolConfig.setMaxIdle(128);
      readPoolConfig.setMinIdle(32);

      return new JedisPool(readPoolConfig, config.readEndpointHost(), config.readEndpointPort());
    }

    @Provides
    @Singleton
    @WriteJedisPool
    @SuppressWarnings("CloseableProvides") // JedisPool will never be closed while server is running
    JedisPool providesWriteJedisPool() {
      JedisPoolConfig writePoolConfig = new JedisPoolConfig();
      writePoolConfig.setMaxTotal(128);
      writePoolConfig.setMaxIdle(128);
      writePoolConfig.setMinIdle(32);

      return new JedisPool(writePoolConfig, config.writeEndpointHost(), config.writeEndpointPort());
    }
  }

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @interface RedisInteractionTtl {}

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @interface ReadJedisPool {}

  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @interface WriteJedisPool {}
}
