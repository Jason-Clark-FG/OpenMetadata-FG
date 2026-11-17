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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.jena.query.QuerySolution;

/** Builds node cards from two graph reads per batch: one for facts, one for reachability. */
public final class NodeCardReader {

  private static final Comparator<Reachable> REACHABLE_ORDER =
      Comparator.comparing(Reachable::relationship).thenComparing(Reachable::direction);

  private final GraphReader graph;
  private final OntologyVocabulary vocabulary;
  private final NodeIris nodeIris;

  public NodeCardReader(
      final GraphReader graph, final OntologyVocabulary vocabulary, final String baseUri) {
    this.graph = Objects.requireNonNull(graph);
    this.vocabulary = Objects.requireNonNull(vocabulary);
    this.nodeIris = new NodeIris(baseUri);
  }

  /** One card per distinct node, in the order the nodes were given. */
  public List<NodeCard> read(final List<String> nodes) {
    final List<String> distinct = nodes.stream().distinct().toList();
    return distinct.isEmpty() ? List.of() : cardsFor(distinct);
  }

  private List<NodeCard> cardsFor(final List<String> nodes) {
    final Map<String, CardFacts> facts = readFacts(nodes);
    final Map<String, List<Reachable>> reachable = readReachable(nodes);
    return nodes.stream()
        .map(node -> card(node, facts.get(node), reachable.getOrDefault(node, List.of())))
        .toList();
  }

  private NodeCard card(final String node, final CardFacts facts, final List<Reachable> reachable) {
    final boolean inGraph = facts != null || !reachable.isEmpty();
    final CardFacts known = facts == null ? new CardFacts() : facts;
    return new NodeCard(
        node,
        nodeIris.entityType(node),
        known.fullyQualifiedName(),
        known.label(),
        known.types().stream().map(vocabulary::compact).sorted().toList(),
        known.keyFacts(),
        reachable,
        inGraph ? vocabulary.notCoveredFor(known.types()) : List.of(),
        inGraph);
  }

  private Map<String, CardFacts> readFacts(final List<String> nodes) {
    final Map<String, CardFacts> facts = new HashMap<>();
    for (final QuerySolution row :
        SparqlRows.parse(graph.select(ExplorationQueries.facts(nodes)))) {
      final CardFacts nodeFacts =
          facts.computeIfAbsent(
              SparqlRows.iri(row, ExplorationQueries.NODE), ignored -> new CardFacts());
      FactPredicate.of(SparqlRows.iri(row, ExplorationQueries.PREDICATE))
          .ifPresent(predicate -> predicate.record(nodeFacts, FactValue.of(row)));
    }
    return facts;
  }

  private Map<String, List<Reachable>> readReachable(final List<String> nodes) {
    return SparqlRows.parse(graph.select(ExplorationQueries.reachable(nodes))).stream()
        .collect(
            Collectors.groupingBy(
                row -> SparqlRows.iri(row, ExplorationQueries.NODE),
                Collectors.mapping(
                    this::reachable,
                    Collectors.collectingAndThen(
                        Collectors.toList(),
                        entries -> entries.stream().sorted(REACHABLE_ORDER).toList()))));
  }

  private Reachable reachable(final QuerySolution row) {
    final String predicate = SparqlRows.iri(row, ExplorationQueries.PREDICATE);
    return new Reachable(
        vocabulary.compact(predicate),
        Direction.fromValue(row.getLiteral(ExplorationQueries.DIRECTION).getString()),
        row.getLiteral(ExplorationQueries.COUNT).getLong(),
        vocabulary.about(predicate));
  }
}
