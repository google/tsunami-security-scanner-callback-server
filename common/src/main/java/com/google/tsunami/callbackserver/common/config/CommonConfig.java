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
import com.google.common.net.InetAddresses;
import java.util.Map;

/** Data model for TCS server's shared configuration. */
@AutoValue
public abstract class CommonConfig {
  public abstract String domain();
  public abstract String externalIp();

  public static CommonConfig fromRawData(Map<String, Object> commonConfig) {
    String domain = (String) commonConfig.get("domain");
    String externalIp = (String) commonConfig.get("external_ip");
    if (!InetAddresses.isInetAddress(externalIp)) {
      throw new IllegalArgumentException(
          String.format("Config common.external_ip is not valid: %s.", externalIp));
    }
    return new AutoValue_CommonConfig(domain, externalIp);
  }
}
