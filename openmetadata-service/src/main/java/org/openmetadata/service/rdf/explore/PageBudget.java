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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.openmetadata.schema.utils.JsonUtils;

/**
 * How many leading rows of an expansion page fit a serialized size. Each far node's card is
 * counted once, and each row is charged as if it opened its own group, so the estimate is an upper
 * bound. The first row is always kept, so paging never stalls.
 */
final class PageBudget {

  private static final int ENVELOPE_CHARS = 64;
  private static final int GROUP_CHARS = 64;

  private PageBudget() {}

  static int rowsThatFit(
      final List<GraphExpansion.ExpansionRow> rows,
      final Map<String, NodeCard> cards,
      final long maxChars) {
    final Set<String> counted = new HashSet<>();
    long used = ENVELOPE_CHARS;
    int kept = 0;
    for (final GraphExpansion.ExpansionRow row : rows) {
      final long cost = cost(row, cards, counted);
      if (kept > 0 && used + cost > maxChars) {
        break;
      }
      used += cost;
      counted.add(row.other());
      kept++;
    }
    return kept;
  }

  private static long cost(
      final GraphExpansion.ExpansionRow row,
      final Map<String, NodeCard> cards,
      final Set<String> counted) {
    final long group = GROUP_CHARS + row.node().length() + row.relationship().length();
    final long reference = row.other().length() + 3L;
    final long card =
        counted.contains(row.other())
            ? 0
            : JsonUtils.pojoToJson(cards.get(row.other())).length() + 1L;
    return group + reference + card;
  }
}
