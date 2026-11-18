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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.openmetadata.schema.utils.JsonUtils;

/**
 * What each relationship named in a response is, sent once per response: cards and groups refer to
 * a relationship by the name {@code expand} takes, and the response maps each name to the label
 * and comment the ontology gives it.
 */
final class RelationshipGlossary {

  private static final int ENTRY_SEPARATORS = 4;

  private final OntologyVocabulary vocabulary;

  RelationshipGlossary(final OntologyVocabulary vocabulary) {
    this.vocabulary = Objects.requireNonNull(vocabulary);
  }

  Map<String, RelationshipAbout> describe(final Collection<String> names) {
    return names.stream()
        .distinct()
        .collect(
            Collectors.toMap(
                Function.identity(),
                vocabulary::aboutNamed,
                (first, second) -> first,
                TreeMap::new));
  }

  Map<String, RelationshipAbout> describeCards(final List<NodeCard> cards) {
    return describe(cards.stream().flatMap(card -> namesOf(card).stream()).toList());
  }

  /** The characters a name's entry adds to a response, as {@code "name":{...},}. */
  long entryChars(final String name) {
    return name.length()
        + ENTRY_SEPARATORS
        + JsonUtils.pojoToJson(vocabulary.aboutNamed(name)).length();
  }

  static Set<String> namesOf(final NodeCard card) {
    return card.reachable().stream().map(Reachable::relationship).collect(Collectors.toSet());
  }
}
