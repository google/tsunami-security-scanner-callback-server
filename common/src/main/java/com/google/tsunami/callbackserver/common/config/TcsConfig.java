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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auto.value.AutoValue;
import com.google.common.io.Files;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Data models for TCS server configs. */
@AutoValue
public abstract class TcsConfig {
  public abstract CommonConfig commonConfig();
  public abstract StorageConfig storageConfig();
  public abstract RecordingServerConfig recordingServerConfig();
  public abstract PollingServerConfig pollingServerConfig();

  public static TcsConfig fromYamlFile(String configFile) throws FileNotFoundException {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Map<String, Object> rawYamlData = yaml.load(Files.newReader(new File(configFile), UTF_8));
    return fromRawData(rawYamlData);
  }

  @SuppressWarnings("unchecked")  // Invalid casting should crash the whole binary.
  public static TcsConfig fromRawData(Map<String, Object> rawData) {
    // We don't actually perform any type or null pointer checks. Issues in the configuration file
    // should just crash the whole binary.
    return new AutoValue_TcsConfig(
        CommonConfig.fromRawData((Map<String, Object>) rawData.get("common")),
        StorageConfig.fromRawData((Map<String, Object>) rawData.get("storage")),
        RecordingServerConfig.fromRawData((Map<String, Object>) rawData.get("recording")),
        PollingServerConfig.fromRawData((Map<String, Object>) rawData.get("polling")));
  }
}
