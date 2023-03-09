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
package com.google.tsunami.callbackserver.server.recording;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.net.InetAddresses;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClock;
import com.google.tsunami.callbackserver.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.callbackserver.proto.Interaction;
import com.google.tsunami.callbackserver.server.recording.Annotations.AuthoritativeDnsDomain;
import com.google.tsunami.callbackserver.server.recording.Annotations.IpForDnsAnswer;
import com.google.tsunami.callbackserver.storage.InMemoryInteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import javax.inject.Inject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DnsRecordingHandler}. */
@RunWith(JUnit4.class)
public final class DnsRecordingHandlerTest {
  private static final FakeUtcClock fakeUtcClock =
      FakeUtcClock.create().setNow(Instant.parse("2020-01-01T00:00:00.00Z"));
  private static final String FAKE_CBID =
      "b0f3dc043a9c5c05f67651a8c9108b4c2b98e7246b2eea14cb204295";
  private static final Interaction FAKE_DNS_INTERACTION =
      Interaction.newBuilder()
          .setIsHttpInteraction(false)
          .setIsDnsInteraction(true)
          .setRecordTime(Timestamps.fromMillis(Instant.now(fakeUtcClock).toEpochMilli()))
          .build();
  private static final InetAddress ANSWER_IPV4_IP = InetAddresses.forString("127.0.0.1");
  private static final InetAddress ANSWER_IPV6_IP = InetAddresses.forString("::1");
  private static final String AUTHORITATIVE_DOMAIN = "domain.com";
  private static final InetAddress TEST_CLIENT_ADDRESS = InetAddresses.forString("1.2.3.4");

  @Inject private DnsRecordingHandler handler;
  @Inject private InteractionStore interactionStore;

  @Before
  public void setUp() {
    createInjector(ANSWER_IPV4_IP.getHostAddress()).injectMembers(this);
  }

  private static Injector createInjector(String dnsAnswerIp) {
    return Guice.createInjector(
        new AbstractModule() {
          @Override
          protected void configure() {
            install(InMemoryInteractionStore.getModuleForTesting());
            install(new FakeUtcClockModule(fakeUtcClock));
            bind(String.class).annotatedWith(IpForDnsAnswer.class).toInstance(dnsAnswerIp);
            bind(String.class)
                .annotatedWith(AuthoritativeDnsDomain.class)
                .toInstance(AUTHORITATIVE_DOMAIN);
          }
        });
  }

  @Test
  public void handleRequest_whenNoErrors_alwaysReturnNoErrorStatus() {
    DatagramDnsResponse response =
        handler.handleRequest(
            buildRequest(FAKE_CBID + "." + AUTHORITATIVE_DOMAIN), TEST_CLIENT_ADDRESS);

    assertThat(response.code()).isEqualTo(DnsResponseCode.NOERROR);
    assertThat(response.recordAt(DnsSection.ANSWER).type()).isEqualTo(DnsRecordType.A);
    assertThat(((DefaultDnsRawRecord) response.recordAt(DnsSection.ANSWER)).content().array())
        .isEqualTo(ANSWER_IPV4_IP.getAddress());
  }

  @Test
  public void handleRequest_whenNoErrorsWithIpV6Answer_alwaysReturnNoErrorStatus() {
    DatagramDnsResponse response =
        createInjector(ANSWER_IPV6_IP.getHostAddress())
            .getInstance(DnsRecordingHandler.class)
            .handleRequest(
                buildRequest(FAKE_CBID + "." + AUTHORITATIVE_DOMAIN), TEST_CLIENT_ADDRESS);

    assertThat(response.code()).isEqualTo(DnsResponseCode.NOERROR);
    assertThat(response.recordAt(DnsSection.ANSWER).type()).isEqualTo(DnsRecordType.AAAA);
    assertThat(((DefaultDnsRawRecord) response.recordAt(DnsSection.ANSWER)).content().array())
        .isEqualTo(ANSWER_IPV6_IP.getAddress());
  }

  @Test
  public void handleRequest_whenNoCbid_alwaysReturnNoErrorStatus() {
    DatagramDnsResponse response =
        handler.handleRequest(buildRequest(AUTHORITATIVE_DOMAIN), TEST_CLIENT_ADDRESS);

    assertThat(response.code()).isEqualTo(DnsResponseCode.NOERROR);
    assertThat(response.recordAt(DnsSection.ANSWER).type()).isEqualTo(DnsRecordType.A);
    assertThat(((DefaultDnsRawRecord) response.recordAt(DnsSection.ANSWER)).content().array())
        .isEqualTo(ANSWER_IPV4_IP.getAddress());
  }

  @Test
  public void handleRequest_whenInvalidDomain_returnsRefused() {
    assertThat(handler.handleRequest(buildRequest("refused.com"), TEST_CLIENT_ADDRESS).code())
        .isEqualTo(DnsResponseCode.REFUSED);
    assertThat(handler.handleRequest(buildRequest("refuseddomain.com"), TEST_CLIENT_ADDRESS).code())
        .isEqualTo(DnsResponseCode.REFUSED);
  }

  @Test
  public void handleRequest_whenValidCbidInDomain_savesInteraction() {
    var unused =
        handler.handleRequest(
            buildRequest(FAKE_CBID + "." + AUTHORITATIVE_DOMAIN), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_DNS_INTERACTION);
  }

  @Test
  public void handleRequest_whenInvalidCbidInDomain_ignoresCbid() {
    var unused =
        handler.handleRequest(
            buildRequest("RANDOM_DOMAIN." + AUTHORITATIVE_DOMAIN), TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get("RANDOM_DOMAIN")).isEmpty();
  }

  private static DatagramDnsQuery buildRequest(String domain) {
    DatagramDnsQuery request =
        new DatagramDnsQuery(
            /* sender= */ null, InetSocketAddress.createUnresolved("localhost", 0), /* id= */ 1);
    request.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(domain, DnsRecordType.A));
    return request;
  }

  @Test
  public void handleRequest_withLog4jDomain_resolvesCorrectly() {
    DatagramDnsResponse response =
        handler.handleRequest(
            buildRequest("localhost#." + FAKE_CBID + "." + AUTHORITATIVE_DOMAIN),
            TEST_CLIENT_ADDRESS);

    assertThat(response.code()).isEqualTo(DnsResponseCode.NOERROR);
    assertThat(response.recordAt(DnsSection.ANSWER).type()).isEqualTo(DnsRecordType.A);
    assertThat(((DefaultDnsRawRecord) response.recordAt(DnsSection.ANSWER)).content().array())
        .isEqualTo(ANSWER_IPV4_IP.getAddress());
  }

  @Test
  public void handleRequest_withLog4jDomain_savesInteraction() {
    var unused =
        handler.handleRequest(
            buildRequest("localhost#." + FAKE_CBID + "." + AUTHORITATIVE_DOMAIN),
            TEST_CLIENT_ADDRESS);

    assertThat(interactionStore.get(FAKE_CBID)).containsExactly(FAKE_DNS_INTERACTION);
  }

  @Test
  public void handleRequest_withInvalidLog4jDomain_returnsRefused() {
    assertThat(
            handler
                .handleRequest(
                    buildRequest("localhost#." + FAKE_CBID + ".refused.com"), TEST_CLIENT_ADDRESS)
                .code())
        .isEqualTo(DnsResponseCode.REFUSED);
  }
}
