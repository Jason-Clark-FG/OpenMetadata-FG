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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openmetadata.schema.utils.JsonUtils;

/** The once-per-response {@code notCovered} list of each kind of node among a response's cards. */
final class NotCoveredGroups {

  private NotCoveredGroups() {}

  static List<NotCoveredGroup> of(final List<NodeCard> cards) {
    final Map<List<String>, NotCoveredGroup> groups = new LinkedHashMap<>();
    cards.stream()
        .filter(card -> !card.notCovered().isEmpty())
        .forEach(card -> groups.putIfAbsent(card.types(), groupOf(card)));
    return List.copyOf(groups.values());
  }

  /** The characters a card's group adds to a response the first time its types appear. */
  static long chars(final NodeCard card) {
    return card.notCovered().isEmpty() ? 0 : JsonUtils.pojoToJson(groupOf(card)).length() + 1L;
  }

  private static NotCoveredGroup groupOf(final NodeCard card) {
    return new NotCoveredGroup(card.types(), card.notCovered());
  }
}
