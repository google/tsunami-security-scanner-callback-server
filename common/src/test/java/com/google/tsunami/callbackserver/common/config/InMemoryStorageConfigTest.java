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
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link InMemoryStorageConfig}. */
@RunWith(JUnit4.class)
public final class InMemoryStorageConfigTest {

  @Test
  public void fromRawData_always_createsExpectedConfigObject() {
    InMemoryStorageConfig config =
        InMemoryStorageConfig.fromRawData(
            ImmutableMap.of("interaction_ttl_secs", 10, "cleanup_interval_secs", 20));

    assertThat(config.interactionTtl()).isEqualTo(Duration.ofSeconds(10));
    assertThat(config.interactionCleanupInterval()).isEqualTo(Duration.ofSeconds(20));
  }

  @Test
  public void fromRawData_whenMissingInteractionTtl_throws() {
    assertThrows(
        NullPointerException.class,
        () -> InMemoryStorageConfig.fromRawData(ImmutableMap.of("cleanup_interval_secs", 20)));
  }

  @Test
  public void fromRawData_whenMissingCleanupInterval_throws() {
    assertThrows(
        NullPointerException.class,
        () -> InMemoryStorageConfig.fromRawData(ImmutableMap.of("interaction_ttl_secs", 10)));
  }

  @Test
  public void fromRawData_whenInvalidType_throws() {
    assertThrows(
        ClassCastException.class,
        () -> InMemoryStorageConfig.fromRawData(ImmutableMap.of("interaction_ttl_secs", "10")));
  }
}
