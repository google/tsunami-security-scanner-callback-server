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

import com.google.common.io.BaseEncoding;
import com.google.inject.AbstractModule;
import org.bouncycastle.jcajce.provider.digest.SHA3;

/** Sha3CbidGenerator which uses 8 bytes random secret and SHA3 hashing. */
public final class Sha3CbidGenerator implements CbidGenerator {
  @Override
  public String generate(String secretString) {
    SHA3.DigestSHA3 digestSHA3 = new SHA3.Digest224();
    byte[] digest = digestSHA3.digest(secretString.getBytes(UTF_8));
    return BaseEncoding.base16().lowerCase().encode(digest);
  }

  public static AbstractModule getModule() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        bind(CbidGenerator.class).to(Sha3CbidGenerator.class);
      }
    };
  }
}
