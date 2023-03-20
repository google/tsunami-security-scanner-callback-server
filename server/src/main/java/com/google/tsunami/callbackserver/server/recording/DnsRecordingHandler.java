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

import static com.google.tsunami.callbackserver.common.CbidProcessor.extractCbidFromDomainInDnsProtocol;
import static com.google.tsunami.callbackserver.server.common.RequestLogger.maybeGetClientAddrAsString;

import com.google.common.flogger.GoogleLogger;
import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import com.google.tsunami.callbackserver.server.common.DnsHandler;
import com.google.tsunami.callbackserver.server.common.monitoring.TcsEventsObserver;
import com.google.tsunami.callbackserver.server.recording.Annotations.AuthoritativeDnsDomain;
import com.google.tsunami.callbackserver.server.recording.Annotations.IpForDnsAnswer;
import com.google.tsunami.callbackserver.storage.InteractionStore;
import com.google.tsunami.callbackserver.storage.InteractionStore.InteractionType;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Optional;
import javax.inject.Inject;

/** DNS handler for recording interactions via DNS requests. */
final class DnsRecordingHandler extends DnsHandler {
  private static final String ENDPOINT_NAME = "RECORDING";
  private static final int TTL = 60;

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final InteractionStore interactionStore;
  private final String serverExternalIp;
  private final String authoritativeDnsDomain;

  @Inject
  DnsRecordingHandler(
      InteractionStore interactionStore,
      @IpForDnsAnswer String serverExternalIp,
      @AuthoritativeDnsDomain String authoritativeDnsDomain,
      TcsEventsObserver tcsEventsObserver) {
    super(ENDPOINT_NAME, tcsEventsObserver);
    this.interactionStore = interactionStore;
    this.serverExternalIp = serverExternalIp;
    this.authoritativeDnsDomain = authoritativeDnsDomain;
  }

  @Override
  protected DatagramDnsResponse handleRequest(
      DatagramDnsQuery request, Optional<InetAddress> clientAddr) {
    DefaultDnsQuestion question = request.recordAt(DnsSection.QUESTION);
    DatagramDnsResponse response = buildBasicDnsResponse(request);

    if (!shouldAnswer(question.name(), authoritativeDnsDomain)) {
      response.setCode(DnsResponseCode.REFUSED);
      return response;
    }

    extractCbidFromDomainInDnsProtocol(question.name())
        .ifPresent(
            cbid -> {
              logger.atInfo().log(
                  "Recording DNS interaction with CBID '%s' from IP %s",
                  cbid, maybeGetClientAddrAsString(clientAddr));
              interactionStore.add(cbid, InteractionType.DNS_INTERACTION);
              tcsEventsObserver.onDnsInteractionRecorded();
            });

    response.addRecord(DnsSection.ANSWER, buildAnswer(question.name(), serverExternalIp));
    return response;
  }

  private static boolean shouldAnswer(String questionDomain, String authoritativeDnsDomain) {
    // TODO(b/210803971): Evaluate whether best-effort normalization vs throwing an error and
    // aborting the request causes any undesirable behavior.
    // Notes: punycode, FQDN trailing dot, etc. at worst we may be giving spurious responses but
    // afaik that should be damaging to us and direct string manipulation of the
    // requests shouldn't significantly expose us to security risks.

    // Best-effort normalization of domain names.
    try {
      questionDomain = InternetDomainName.from(questionDomain).toString();
    } catch (IllegalArgumentException e) {
      // pass
    }
    try {
      authoritativeDnsDomain = InternetDomainName.from(authoritativeDnsDomain).toString();
    } catch (IllegalArgumentException e) {
      // pass
    }

    // Normalize to FQDN since configuration may specify without trailing dot.
    if (!questionDomain.endsWith(".")) {
      questionDomain = questionDomain + ".";
    }
    if (!authoritativeDnsDomain.endsWith(".")) {
      authoritativeDnsDomain = authoritativeDnsDomain + ".";
    }

    return questionDomain.equals(authoritativeDnsDomain)
        || questionDomain.endsWith("." + authoritativeDnsDomain);
  }

  private static DefaultDnsRawRecord buildAnswer(String domainName, String answerIp) {
    InetAddress answerIpAddr = InetAddresses.forString(answerIp);
    if (answerIpAddr instanceof Inet4Address) {
      return new DefaultDnsRawRecord(
          domainName, DnsRecordType.A, TTL, Unpooled.copiedBuffer(answerIpAddr.getAddress()));
    } else if (answerIpAddr instanceof Inet6Address) {
      return new DefaultDnsRawRecord(
          domainName, DnsRecordType.AAAA, TTL, Unpooled.copiedBuffer(answerIpAddr.getAddress()));
    } else {
      throw new AssertionError(String.format("Unexpected answerIp format: %s", answerIp));
    }
  }
}
