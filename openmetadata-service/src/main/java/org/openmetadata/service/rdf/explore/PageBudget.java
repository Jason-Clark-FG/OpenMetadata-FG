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
 * How many leading rows of an expansion page, or cards of a find, fit a serialized size. Each far
 * node's card, and each relationship's description, is counted once, and each row is charged as if
 * it opened its own group, so the estimate is an upper bound. The first row is always kept, so
 * paging never stalls.
 */
final class PageBudget {

  private static final int ENVELOPE_CHARS = 64;
  private static final int GROUP_CHARS = 64;

  private PageBudget() {}

  static int rowsThatFit(
      final List<GraphExpansion.ExpansionRow> rows,
      final Map<String, NodeCard> cards,
      final RelationshipGlossary glossary,
      final long maxChars) {
    final Set<String> counted = new HashSet<>();
    final Set<String> described = new HashSet<>();
    long used = ENVELOPE_CHARS;
    int kept = 0;
    for (final GraphExpansion.ExpansionRow row : rows) {
      final NodeCard card = counted.contains(row.other()) ? null : cards.get(row.other());
      final Set<String> names = namesOf(row, card);
      final long cost = rowChars(row, card) + newEntryChars(names, described, glossary);
      if (kept > 0 && used + cost > maxChars) {
        break;
      }
      used += cost;
      counted.add(row.other());
      described.addAll(names);
      kept++;
    }
    return kept;
  }

  /** How many leading cards fit {@code maxChars} serialized; the first card is always kept. */
  static int cardsThatFit(
      final List<NodeCard> cards, final RelationshipGlossary glossary, final long maxChars) {
    final Set<String> described = new HashSet<>();
    long used = ENVELOPE_CHARS;
    int kept = 0;
    for (final NodeCard card : cards) {
      final Set<String> names = RelationshipGlossary.namesOf(card);
      final long cost = cardChars(card) + newEntryChars(names, described, glossary);
      if (kept > 0 && used + cost > maxChars) {
        break;
      }
      used += cost;
      described.addAll(names);
      kept++;
    }
    return kept;
  }

  private static Set<String> namesOf(final GraphExpansion.ExpansionRow row, final NodeCard card) {
    final Set<String> names = new HashSet<>(Set.of(row.relationship()));
    if (card != null) {
      names.addAll(RelationshipGlossary.namesOf(card));
    }
    return names;
  }

  private static long newEntryChars(
      final Set<String> names, final Set<String> described, final RelationshipGlossary glossary) {
    return names.stream()
        .filter(name -> !described.contains(name))
        .mapToLong(glossary::entryChars)
        .sum();
  }

  private static long rowChars(final GraphExpansion.ExpansionRow row, final NodeCard card) {
    final long group = GROUP_CHARS + row.node().length() + row.relationship().length();
    final long reference = row.other().length() + 3L;
    return group + reference + (card == null ? 0 : cardChars(card));
  }

  private static long cardChars(final NodeCard card) {
    return JsonUtils.pojoToJson(card).length() + 1L;
  }
}
