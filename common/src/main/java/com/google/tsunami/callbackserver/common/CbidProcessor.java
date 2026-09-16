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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.net.HostAndPort;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility class for processing CBIDs. */
public final class CbidProcessor {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final String CBID_KEY = "CBID";
  private static final String CBID_PATTERN_STRING =
      String.format("(?<%s>[a-fA-F0-9]{56})", CBID_KEY);
  private static final String DOMAIN_CBID_PATTERN_STRING =
      String.format("\\.?%s\\.", CBID_PATTERN_STRING);
  private static final Pattern DOMAIN_CBID_PATTERN = Pattern.compile(DOMAIN_CBID_PATTERN_STRING);

  // Matches a CBID anywhere within the request target of an HTTP request (i.e. the path, the query
  // string or the fragment). Out-of-band payloads regularly embed the callback URL into a larger
  // string (e.g. "GET /img%20<cbid>" or "GET /?next=http://tcs/<cbid>"), so anchoring the CBID to
  // the whole path would silently drop those interactions.
  //
  // The lookarounds make sure that the CBID is not just a slice of a longer hexadecimal string,
  // which keeps unrelated hex blobs (e.g. a SHA-256 digest in a query parameter) from being
  // recorded as interactions.
  private static final String URL_CBID_PATTERN_STRING =
      String.format("(?<![a-fA-F0-9])%s(?![a-fA-F0-9])", CBID_PATTERN_STRING);
  private static final Pattern URL_CBID_PATTERN = Pattern.compile(URL_CBID_PATTERN_STRING);

  // Number of times a request target is percent-decoded before giving up. Clients (and the
  // intermediate proxies they traverse) sometimes double encode the callback URL.
  private static final int MAX_DECODING_ROUNDS = 2;

  private CbidProcessor() {}

  /**
   * Adds a given CBID into a URL for interaction tracking.
   *
   * @param cbid the CBID to be added to the interaction URL.
   * @param hostAndPort the TCS interaction tracking URL.
   * @return an HTTP interaction URL with the given CBID.
   */
  public static String addCbidToUrl(String cbid, HostAndPort hostAndPort) {
    return String.format("http://%s/%s", hostAndPort, cbid);
  }

  /**
   * Adds a given CBID into a domain name for interaction tracking.
   *
   * @param cbid the CBID to be added to the interaction domain.
   * @param hostAndPort the TCS interaction tracking endpoint.
   * @return an interaction hostname with the given CBID.
   */
  public static String addCbidToSubdomain(String cbid, HostAndPort hostAndPort) {
    return String.format("%s.%s", cbid, hostAndPort);
  }

  // Check if CBID exists in the host name of a HTTP request
  public static Optional<String> extractCbidFromDomainInHttpProtocol(String domainString) {
    Optional<String> host = extractHost(domainString);
    if (host.isEmpty()) {
      logger.atSevere().log("Unable to parse host from url: %s", domainString);
      return Optional.empty();
    }

    Matcher domainMatcher = DOMAIN_CBID_PATTERN.matcher(host.get());
    if (domainMatcher.find()) {
      return Optional.of(Ascii.toLowerCase(domainMatcher.group(CBID_KEY)));
    }

    return Optional.empty();
  }

  // Check if CBID exists in a DNS lookup request. Domain name from DNS protocol doesn't contain
  // "http" prefix nor path.
  public static Optional<String> extractCbidFromDomainInDnsProtocol(String domainString) {
    Matcher domainMatcher = DOMAIN_CBID_PATTERN.matcher(domainString);
    if (domainMatcher.find()) {
      return Optional.of(Ascii.toLowerCase(domainMatcher.group(CBID_KEY)));
    }
    return Optional.empty();
  }

