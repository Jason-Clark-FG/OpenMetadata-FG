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
 * One populated relationship of a node: the name a caller passes back to {@code expand}, which end
 * the node is on and how many distinct nodes are on the other side. When the ontology gives those
 * nodes' class a status property ({@code om:statusProperty}), {@code status} counts them by it:
 * {@code property=value} for a value, {@code property} alone for a relationship they have. What the
 * relationship and the status properties are comes once per response, in {@code properties}.
 */
public record Reachable(
    String relationship,
    Direction direction,
    long count,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Long> status) {

  private static final String VALUE_SEPARATOR = "=";

  public Reachable {
    status = Map.copyOf(status);
  }

  public Reachable(final String relationship, final Direction direction, final long count) {
    this(relationship, direction, count, Map.of());
  }

  /** The status counts in name order, as a card shows them. */
  @Override
  public Map<String, Long> status() {
    return new TreeMap<>(status);
  }

  static String statusKey(final String property, final String value) {
    return value.isEmpty() ? property : property + VALUE_SEPARATOR + value;
  }

  static String statusProperty(final String key) {
    final int separator = key.indexOf(VALUE_SEPARATOR);
    return separator < 0 ? key : key.substring(0, separator);
  }
}
