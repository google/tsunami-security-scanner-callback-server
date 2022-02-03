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
import static com.google.common.truth.Truth8.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DnsRecordingServerConfig}. */
@RunWith(JUnit4.class)
public final class DnsRecordingServerConfigTest {

  @Test
  public void fromRawData_always_createsExpectedConfigObject() {
    DnsRecordingServerConfig config =
        DnsRecordingServerConfig.fromRawData(ImmutableMap.of("port", 53, "worker_pool_size", 10));

    assertThat(config.port()).isEqualTo(53);
    assertThat(config.workerPoolSize()).hasValue(10);
  }

  @Test
  public void fromRawData_whenNoWorkerPoolSize_createsExpectedConfigObject() {
    DnsRecordingServerConfig config =
        DnsRecordingServerConfig.fromRawData(ImmutableMap.of("port", 53));

    assertThat(config.port()).isEqualTo(53);
    assertThat(config.workerPoolSize()).isEmpty();
  }

  @Test
  public void fromRawData_whenMissingConfigKey_throws() {
    assertThrows(
        NullPointerException.class,
        () -> DnsRecordingServerConfig.fromRawData(ImmutableMap.of("k", "v")));
  }

  @Test
  public void fromRawData_whenInvalidType_throws() {
    assertThrows(
        ClassCastException.class,
        () -> DnsRecordingServerConfig.fromRawData(ImmutableMap.of("port", "abc")));
  }
}
