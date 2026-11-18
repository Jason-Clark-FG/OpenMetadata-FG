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

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.openmetadata.service.rdf.explore.SearchHit;

/**
 * Orders catalog hits by how many of the question's words found them, then by the best position
 * any search gave them, then by when they were first found.
 */
final class CatalogRanking {

  private final Map<SearchHit, Rank> ranks = new LinkedHashMap<>();

  void add(final List<SearchHit> hits, final Set<Integer> words) {
    for (int position = 0; position < hits.size(); position++) {
      final int at = position;
      ranks.computeIfAbsent(hits.get(position), hit -> new Rank()).record(words, at);
    }
  }

  List<SearchHit> ordered() {
    return ranks.entrySet().stream()
        .sorted(
            Comparator.comparing(
                    (Map.Entry<SearchHit, Rank> entry) -> -entry.getValue().words.size())
                .thenComparingInt(entry -> entry.getValue().bestPosition))
        .map(Map.Entry::getKey)
        .toList();
  }

  private static final class Rank {
    private final Set<Integer> words = new HashSet<>();
    private int bestPosition = Integer.MAX_VALUE;

    void record(final Set<Integer> found, final int position) {
      words.addAll(found);
      bestPosition = Math.min(bestPosition, position);
    }
  }
}
