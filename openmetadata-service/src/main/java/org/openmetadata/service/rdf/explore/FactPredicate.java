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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The predicates a card reads its identity and key facts from, and where each value goes. */
enum FactPredicate {
  TYPE("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", CardFacts::addType),
  LABEL("http://www.w3.org/2000/01/rdf-schema#label", CardFacts::setLabel),
  DISPLAY_NAME("http://www.w3.org/2004/02/skos/core#prefLabel", CardFacts::setDisplayName),
  FULLY_QUALIFIED_NAME(Names.OM + "fullyQualifiedName", CardFacts::setFullyQualifiedName),
  DESCRIPTION("http://purl.org/dc/terms/description", CardFacts::setDescription),
  TIER(Names.OM + "hasTier", CardFacts::setTier),
  CERTIFICATION(Names.OM + "certificationLevel", CardFacts::setCertification),
  STATUS(Names.OM + "entityStatus", CardFacts::setStatus),
  OWNER(Names.OM + "hasOwner", CardFacts::addOwner),
  SERVICE(Names.OM + "belongsToService", CardFacts::setService),
  USAGE_DAILY(Names.OM + "usageDailyCount", CardFacts::setDailyUsage),
  USAGE_WEEKLY(Names.OM + "usageWeeklyCount", CardFacts::setWeeklyUsage),
  USAGE_MONTHLY(Names.OM + "usageMonthlyCount", CardFacts::setMonthlyUsage),
  USAGE_WEEKLY_PERCENTILE(Names.OM + "usageWeeklyPercentile", CardFacts::setWeeklyPercentile);

  private static final Map<String, FactPredicate> BY_IRI =
      Arrays.stream(values())
          .collect(Collectors.toUnmodifiableMap(p -> p.iri, Function.identity()));

  private final String iri;
  private final BiConsumer<CardFacts, FactValue> recorder;

  FactPredicate(final String iri, final BiConsumer<CardFacts, FactValue> recorder) {
    this.iri = iri;
    this.recorder = recorder;
  }

  static List<String> iris() {
    return Arrays.stream(values()).map(predicate -> predicate.iri).toList();
  }

  static Optional<FactPredicate> of(final String iri) {
    return Optional.ofNullable(BY_IRI.get(iri));
  }

  void record(final CardFacts facts, final FactValue value) {
    recorder.accept(facts, value);
  }

  private static final class Names {
    private static final String OM = "https://open-metadata.org/ontology/";
  }
}
