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

import java.util.List;
import java.util.Set;

/**
 * Which relationships to follow from which nodes. {@code relationships} are full IRIs; {@code
 * offset} counts the (node, relationship, direction, far node) rows earlier pages returned. A page
 * ends early rather than serialize past {@code maxChars}, and always carries at least one row. The
 * caller's {@code preferences}, sent with it, are recorded as shown.
 */
public record ExpandRequest(
    List<String> nodes,
    List<String> relationships,
    Set<Direction> directions,
    int limit,
    int offset,
    long maxChars,
    List<MemoryEntry> preferences) {

  public ExpandRequest {
    nodes = List.copyOf(nodes);
    relationships = List.copyOf(relationships);
    directions = Set.copyOf(directions);
    preferences = List.copyOf(preferences);
  }

  /** An expansion sent without the caller's preferences. */
  public ExpandRequest(
      final List<String> nodes,
      final List<String> relationships,
      final Set<Direction> directions,
      final int limit,
      final int offset,
      final long maxChars) {
    this(nodes, relationships, directions, limit, offset, maxChars, List.of());
  }
}
