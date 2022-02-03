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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TcsConfig}. */
@RunWith(JUnit4.class)
public class TcsConfigTest {
  private static final ImmutableMap<String, Object> COMMON_CONFIG_DATA =
      ImmutableMap.of("domain", "irrelevant", "external_ip", "127.0.0.1");
  private static final ImmutableMap<String, Object> IN_MEMORY_STORAGE_CONFIG_DATA =
      ImmutableMap.of("interaction_ttl_secs", 10, "cleanup_interval_secs", 20);
  private static final ImmutableMap<String, Object> STORAGE_CONFIG_DATA =
      ImmutableMap.of("in_memory", IN_MEMORY_STORAGE_CONFIG_DATA);
  private static final ImmutableMap<String, Object> HTTP_RECORDING_CONFIG_DATA =
      ImmutableMap.of("port", 80);
  private static final ImmutableMap<String, Object> DNS_RECORDING_CONFIG_DATA =
      ImmutableMap.of("port", 53);
  private static final ImmutableMap<String, Object> RECORDING_SERVER_CONFIG_DATA =
      ImmutableMap.of("http", HTTP_RECORDING_CONFIG_DATA, "dns", DNS_RECORDING_CONFIG_DATA);
  private static final ImmutableMap<String, Object> POLLING_SERVER_CONFIG_DATA =
      ImmutableMap.of("port", 8080);
  private static final String YAML_CONFIG_DATA =
      "common:\n"
          + " domain: irrelevant\n"
          + " external_ip: 127.0.0.1\n"
          + "storage:\n"
          + " in_memory:\n"
          + "  interaction_ttl_secs: 10\n"
          + "  cleanup_interval_secs: 20\n"
          + "recording:\n"
          + " http:\n"
          + "  port: 80\n"
          + " dns:\n"
          + "  port: 53\n"
          + "polling:\n"
          + " port: 8080";

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void fromYamlFile_always_createsExpectedConfigObject() throws IOException {
    File configFile = temporaryFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8).write(YAML_CONFIG_DATA);
    TcsConfig config = TcsConfig.fromYamlFile(configFile.getAbsolutePath());

    assertThat(config.commonConfig()).isEqualTo(CommonConfig.fromRawData(COMMON_CONFIG_DATA));
    assertThat(config.storageConfig()).isEqualTo(StorageConfig.fromRawData(STORAGE_CONFIG_DATA));
    assertThat(config.recordingServerConfig())
        .isEqualTo(RecordingServerConfig.fromRawData(RECORDING_SERVER_CONFIG_DATA));
    assertThat(config.pollingServerConfig())
        .isEqualTo(PollingServerConfig.fromRawData(POLLING_SERVER_CONFIG_DATA));
  }

  @Test
  public void fromYamlFile_whenConfigFileNotFound_throws() {
    assertThrows(FileNotFoundException.class, () -> TcsConfig.fromYamlFile("not_exist"));
  }

  @Test
  public void fromRawData_always_createsExpectedConfigObject() {
    TcsConfig config =
        TcsConfig.fromRawData(
            ImmutableMap.of(
                "common", COMMON_CONFIG_DATA,
                "storage", STORAGE_CONFIG_DATA,
                "recording", RECORDING_SERVER_CONFIG_DATA,
                "polling", POLLING_SERVER_CONFIG_DATA));

    assertThat(config.commonConfig()).isEqualTo(CommonConfig.fromRawData(COMMON_CONFIG_DATA));
    assertThat(config.storageConfig()).isEqualTo(StorageConfig.fromRawData(STORAGE_CONFIG_DATA));
    assertThat(config.recordingServerConfig())
        .isEqualTo(RecordingServerConfig.fromRawData(RECORDING_SERVER_CONFIG_DATA));
    assertThat(config.pollingServerConfig())
        .isEqualTo(PollingServerConfig.fromRawData(POLLING_SERVER_CONFIG_DATA));
  }

  @Test
  public void fromRawData_whenMissingConfigKey_throws() {
    assertThrows(
        NullPointerException.class, () -> TcsConfig.fromRawData(ImmutableMap.of("k", "v")));
  }

  @Test
  public void fromRawData_whenInvalidType_throws() {
    assertThrows(
        ClassCastException.class, () -> TcsConfig.fromRawData(ImmutableMap.of("common", "abc")));
  }
}
