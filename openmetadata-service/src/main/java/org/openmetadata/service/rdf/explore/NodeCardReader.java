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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jena.query.QuerySolution;

/**
 * Builds node cards from three graph reads per batch: the header facts, every other value stored on
 * the nodes, and reachability.
 */
public final class NodeCardReader {

  private static final Comparator<Reachable> REACHABLE_ORDER =
      Comparator.comparing(Reachable::relationship).thenComparing(Reachable::direction);

  private final GraphReader graph;
  private final OntologyVocabulary vocabulary;
  private final NodeIris nodeIris;
  private final Set<String> bookkeeping;

  public NodeCardReader(
      final GraphReader graph, final OntologyVocabulary vocabulary, final String baseUri) {
    this.graph = Objects.requireNonNull(graph);
    this.vocabulary = Objects.requireNonNull(vocabulary);
    this.nodeIris = new NodeIris(baseUri);
    this.bookkeeping = Bookkeeping.namesIn(vocabulary);
  }

  /**
   * One card per distinct node, in the order the nodes were given. A node whose IRI is not an
   * absolute http(s) IRI, such as a {@code urn:} concept, cannot be placed in a query and gets no
   * card.
   */
  public List<NodeCard> read(final List<String> nodes) {
    final List<String> distinct =
        nodes.stream().distinct().filter(ExplorationQueries::isQueryable).toList();
    return distinct.isEmpty() ? List.of() : cardsFor(distinct);
  }

  private List<NodeCard> cardsFor(final List<String> nodes) {
    final Map<String, CardFacts> facts = readFacts(nodes);
    final Map<String, List<CardValue>> values =
        CardValues.byNode(
            SparqlRows.parse(graph.select(ExplorationQueries.values(nodes))), vocabulary);
    final Map<String, List<Reachable>> reachable = readReachable(nodes);
    return nodes.stream()
        .map(
            node ->
                card(
                    node,
                    facts.get(node),
                    values.getOrDefault(node, List.of()),
                    reachable.getOrDefault(node, List.of())))
        .toList();
  }

  private NodeCard card(
      final String node,
      final CardFacts facts,
      final List<CardValue> values,
      final List<Reachable> reachable) {
    final boolean inGraph = facts != null || !values.isEmpty() || !reachable.isEmpty();
    final CardFacts known = facts == null ? new CardFacts() : facts;
    final List<String> types = known.types().stream().map(vocabulary::compact).sorted().toList();
    final Map<Boolean, List<CardValue>> kept =
        values.stream()
            .collect(Collectors.partitioningBy(value -> bookkeeping.contains(value.property())));
    return new NodeCard(
        node,
        entityType(node, known.types()),
        known.fullyQualifiedName(),
        Optional.ofNullable(known.label()).orElseGet(() -> DerivedLabel.of(types, values)),
        types,
        known.keyFacts(),
        kept.get(false),
        kept.get(true).stream().map(CardValue::property).toList(),
        reachable,
        inGraph ? vocabulary.notCoveredFor(known.types()) : List.of(),
        inGraph);
  }

  /** A catalog entity's type from its IRI; an ontology term's kind from its declared types. */
  private String entityType(final String node, final Set<String> typeIris) {
    return Optional.ofNullable(nodeIris.entityType(node))
        .or(() -> OntologyTermKind.declaredBy(typeIris).map(OntologyTermKind::entityType))
        .orElse(null);
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
        row.getLiteral(ExplorationQueries.COUNT).getLong());
  }
}
