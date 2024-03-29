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
package com.google.tsunami.callbackserver.server.common;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.google.common.net.InetAddresses;
import com.google.tsunami.callbackserver.server.common.monitoring.TcsEventsObserver;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link DnsHandler}. */
@RunWith(JUnit4.class)
public final class DnsHandlerTest {
  private static final String ENDPOINT_NAME = "TestDnsHandler";
  private static final InetAddress ANSWER_IP = InetAddresses.forString("127.0.0.1");
  private static final String DOMAIN = "localhost";
  private static final int TTL = 1;
  private static final DnsQuestion QUESTION = new DefaultDnsQuestion(DOMAIN, DnsRecordType.A);

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock TcsEventsObserver eventsObserverMock;

  @Test
  public void handleRequest_withValidRequest_returnsNoError() {
    DatagramDnsResponse response = runRequest(new OkStatusDnsHandler());

    assertThat(response.code()).isEqualTo(DnsResponseCode.NOERROR);
    assertThat(response.isAuthoritativeAnswer()).isTrue();
    assertThat((DnsQuestion) response.recordAt(DnsSection.QUESTION)).isEqualTo(QUESTION);
    assertThat(response.recordAt(DnsSection.ANSWER).name()).isEqualTo(DOMAIN + ".");
    assertThat(((DefaultDnsRawRecord) response.recordAt(DnsSection.ANSWER)).content().array())
        .isEqualTo(ANSWER_IP.getAddress());
    verify(eventsObserverMock).onSuccessfulDnsRpc(eq(ENDPOINT_NAME), any(Duration.class));
  }

  @Test
  public void handleRequest_withServerError_returnsServFail() {
    DatagramDnsResponse response = runRequest(new ErrorStatusDnsHandler());

    assertThat(response.code()).isEqualTo(DnsResponseCode.SERVFAIL);
    assertThat(response.isAuthoritativeAnswer()).isTrue();
    assertThat((DnsQuestion) response.recordAt(DnsSection.QUESTION)).isEqualTo(QUESTION);
    verify(eventsObserverMock)
        .onFailedDnsRpc(
            eq(ENDPOINT_NAME),
            any(Duration.class),
            eq(DnsResponseCode.SERVFAIL),
            ArgumentMatchers.<Optional<Exception>>any());
  }

  private static DatagramDnsResponse runRequest(DnsHandler handler) {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    DatagramDnsQuery request =
        new DatagramDnsQuery(null, InetSocketAddress.createUnresolved("localhost", 0), 1);
    request.addRecord(DnsSection.QUESTION, QUESTION);
    channel.writeInbound(request);
    return channel.readOutbound();
  }

  private abstract class BaseTestDnsHandler extends DnsHandler {
    BaseTestDnsHandler() {
      super(ENDPOINT_NAME, RequestLogger.INSTANCE, eventsObserverMock);
    }
  }

  private class OkStatusDnsHandler extends BaseTestDnsHandler {
    @Override
    protected DatagramDnsResponse handleRequest(
        DatagramDnsQuery request, Optional<InetAddress> clientAddr) throws Exception {
      DatagramDnsResponse response = buildBasicDnsResponse(request);
      response.addRecord(
          DnsSection.ANSWER,
          new DefaultDnsRawRecord(
              DOMAIN, DnsRecordType.A, TTL, Unpooled.copiedBuffer(ANSWER_IP.getAddress())));
      return response;
    }
  }

  private class ErrorStatusDnsHandler extends BaseTestDnsHandler {
    @Override
    protected DatagramDnsResponse handleRequest(
        DatagramDnsQuery request, Optional<InetAddress> clientAddr) throws Exception {
      throw new RuntimeException();
    }
  }
}
