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
import java.util.Optional;

/** Data model for the HTTP recording server configuration. */
@AutoValue
public abstract class HttpRecordingServerConfig {
  public abstract int port();
  public abstract Optional<Integer> workerPoolSize();

  public static HttpRecordingServerConfig fromRawData(
      Map<String, Object> httpRecordingServerConfig) {
    int port = (int) httpRecordingServerConfig.get("port");
    Optional<Integer> workerPoolSize =
        Optional.ofNullable((Integer) httpRecordingServerConfig.get("worker_pool_size"));
    return new AutoValue_HttpRecordingServerConfig(port, workerPoolSize);
  }
}
