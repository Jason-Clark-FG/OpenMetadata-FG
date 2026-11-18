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
import java.util.stream.Collectors;
import org.openmetadata.service.rdf.RdfIriValidator;

/**
 * The SELECTs behind cards and expansion. Each UNION branch repeats its {@code VALUES} block so
 * the engine joins the bound nodes into the triple pattern instead of scanning every triple and
 * joining afterwards. No query names a graph: the asserted-only read path chooses the dataset.
 * Card reads carry no row limit, so an oversized answer fails on the read path's output bound
 * instead of coming back silently short.
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
        }
        """
            .formatted(iris(nodes), iris(FactPredicate.iris()));
  }

  /**
   * One row per node and property whose values are literals, other than those a card's header
   * shows: how many values, the first and last in text order, the longest length and a datatype.
   * Values are cut at the card's limit here, so a long literal never crosses the read bound whole.
   */
  static String values(final Collection<String> nodes) {
    return PREFIXES
        + """
        SELECT ?node ?predicate (COUNT(DISTINCT ?value) AS ?count) (MIN(?shown) AS ?first)
               (MAX(?shown) AS ?last) (MAX(?length) AS ?longest)
               (SAMPLE(DATATYPE(?value)) AS ?datatype) WHERE {
          VALUES ?node { %s }
          ?node ?predicate ?value .
          FILTER(isLiteral(?value))
          FILTER(?predicate NOT IN (%s))
          BIND(STRLEN(STR(?value)) AS ?length)
          BIND(SUBSTR(STR(?value), 1, %d) AS ?shown)
        } GROUP BY ?node ?predicate
        """
            .formatted(
                iris(nodes),
                String.join(
                    ", ", FactPredicate.iris().stream().map(ExplorationQueries::iri).toList()),
                CardValue.VALUE_LIMIT);
  }

  static final String STATUS = "status";

  static String reachable(final Collection<String> nodes) {
    return reachable(nodes, Map.of());
  }

  /**
   * One row per node, direction and relationship with the count of distinct nodes on the other
   * side; with {@code statusProperties} (status properties by class), also one row per status
   * property and value of those nodes, {@code ?status} bound, counting the nodes that have it. A
   * relationship they have counts under the empty value. One read either way.
   */
  static String reachable(
      final Collection<String> nodes, final Map<String, List<String>> statusProperties) {
    final String values = iris(nodes);
    final String totals =
        """
        SELECT ?node ?direction ?predicate (COUNT(DISTINCT ?other) AS ?count) WHERE {
          { VALUES ?node { %1$s }
            ?node ?predicate ?other . FILTER(isIRI(?other)) BIND("outgoing" AS ?direction) }
          UNION
          { VALUES ?node { %1$s }
            ?other ?predicate ?node . BIND("incoming" AS ?direction) }
          FILTER(?predicate != rdf:type)
        } GROUP BY ?node ?direction ?predicate
        """
            .formatted(values);
    return PREFIXES
        + (statusProperties.isEmpty()
            ? totals
            : """
              SELECT ?node ?direction ?predicate ?status ?value ?count WHERE {
                { %s }
                UNION
                { %s }
              }
              """
                .formatted(totals, statuses(values, statusPairs(statusProperties))));
  }

  private static String statuses(final String nodes, final String pairs) {
    return """
        SELECT ?node ?direction ?predicate ?status ?value (COUNT(DISTINCT ?other) AS ?count) WHERE {
          { VALUES ?node { %1$s } VALUES (?class ?status) { %2$s }
            ?node ?predicate ?other . ?other a ?class ; ?status ?state .
            BIND("outgoing" AS ?direction) }
          UNION
          { VALUES ?node { %1$s } VALUES (?class ?status) { %2$s }
            ?other ?predicate ?node . ?other a ?class ; ?status ?state .
            BIND("incoming" AS ?direction) }
          FILTER(?predicate != rdf:type)
          BIND(IF(isLiteral(?state), STR(?state), "") AS ?value)
        } GROUP BY ?node ?direction ?predicate ?status ?value
        """
        .formatted(nodes, pairs);
  }

  private static String statusPairs(final Map<String, List<String>> statusProperties) {
    return statusProperties.entrySet().stream()
        .flatMap(
            entry ->
                entry.getValue().stream()
                    .map(property -> "(%s %s)".formatted(iri(entry.getKey()), iri(property))))
        .collect(Collectors.joining(" "));
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

  /**
   * The one rule for every IRI the server interpolates: an absolute http(s) IRI that {@code
   * java.net.URI} parses, with nothing that could close the IRI or open a pattern. Server-built
   * queries skip the agent query validator, so this check stands on its own.
   */
  static boolean isQueryable(final String value) {
    return value != null && value.equals(RdfIriValidator.validateEntityIri(value));
  }

  static String iri(final String value) {
    if (!isQueryable(value)) {
      throw new IllegalArgumentException(
          "'%s' is not an absolute http(s) IRI, so it cannot be placed in a graph query"
              .formatted(value));
    }
    return "<" + value + ">";
  }
}
