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
  // A 64 character long hexadecimal string, i.e. longer than a CBID.
  private static final String SHA256_HEX =
      "b0f3dc043a9c5c05f67651a8c9108b4c2b98e7246b2eea14cb2042950123abcd";

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

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInDnsProtocol_domainWithCbid_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInDnsProtocol(FAKE_CBID + ".anyDomain.com");

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInDnsProtocol_domainWithX20EncodedCbid_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInDnsProtocol(FAKE_CBID_X20_ENCODED + ".anyDomain.com");

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInDnsProtocol_domainWithoutCbid_returnsEmpty() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInDnsProtocol("anySubdomain.anyDomain.com");

    assertThat(result).isEmpty();
  }

  @Test
  // This can NEVER happen in practice, where a http url is passed into cbid extractor for DNS.
  // Otherwise, the callback server would incorrectly log a cbid that doesn't actually come from a
  // DNS lookup.
  public void extractCbidFromDomainInDnsProtocol_domainWithoutCbidPathWithCbid_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInDnsProtocol(
            "http://anySubdomain.anyDomain.com/q=" + FAKE_CBID + ".anyDomain.com");

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInHttpProtocol_domainWithCbid_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInHttpProtocol("http://" + FAKE_CBID + ".anyDomain.com");

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInHttpProtocol_domainWithoutCbidPathWithCbid_returnsEmpty() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInHttpProtocol(
            "http://anySubdomain.anyDomain.com/q=" + FAKE_CBID + ".anyDomain.com");

    assertThat(result).isEmpty();
  }

  @Test
  public void extractCbidFromUrl_urlWithoutCbid_returnsEmpty() {
    Optional<String> result = CbidProcessor.extractCbidFromUrl("http://anyDomain.com/");

    assertThat(result).isEmpty();
  }

  @Test
  public void extractCbidFromUrl_urlWithEncodedPrefixBeforeCbid_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://anyDomain.com/img%20" + FAKE_CBID);

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromUrl_urlWithCbidInSubPath_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://anyDomain.com/some/path/" + FAKE_CBID + "/more");

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromUrl_urlWithCbidAndFileExtension_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://anyDomain.com/" + FAKE_CBID + ".png");

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromUrl_urlWithCbidInQueryParameter_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl(
            "http://anyDomain.com/redirect?to=http%3A%2F%2FanyDomain.com%2F" + FAKE_CBID);

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromUrl_urlWithPercentEncodedCbid_returnsCbid() {
    // "b0" percent-encoded as "%62%30".
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://anyDomain.com/%62%30" + FAKE_CBID.substring(2));

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromUrl_urlWithDoublePercentEncodedCbid_returnsCbid() {
    // "b" double encoded: "%2562" decodes to "%62", which in turn decodes to "b".
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://anyDomain.com/%2562" + FAKE_CBID.substring(1));

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromUrl_urlWithTriplePercentEncodedCbid_returnsEmpty() {
    // Decoding stops after two rounds, so a CBID that only appears after a third round is not
    // recorded. This bounds the work spent on a single request.
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://anyDomain.com/%252562" + FAKE_CBID.substring(1));

    assertThat(result).isEmpty();
  }

  @Test
  public void extractCbidFromUrl_urlWithMalformedPercentEncoding_returnsCbid() {
    // "%/" is not a valid percent escape, so the URL cannot be decoded at all. The CBID must still
    // be found in the raw request target.
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://anyDomain.com/100%/" + FAKE_CBID);

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromUrl_urlWithUpperCaseCbid_returnsLowerCasedCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://anyDomain.com/" + FAKE_CBID_X20_ENCODED);

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromUrl_urlWithIllegalUriCharacters_returnsCbid() {
    // java.net.URI rejects '{' and '}', but OOB payloads regularly contain them.
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl(
            "http://anyDomain.com/?q=${jndi:ldap://anyDomain.com/" + FAKE_CBID + "}");

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromUrl_requestTargetWithoutScheme_returnsCbid() {
    Optional<String> result = CbidProcessor.extractCbidFromUrl("/" + FAKE_CBID);

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromUrl_urlWithCbidOnlyInDomain_returnsEmpty() {
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://" + FAKE_CBID + ".anyDomain.com/path");

    assertThat(result).isEmpty();
  }

  @Test
  public void extractCbidFromUrl_urlWithLongerHexStringInPath_returnsEmpty() {
    // A CBID must never be matched as a slice of a longer hexadecimal string, e.g. a SHA-256 hash.
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://anyDomain.com/" + SHA256_HEX);

    assertThat(result).isEmpty();
  }

  @Test
  public void extractCbidFromUrl_urlWithoutRequestTarget_returnsEmpty() {
    Optional<String> result =
        CbidProcessor.extractCbidFromUrl("http://" + FAKE_CBID + ".anyDomain.com");

    assertThat(result).isEmpty();
  }

  @Test
  public void extractCbidFromDomainInHttpProtocol_urlWithIllegalUriCharacters_returnsCbid() {
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInHttpProtocol(
            "http://" + FAKE_CBID + ".anyDomain.com/?q=${jndi}");

    assertThat(result).hasValue(FAKE_CBID);
  }

  @Test
  public void extractCbidFromDomainInHttpProtocol_illegalUriCharsAndCbidOnlyInPath_returnsEmpty() {
    // The manual host fallback must stop at the start of the request target, otherwise a CBID in
    // the path would be reported as if it had been part of the host.
    Optional<String> result =
        CbidProcessor.extractCbidFromDomainInHttpProtocol(
            "http://anyDomain.com/${jndi}/" + FAKE_CBID + ".evil.com");

    assertThat(result).isEmpty();
  }
}
