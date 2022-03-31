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

/** Data model for the Redis storage configuration. */
@AutoValue
public abstract class RedisStorageConfig {
  public abstract Duration interactionTtl();
  public abstract String readEndpointHost();
  public abstract int readEndpointPort();
  public abstract String writeEndpointHost();
  public abstract int writeEndpointPort();

  public static RedisStorageConfig fromRawData(Map<String, Object> redisStorageConfig) {
    Duration interactionTtl =
        Duration.ofSeconds((Integer) redisStorageConfig.get("interaction_ttl_secs"));
    String readEndpointHost = (String) redisStorageConfig.get("read_endpoint_host");
    int readEndpointPort = (int) redisStorageConfig.get("read_endpoint_port");
    String writeEndpointHost = (String) redisStorageConfig.get("write_endpoint_host");
    int writeEndpointPort = (int) redisStorageConfig.get("write_endpoint_port");
    return new AutoValue_RedisStorageConfig(
        interactionTtl, readEndpointHost, readEndpointPort, writeEndpointHost, writeEndpointPort);
  }

  public static RedisStorageConfig createForTesting(String testingHost, int testingPort) {
    return new AutoValue_RedisStorageConfig(
        Duration.ofSeconds(Integer.MAX_VALUE), testingHost, testingPort, testingHost, testingPort);
  }
}
