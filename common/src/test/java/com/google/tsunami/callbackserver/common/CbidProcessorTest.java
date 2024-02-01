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

package com.google.tsunami.callbackserver.common;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.net.HostAndPort;
import com.google.common.truth.Truth8;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link CbidProcessor}. */
@RunWith(JUnit4.class)
public final class CbidProcessorTest {
  private static final String FAKE_CBID =
      "b0f3dc043a9c5c05f67651a8c9108b4c2b98e7246b2eea14cb204295";
  private static final String FAKE_CBID_X20_ENCODED =
      "B0f3dc043a9c5c05F67651a8c9108b4c2B98e7246b2Eea14cb204295";

  @Test
  public void addCbidToUrl_returnsUriWithCbidInPath() {
    String uriString = CbidProcessor.addCbidToUrl(FAKE_CBID, HostAndPort.fromHost("127.0.0.1"));

    assertThat(uriString).isEqualTo("http://127.0.0.1/" + FAKE_CBID);
  }

  @Test
  public void addCbidToSubdomain_returnsUriWithCbidInSubdomain() {
    String uriString = CbidProcessor.addCbidToSubdomain(FAKE_CBID, HostAndPort.fromHost("tcs.com"));

    assertThat(uriString).isEqualTo(FAKE_CBID + ".tcs.com");
  }

  @Test
  public void extractCbidFromUrl_urlWithCbid_returnsCbid() {
    Optional<String> result = CbidProcessor.extractCbidFromUrl("http://anyDomain.com/" + FAKE_CBID);

    Truth8.assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInDnsProtocol_domainWithCbid_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInDnsProtocol(FAKE_CBID + ".anyDomain.com");

    Truth8.assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInDnsProtocol_domainWithX20EncodedCbid_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInDnsProtocol(FAKE_CBID_X20_ENCODED + ".anyDomain.com");

    Truth8.assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInDnsProtocol_domainWithoutCbid_returnsEmpty() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInDnsProtocol("anySubdomain.anyDomain.com");

    Truth8.assertThat(result).isEmpty();
  }

  @Test
  // This can NEVER happen in practice, where a http url is passed into cbid extractor for DNS.
  // Otherwise, the callback server would incorrectly log a cbid that doesn't actually come from a
  // DNS lookup.
  public void extractCbidFromDomainInDnsProtocol_domainWithoutCbidPathWithCbid_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInDnsProtocol(
            "http://anySubdomain.anyDomain.com/q=" + FAKE_CBID + ".anyDomain.com");

    Truth8.assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInHttpProtocol_domainWithCbid_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInHttpProtocol("http://" + FAKE_CBID + ".anyDomain.com");

    Truth8.assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInHttpProtocol_domainWithoutCbidPathWithCbid_returnsEmpty() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInHttpProtocol(
            "http://anySubdomain.anyDomain.com/q=" + FAKE_CBID + ".anyDomain.com");

    Truth8.assertThat(result).isEmpty();
  }

  @Test
  public void extractCbidFromUrl_urlWithoutCbid_returnsEmpty() {
    Optional<String> result = CbidProcessor.extractCbidFromUrl("http://anyDomain.com/");

    Truth8.assertThat(result).isEmpty();
  }
}
