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

import com.google.common.flogger.GoogleLogger;
import com.google.common.net.HostAndPort;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility class for processing CBIDs. */
public final class CbidProcessor {
  private static final String CBID_KEY = "CBID";
  private static final String DOMAIN_CBID_PATTERN_STRING =
      String.format("\\.?(?<%s>[a-fA-F0-9]{56})\\.", CBID_KEY);
  private static final Pattern DOMAIN_CBID_PATTERN = Pattern.compile(DOMAIN_CBID_PATTERN_STRING);
  private static final String PATH_CBID_PATTERN_STRING =
      String.format("^/(?<%s>[a-fA-F0-9]{56})$", CBID_KEY);
  private static final Pattern PATH_CBID_PATTERN = Pattern.compile(PATH_CBID_PATTERN_STRING);
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

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

  public static Optional<String> extractCbidFromDomain(String domainString) {
    Matcher domainMatcher = DOMAIN_CBID_PATTERN.matcher(domainString);
    if (domainMatcher.find()) {
      return Optional.of(domainMatcher.group(CBID_KEY));
    }
    return Optional.empty();
  }

  public static Optional<String> extractCbidFromUrl(String urlString) {
    try {
      String cbid = new URI(urlString).getPath();
      Matcher pathMatcher = PATH_CBID_PATTERN.matcher(cbid);
      if (pathMatcher.find()) {
        return Optional.of(pathMatcher.group(CBID_KEY));
      }
    } catch (URISyntaxException e) {
      logger.atWarning().withCause(e).log("Unable to parse url: %s", urlString);
    }
    return Optional.empty();
  }
}
