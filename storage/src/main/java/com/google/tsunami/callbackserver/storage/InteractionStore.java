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
package com.google.tsunami.callbackserver.storage;

import com.google.common.collect.ImmutableList;
import com.google.tsunami.callbackserver.proto.Interaction;

/** Interface for TCS interaction backend storage. */
public interface InteractionStore {

  /** Type of an TCS OOB interaction. */
  enum InteractionType {
    HTTP_INTERACTION,
    DNS_INTERACTION,
  }

  /**
   * Adds an interaction into the storage backend.
   *
   * @param cbid the callback ID assigned to the interaction.
   * @param type type of the interaction.
   */
  void add(String cbid, InteractionType type);

  /**
   * Retrieves all interactions associated with the given cbid.
   *
   * @param cbid the callback ID assigned to the interaction.
   * @return all interactions associated with the cbid.
   */
  ImmutableList<Interaction> get(String cbid);

  /**
   * Deletes an interaction from the storage backend.
   *
   * @param cbid the callback ID assigned to the interaction
   */
  void delete(String cbid);
}
