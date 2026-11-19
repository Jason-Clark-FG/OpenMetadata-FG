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
import java.util.LinkedHashMap;
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

  private static final String APPLIED_TO = "om:appliedTo";
  private static final Comparator<Reachable> REACHABLE_ORDER =
      Comparator.comparing(Reachable::relationship).thenComparing(Reachable::direction);

  private final GraphReader graph;
  private final OntologyVocabulary vocabulary;
  private final NodeIris nodeIris;
  private final Set<String> bookkeeping;
  private final GraphMemories memories;

  public NodeCardReader(
      final GraphReader graph, final OntologyVocabulary vocabulary, final String baseUri) {
    this.graph = Objects.requireNonNull(graph);
    this.vocabulary = Objects.requireNonNull(vocabulary);
    this.nodeIris = new NodeIris(baseUri);
    this.bookkeeping = Bookkeeping.namesIn(vocabulary);
    this.memories = new GraphMemories(graph);
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
    final Map<String, MemoryListing> anchored = memories.onCards(anchoring(reachable));
    return nodes.stream()
        .map(
            node ->
                card(
                    node,
                    new Read(
                        facts.get(node),
                        values.getOrDefault(node, List.of()),
                        reachable.getOrDefault(node, List.of()),
                        anchored.get(node))))
        .toList();
  }

  /** What the reads gave for one node. */
  private record Read(
      CardFacts facts, List<CardValue> values, List<Reachable> reachable, MemoryListing memories) {}

  /**
   * The nodes with an {@code om:appliedTo} edge, the only ones a memory can be anchored to, so a
   * batch without one costs no further read.
   */
  private static List<String> anchoring(final Map<String, List<Reachable>> reachable) {
    return reachable.entrySet().stream()
        .filter(
            entry ->
                entry.getValue().stream()
                    .anyMatch(
                        offer ->
                            APPLIED_TO.equals(offer.relationship())
                                && offer.direction() == Direction.OUTGOING))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }

  private NodeCard card(final String node, final Read read) {
    final List<CardValue> values = read.values();
    final List<Reachable> reachable = read.reachable();
    final boolean inGraph = read.facts() != null || !values.isEmpty() || !reachable.isEmpty();
    final CardFacts known = read.facts() == null ? new CardFacts() : read.facts();
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
        read.memories(),
        kept.get(false),
        kept.get(true).stream().map(CardValue::property).toList(),
        reachable,
        inGraph ? vocabulary.notCoveredFor(known.types()) : List.of(),
        inGraph);
  }

  /** A catalog entity's type from its IRI; an ontology term's kind from its declared types. */
  String entityType(final String node, final Set<String> typeIris) {
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
    final Map<String, Map<RelationshipKey, Long>> totals = new LinkedHashMap<>();
    final Map<RelationshipKey, Map<String, Long>> statuses = new HashMap<>();
    for (final QuerySolution row :
        SparqlRows.parse(
            graph.select(ExplorationQueries.reachable(nodes, vocabulary.statusProperties())))) {
      final RelationshipKey key = RelationshipKey.of(row, vocabulary);
      final long count = row.getLiteral(ExplorationQueries.COUNT).getLong();
      if (row.contains(ExplorationQueries.STATUS)) {
        statuses.computeIfAbsent(key, ignored -> new HashMap<>()).put(statusKey(row), count);
      } else {
        totals.computeIfAbsent(key.node(), ignored -> new LinkedHashMap<>()).put(key, count);
      }
    }
    final Map<String, List<Reachable>> reachable = new HashMap<>();
    totals.forEach(
        (node, counts) ->
            reachable.put(
                node,
                counts.entrySet().stream()
                    .map(
                        entry ->
                            new Reachable(
                                entry.getKey().relationship(),
                                entry.getKey().direction(),
                                entry.getValue(),
                                statuses.getOrDefault(entry.getKey(), Map.of())))
                    .sorted(REACHABLE_ORDER)
                    .toList()));
    return reachable;
  }

  private String statusKey(final QuerySolution row) {
    return Reachable.statusKey(
        vocabulary.compact(SparqlRows.iri(row, ExplorationQueries.STATUS)),
        row.getLiteral(ExplorationQueries.VALUE).getString());
  }

  private record RelationshipKey(String node, String relationship, Direction direction) {
    static RelationshipKey of(final QuerySolution row, final OntologyVocabulary vocabulary) {
      return new RelationshipKey(
          SparqlRows.iri(row, ExplorationQueries.NODE),
          vocabulary.compact(SparqlRows.iri(row, ExplorationQueries.PREDICATE)),
          Direction.fromValue(row.getLiteral(ExplorationQueries.DIRECTION).getString()));
    }
  }
}
