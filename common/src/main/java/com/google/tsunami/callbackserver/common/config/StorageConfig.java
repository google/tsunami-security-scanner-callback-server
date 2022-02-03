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
import java.util.Map;

/** Data model for the interaction storage configuration. */
@AutoValue
public abstract class StorageConfig {
  public abstract InMemoryStorageConfig inMemoryStorageConfig();

  @SuppressWarnings("unchecked") // Invalid casting should crash the whole binary.
  public static StorageConfig fromRawData(Map<String, Object> recordingServerConfig) {
    InMemoryStorageConfig inMemoryStorageConfig =
        InMemoryStorageConfig.fromRawData(
            (Map<String, Object>) recordingServerConfig.get("in_memory"));
    return new AutoValue_StorageConfig(inMemoryStorageConfig);
  }
}
