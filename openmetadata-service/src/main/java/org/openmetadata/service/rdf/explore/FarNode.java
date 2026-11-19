/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.openmetadata.service.rdf.explore;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.TreeMap;

/**
 * A far node of an expansion in short form: the id {@code expand} and {@code cite} take, its name,
 * its type, and the values of the status properties the ontology marks for its class ({@code
 * om:statusProperty}): a value, or {@code true} for a relationship it has.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record FarNode(String id, String name, String type, Map<String, Object> status) {

  public FarNode {
    status = status == null ? Map.of() : Map.copyOf(status);
  }

  private static final int ID_ENTRY_CHARS = "{\"id\":\"\"},".length();

  /** The fewest characters a listing entry for {@code id} can take: the id alone. */
  static long smallestChars(final String id) {
    return (long) id.length() + ID_ENTRY_CHARS;
  }

  /** The status values in name order, as a listing shows them. */
  @Override
  public Map<String, Object> status() {
    return new TreeMap<>(status);
  }
}
