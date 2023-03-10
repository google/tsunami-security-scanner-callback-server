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

package com.google.tsunami.callbackserver.server.common.monitoring;

import com.google.inject.AbstractModule;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.Duration;
import java.util.Optional;

/** An TcsEventsObserver that does nothing. */
public final class NoOpTcsEventsObserver implements TcsEventsObserver {
  public static final NoOpTcsEventsObserver INSTANCE = new NoOpTcsEventsObserver();

  @Override
  public void onSuccessfullHttpRpc(
      String endpointName, Duration responseTime, HttpResponseStatus responseCode) {}

  @Override
  public void onFailedHttpRpc(
      String endpointName,
      Duration responseTime,
      HttpResponseStatus responseCode,
      Optional<Exception> ex) {}

  @Override
  public void onSuccessfullDnsRpc(String endpointName, Duration responseTime) {}

  @Override
  public void onFailedDnsRpc(
      String endpointName,
      Duration responseTime,
      DnsResponseCode responseCode,
      Optional<Exception> ex) {}

  @Override
  public void onHttpInteractionRecorded() {}

  @Override
  public void onDnsInteractionRecorded() {}

  @Override
  public void onHttpInteractionFound() {}

  @Override
  public void onDnsInteractionFound() {}

  @Override
  public void onInteractionNotFound() {}

  private NoOpTcsEventsObserver() {}

  /** The Guice module to install the NoOpTcsEventsObserver. */
  public static final class Module extends AbstractModule {
    @Override
    protected void configure() {
      bind(TcsEventsObserver.class).toInstance(INSTANCE);
    }
  }
}
