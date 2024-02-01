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
package com.google.tsunami.callbackserver.common.config;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.truth.Truth8;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link StorageConfig}. */
@RunWith(JUnit4.class)
public final class StorageConfigTest {
  private static final ImmutableMap<String, Object> IN_MEMORY_STORAGE_CONFIG_DATA =
      ImmutableMap.of("interaction_ttl_secs", 10, "cleanup_interval_secs", 20);
  private static final ImmutableMap<String, Object> REDIS_STORAGE_CONFIG_DATA =
      ImmutableMap.of(
          "interaction_ttl_secs",
          10,
          "read_endpoint_host",
          "127.0.0.1",
          "read_endpoint_port",
          6379,
          "write_endpoint_host",
          "127.0.0.2",
          "write_endpoint_port",
          6379);

  @Test
  public void fromRawData_givenInMemoryStorageConfig_createsExpectedConfigObject() {
    StorageConfig config =
        StorageConfig.fromRawData(ImmutableMap.of("in_memory", IN_MEMORY_STORAGE_CONFIG_DATA));

    Truth8.assertThat(config.inMemoryStorageConfig())
        .hasValue(InMemoryStorageConfig.fromRawData(IN_MEMORY_STORAGE_CONFIG_DATA));
    Truth8.assertThat(config.redisStorageConfig()).isEmpty();
  }

  @Test
  public void fromRawData_givenRedisStorageConfig_createsExpectedConfigObject() {
    StorageConfig config =
        StorageConfig.fromRawData(ImmutableMap.of("redis", REDIS_STORAGE_CONFIG_DATA));

    Truth8.assertThat(config.inMemoryStorageConfig()).isEmpty();
    Truth8.assertThat(config.redisStorageConfig())
        .hasValue(RedisStorageConfig.fromRawData(REDIS_STORAGE_CONFIG_DATA));
  }

  @Test
  public void fromRawData_givenMoreThanOneConfig_throws() {
    var error =
        assertThrows(
            AssertionError.class,
            () ->
                StorageConfig.fromRawData(
                    ImmutableMap.of(
                        "in_memory",
                        IN_MEMORY_STORAGE_CONFIG_DATA,
                        "redis",
                        REDIS_STORAGE_CONFIG_DATA)));
    assertThat(error)
        .hasMessageThat()
        .isEqualTo("At most one storage backend should be configured.");
  }

  @Test
  public void fromRawData_givenEmptyConfig_throws() {
    var error =
        assertThrows(AssertionError.class, () -> StorageConfig.fromRawData(ImmutableMap.of()));
    assertThat(error).hasMessageThat().isEqualTo("At least one storage backend is required.");
  }

  @Test
  public void fromRawData_whenInvalidType_throws() {
    assertThrows(
        ClassCastException.class,
        () -> StorageConfig.fromRawData(ImmutableMap.of("in_memory", "abc")));
    assertThrows(
        ClassCastException.class, () -> StorageConfig.fromRawData(ImmutableMap.of("redis", "abc")));
  }
}
