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
package org.openmetadata.mcp.tools;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openmetadata.service.rdf.explore.SearchHit;

/**
 * Which hits of one find are deprecated, recorded from the search rows they came from: an asset
 * whose {@code entityStatus} is Deprecated, or a column whose table is one of those. A column
 * document carries neither its own status nor its table's, so a column is judged only when its
 * table came back from the same find.
 */
final class HitDeprecation {

  private static final String ENTITY_STATUS = "entityStatus";
  private static final String DEPRECATED = "Deprecated";
  private static final String TABLE = "table";
  private static final String ID = "id";

  private final Set<UUID> deprecated = new HashSet<>();
  private final Map<UUID, UUID> tableOfColumn = new HashMap<>();

  /** Records the rows of one search response, whose entity id is under {@code idField}. */
  void record(final List<?> rows, final String idField) {
    rows.stream()
        .filter(Map.class::isInstance)
        .map(row -> (Map<?, ?>) row)
        .forEach(row -> record(row, idField));
  }

  private void record(final Map<?, ?> row, final String idField) {
    uuid(row.get(idField))
        .ifPresent(
            id -> {
              if (DEPRECATED.equals(row.get(ENTITY_STATUS))) {
                deprecated.add(id);
              }
              if (row.get(TABLE) instanceof Map<?, ?> table) {
                uuid(table.get(ID)).ifPresent(tableId -> tableOfColumn.put(id, tableId));
              }
            });
  }

  /** Within each kind, the hits that are not deprecated, in order, ahead of those that are. */
  List<SearchHit> last(final List<SearchHit> ranked) {
    final Map<String, Deque<SearchHit>> byKind =
        ranked.stream()
            .collect(
                Collectors.groupingBy(
                    SearchHit::entityType,
                    LinkedHashMap::new,
                    Collectors.collectingAndThen(
                        Collectors.toList(),
                        hits ->
                            new ArrayDeque<>(
                                hits.stream()
                                    .sorted(Comparator.comparing(this::isDeprecated))
                                    .toList()))));
    return ranked.stream().map(hit -> byKind.get(hit.entityType()).poll()).toList();
  }

  boolean isDeprecated(final SearchHit hit) {
    return hit.id() != null
        && (deprecated.contains(hit.id()) || deprecated.contains(tableOfColumn.get(hit.id())));
  }

  private static Optional<UUID> uuid(final Object value) {
    try {
      return Optional.ofNullable(value).map(Object::toString).map(UUID::fromString);
    } catch (IllegalArgumentException notAnId) {
      return Optional.empty();
    }
  }
}
