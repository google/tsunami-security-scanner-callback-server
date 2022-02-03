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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Sha3CbidGenerator}. */
@RunWith(JUnit4.class)
public final class Sha3CbidGeneratorTest {
  private static final String SECRET = "a3d9ed89deadbeef";
  private static final String CBID = "04041e8898e739ca33a250923e24f59ca41a8373f8cf6a45a1275f3b";

  @Test
  public void generate_always_returnsCbid() {
    String cbid = new Sha3CbidGenerator().generate(SECRET);

    assertThat(cbid).isEqualTo(CBID);
  }
}
