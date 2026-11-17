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
import java.util.stream.Collectors;
import org.openmetadata.service.rdf.RdfIriValidator;

/**
 * The SELECTs behind cards and expansion. Each UNION branch repeats its {@code VALUES} block so
 * the engine joins the bound nodes into the triple pattern instead of scanning every triple and
 * joining afterwards. No query names a graph: the asserted-only read path chooses the dataset.
 */
final class ExplorationQueries {

  static final String NODE = "node";
  static final String PREDICATE = "predicate";
  static final String VALUE = "value";
  static final String VALUE_LABEL = "valueLabel";
  static final String VALUE_DISPLAY = "valueDisplay";
  static final String VALUE_TAG = "valueTag";
  static final String DIRECTION = "direction";
  static final String COUNT = "count";
  static final String OTHER = "other";

  /** Far more rows than any node's facts or populated relationships produce. */
  private static final int ROW_CEILING = 5_000;

  private static final String PREFIXES =
      """
      PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      PREFIX om: <https://open-metadata.org/ontology/>
      """;

  private ExplorationQueries() {}

  static String facts(final Collection<String> nodes) {
    return PREFIXES
        + """
        SELECT ?node ?predicate ?value ?valueLabel ?valueDisplay ?valueTag WHERE {
          VALUES ?node { %s }
          VALUES ?predicate { %s }
          ?node ?predicate ?value .
          OPTIONAL { ?value rdfs:label ?valueLabel }
          OPTIONAL { ?value skos:prefLabel ?valueDisplay }
          OPTIONAL { ?value om:tagFQN ?valueTag }
        } LIMIT %d
        """
            .formatted(iris(nodes), iris(FactPredicate.iris()), ROW_CEILING);
  }

  static String reachable(final Collection<String> nodes) {
    final String values = iris(nodes);
    return PREFIXES
        + """
        SELECT ?node ?direction ?predicate (COUNT(DISTINCT ?other) AS ?count) WHERE {
          { VALUES ?node { %1$s }
            ?node ?predicate ?other . FILTER(isIRI(?other)) BIND("outgoing" AS ?direction) }
          UNION
          { VALUES ?node { %1$s }
            ?other ?predicate ?node . BIND("incoming" AS ?direction) }
          FILTER(?predicate != rdf:type)
        } GROUP BY ?node ?direction ?predicate LIMIT %2$d
        """
            .formatted(values, ROW_CEILING);
  }

  /** One row per far node, ordered so pages never overlap; one row beyond the page shows more. */
  static String expansion(final ExpandRequest request) {
    final String branches =
        request.directions().stream()
            .sorted()
            .map(direction -> expansionBranch(request, direction))
            .collect(Collectors.joining("\n  UNION\n  "));
    return PREFIXES
        + """
        SELECT ?node ?predicate ?direction ?other WHERE {
          %s
        } ORDER BY ?node ?predicate ?direction ?other LIMIT %d OFFSET %d
        """
            .formatted(branches, request.limit() + 1, request.offset());
  }

  private static String expansionBranch(final ExpandRequest request, final Direction direction) {
    final String pattern =
        direction == Direction.OUTGOING
            ? "?node ?predicate ?other . FILTER(isIRI(?other))"
            : "?other ?predicate ?node .";
    return "{ VALUES ?node { %s } VALUES ?predicate { %s } %s BIND(\"%s\" AS ?direction) }"
        .formatted(
            iris(request.nodes()), iris(request.relationships()), pattern, direction.value());
  }

  static String iris(final Collection<String> iris) {
    return iris.stream().map(ExplorationQueries::iri).collect(Collectors.joining(" "));
  }

  static String iri(final String value) {
    final String safe = RdfIriValidator.sanitizeStoredIri(value);
    if (safe == null) {
      throw new IllegalArgumentException("'%s' is not a node id".formatted(value));
    }
    return "<" + safe + ">";
  }
}
