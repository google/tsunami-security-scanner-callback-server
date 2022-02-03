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

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Optional;

/** Utilities for parsing URLs. */
public final class UrlParser {

  private UrlParser() {}

  /**
   * Get the first <em>non-empty</em> value for the given query parameter in the URL.
   *
   * @param uri the target URL.
   * @param key the target query parameter key.
   * @return the first non-empty value for the given key, or empty {@link Optional} if the key is
   *     not found.
   */
  public static Optional<String> getQueryParameter(String uri, String key) {
    try {
      String rawQuery = new URI(uri).getRawQuery();
      if (isNullOrEmpty(rawQuery)) {
        return Optional.empty();
      }

      int start = 0;
      while (start <= rawQuery.length()) {
        // Find the end of the current parameter.
        int ampersandIndex = rawQuery.indexOf('&', start);
        if (ampersandIndex == -1) {
          ampersandIndex = rawQuery.length();
        }
        int equalsIndex = rawQuery.indexOf('=', start);
        if (equalsIndex > ampersandIndex) {
          // Equal is in the next parameter, so this parameter has no value.
          equalsIndex = -1;
        }
        int paramNameEndIndex = (equalsIndex == -1) ? ampersandIndex : equalsIndex;
        String name = urlDecode(rawQuery, start, paramNameEndIndex);
        String value =
            equalsIndex == -1 ? "" : urlDecode(rawQuery, equalsIndex + 1, ampersandIndex);
        if (name.equals(key) && !isNullOrEmpty(value)) {
          return Optional.of(urlDecode(rawQuery, equalsIndex + 1, ampersandIndex));
        }
        start = ampersandIndex + 1;
      }
      return Optional.empty();
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
  }

  private static String urlDecode(String str, int start, int end) {
    try {
      return URLDecoder.decode(str.substring(start, end), UTF_8);
    } catch (IllegalArgumentException e) {
      return str.substring(start, end);
    }
  }
}