  /**
   * Extracts a CBID from the request target (path, query string and fragment) of the given URL.
   *
   * <p>The CBID may appear anywhere in the request target as long as it is not part of a longer
   * hexadecimal string, e.g. all of {@code /<cbid>}, {@code /img%20<cbid>}, {@code /<cbid>.png} and
   * {@code /redirect?to=http://tcs/<cbid>} yield the CBID. The request target is inspected both in
   * its raw and in its percent-decoded form, so percent-encoded CBIDs are found as well.
   *
   * <p>The host part of the URL is deliberately ignored here, use {@link
   * #extractCbidFromDomainInHttpProtocol} for that.
   *
   * @param urlString the URL to extract the CBID from.
   * @return the lowercased CBID, or {@link Optional#empty} if the URL doesn't contain one.
   */
  public static Optional<String> extractCbidFromUrl(String urlString) {
    for (String candidate : cbidSearchCandidates(stripSchemeAndAuthority(urlString))) {
      Matcher urlMatcher = URL_CBID_PATTERN.matcher(candidate);
      if (urlMatcher.find()) {
        return Optional.of(Ascii.toLowerCase(urlMatcher.group(CBID_KEY)));
      }
    }
    return Optional.empty();
  }

  /**
   * Returns the host of the given URL.
   *
   * <p>Falls back to manual parsing when {@link URI} rejects the URL. Out-of-band payloads
   * frequently contain characters that are illegal in an RFC 2396 URI (e.g. spaces, <code>'{'</code> or
   * {@code '|'}), and those requests must still be attributed to their CBID.
   */
  private static Optional<String> extractHost(String urlString) {
    try {
      String host = new URI(urlString).getHost();
      if (!Strings.isNullOrEmpty(host)) {
        return Optional.of(host);
      }
    } catch (URISyntaxException e) {
      logger.atInfo().withCause(e).log(
          "Unable to parse url '%s' as a URI, falling back to manual host extraction", urlString);
    }

    int authorityStart = authorityStartIndex(urlString);
    if (authorityStart < 0) {
      return Optional.empty();
    }
    int authorityEnd = requestTargetStartIndex(urlString, authorityStart);
    String authority =
        authorityEnd < 0
            ? urlString.substring(authorityStart)
            : urlString.substring(authorityStart, authorityEnd);
    // Drop the userinfo component so that a CBID in there isn't mistaken for the host.
    String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
    return hostAndPort.isEmpty() ? Optional.empty() : Optional.of(hostAndPort);
  }

  /** Returns the request target (path, query string and fragment) of the given URL. */
  private static String stripSchemeAndAuthority(String urlString) {
    int authorityStart = authorityStartIndex(urlString);
    if (authorityStart < 0) {
      // Relative URLs (e.g. the request target of an origin-form HTTP request) have no authority.
      return urlString;
    }
    int requestTargetStart = requestTargetStartIndex(urlString, authorityStart);
    return requestTargetStart < 0 ? "" : urlString.substring(requestTargetStart);
  }

  private static int authorityStartIndex(String urlString) {
    int schemeSeparator = urlString.indexOf("://");
    return schemeSeparator < 0 ? -1 : schemeSeparator + "://".length();
  }

  private static int requestTargetStartIndex(String urlString, int fromIndex) {
    for (int i = fromIndex; i < urlString.length(); i++) {
      char c = urlString.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns the strings to search for a CBID: the percent-decoded variants of the given request
   * target first, the raw request target last.
   *
   * <p>Decoded variants take precedence because the hexadecimal digits of a percent escape would
   * otherwise be mistaken for the beginning of a CBID (e.g. {@code %62%30<54 hex chars>}). The raw
   * variant is still considered as a last resort, since decoding can break a CBID apart when a
   * percent escape overlaps it.
   */
  private static ImmutableList<String> cbidSearchCandidates(String requestTarget) {
    ImmutableList.Builder<String> candidates = ImmutableList.builder();
    String current = requestTarget;
    for (int i = 0; i < MAX_DECODING_ROUNDS; i++) {
      Optional<String> decoded = percentDecode(current);
      if (decoded.isEmpty() || decoded.get().equals(current)) {
        break;
      }
      candidates.add(decoded.get());
      current = decoded.get();
    }
    return candidates.add(requestTarget).build();
  }

  private static Optional<String> percentDecode(String value) {
    try {
      return Optional.of(URLDecoder.decode(value, UTF_8));
    } catch (IllegalArgumentException e) {
      // Malformed percent-encoding, the raw variant is all we can look at.
      return Optional.empty();
    }
  }
}
