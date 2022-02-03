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

import com.google.auto.value.AutoValue;
import java.time.Duration;
import java.util.Map;

/** Data model for the in-memory storage configuration. */
@AutoValue
public abstract class InMemoryStorageConfig {
  public abstract Duration interactionTtl();
  public abstract Duration interactionCleanupInterval();

  public static InMemoryStorageConfig fromRawData(Map<String, Object> inMemoryStorageConfig) {
    Duration interactionTtl =
        Duration.ofSeconds((Integer) inMemoryStorageConfig.get("interaction_ttl_secs"));
    Duration interactionCleanupInterval =
        Duration.ofSeconds((Integer) inMemoryStorageConfig.get("cleanup_interval_secs"));
    return new AutoValue_InMemoryStorageConfig(interactionTtl, interactionCleanupInterval);
  }

  public static InMemoryStorageConfig createForTesting() {
    return new AutoValue_InMemoryStorageConfig(
        Duration.ofSeconds(Integer.MAX_VALUE), Duration.ofSeconds(Integer.MAX_VALUE));
  }
}
