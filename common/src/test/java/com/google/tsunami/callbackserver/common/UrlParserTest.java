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
import static com.google.tsunami.callbackserver.common.UrlParser.getQueryParameter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link UrlParser}. */
@RunWith(JUnit4.class)
public final class UrlParserTest {

  @Test
  public void getQueryParameter_whenKeyMatchesUrl_returnsParameterValue() {
    assertThat(getQueryParameter("http://localhost/?key=value", "key")).hasValue("value");
  }

  @Test
  public void getQueryParameter_whenMultipleMatchingKeys_returnsFirstNonEmptyParameterValue() {
    assertThat(getQueryParameter("http://localhost/?key&key=&key=value&key=value2", "key"))
        .hasValue("value");
  }

  @Test
  public void getQueryParameter_whenKeyMatchesUrl_returnsPercentDecodedParameterValue() {
    assertThat(getQueryParameter("http://localhost/?key=%76alue", "key")).hasValue("value");
  }

  @Test
  public void getQueryParameter_whenPercentEncodedKeyMatchesUrl_returnsParameterValue() {
    assertThat(getQueryParameter("http://localhost/?%6bey=value", "key")).hasValue("value");
  }

  @Test
  public void getQueryParameter_whenNoMatchingKey_returnsEmpty() {
    assertThat(getQueryParameter("http://localhost/?key=value", "nomatch")).isEmpty();
  }

  @Test
  public void getQueryParameter_whenNoQueryParameters_returnsEmpty() {
    assertThat(getQueryParameter("http://localhost", "key")).isEmpty();
  }

  @Test
  public void getQueryParameter_whenInvalidUrl_returnsEmpty() {
    assertThat(getQueryParameter("123://localhost", "key")).isEmpty();
  }
}
