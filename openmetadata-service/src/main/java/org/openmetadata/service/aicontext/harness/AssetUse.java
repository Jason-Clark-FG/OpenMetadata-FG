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
package org.openmetadata.service.aicontext.harness;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A data asset that answers a glossary term or metric, as the search index holds it: what the agent
 * needs to name it, and the two facts that rank it.
 *
 * @param certification the certification tag FQN, or null when the asset is not certified
 */
record AssetUse(
    String fullyQualifiedName, String entityType, String certification, boolean deprecated) {
  static final int MAX_PER_ITEM = 3;

  /** Certified assets first, deprecated ones never, at most {@link #MAX_PER_ITEM}. */
  static List<AssetUse> choose(final List<AssetUse> candidates) {
    return candidates.stream()
        .filter(use -> !use.deprecated())
        .filter(use -> !nullOrEmpty(use.fullyQualifiedName()))
        .sorted(Comparator.comparing(use -> use.certification() == null))
        .distinct()
        .limit(MAX_PER_ITEM)
        .toList();
  }

  /** The line appended to the term's or metric's card, or empty when no asset answers it. */
  static String render(final List<AssetUse> uses) {
    return uses.isEmpty()
        ? ""
        : "Use: " + uses.stream().map(AssetUse::describe).collect(Collectors.joining(", "));
  }

  private String describe() {
    final String qualifier =
        certification == null
            ? entityType
            : entityType + ", " + certification.substring(certification.lastIndexOf('.') + 1);
    return "`" + fullyQualifiedName + "` (" + qualifier + ")";
  }
}
