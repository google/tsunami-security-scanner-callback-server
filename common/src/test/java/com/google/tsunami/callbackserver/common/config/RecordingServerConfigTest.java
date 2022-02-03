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

import static com.google.common.truth.Truth8.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RecordingServerConfig}. */
@RunWith(JUnit4.class)
public final class RecordingServerConfigTest {
  private static final ImmutableMap<String, Object> HTTP_RECORDING_CONFIG_DATA =
      ImmutableMap.of("port", 80);
  private static final ImmutableMap<String, Object> DNS_RECORDING_CONFIG_DATA =
      ImmutableMap.of("port", 53);

  @Test
  public void fromRawData_always_createsExpectedConfigObject() {
    RecordingServerConfig config =
        RecordingServerConfig.fromRawData(
            ImmutableMap.of(
                "http", HTTP_RECORDING_CONFIG_DATA,
                "dns", DNS_RECORDING_CONFIG_DATA));

    assertThat(config.httpRecordingServerConfig())
        .hasValue(HttpRecordingServerConfig.fromRawData(HTTP_RECORDING_CONFIG_DATA));
    assertThat(config.dnsRecordingServerConfig())
        .hasValue(DnsRecordingServerConfig.fromRawData(DNS_RECORDING_CONFIG_DATA));
  }

  @Test
  public void fromRawData_whenNoHttpConfig_createsExpectedConfigObject() {
    RecordingServerConfig config =
        RecordingServerConfig.fromRawData(ImmutableMap.of("dns", DNS_RECORDING_CONFIG_DATA));

    assertThat(config.httpRecordingServerConfig()).isEmpty();
    assertThat(config.dnsRecordingServerConfig())
        .hasValue(DnsRecordingServerConfig.fromRawData(DNS_RECORDING_CONFIG_DATA));
  }

  @Test
  public void fromRawData_whenNoDnsConfig_createsExpectedConfigObject() {
    RecordingServerConfig config =
        RecordingServerConfig.fromRawData(ImmutableMap.of("http", HTTP_RECORDING_CONFIG_DATA));

    assertThat(config.httpRecordingServerConfig())
        .hasValue(HttpRecordingServerConfig.fromRawData(HTTP_RECORDING_CONFIG_DATA));
    assertThat(config.dnsRecordingServerConfig()).isEmpty();
  }

  @Test
  public void fromRawData_whenNoHttpAndDnsConfig_throws() {
    assertThrows(AssertionError.class, () -> RecordingServerConfig.fromRawData(ImmutableMap.of()));
  }

  @Test
  public void fromRawData_whenInvalidType_throws() {
    assertThrows(
        ClassCastException.class,
        () -> RecordingServerConfig.fromRawData(ImmutableMap.of("http", "abc")));
  }
}
