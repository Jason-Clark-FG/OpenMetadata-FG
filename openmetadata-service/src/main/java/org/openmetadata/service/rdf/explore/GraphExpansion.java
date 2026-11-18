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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.jena.query.QuerySolution;

/** Follows named relationships from a set of nodes, one page of far nodes at a time. */
public final class GraphExpansion {

  private final GraphReader graph;
  private final NodeCardReader cards;
  private final OntologyVocabulary vocabulary;
  private final PropertyDescriptions descriptions;

  public GraphExpansion(
      final GraphReader graph, final NodeCardReader cards, final OntologyVocabulary vocabulary) {
    this.graph = Objects.requireNonNull(graph);
    this.cards = Objects.requireNonNull(cards);
    this.vocabulary = Objects.requireNonNull(vocabulary);
    this.descriptions = new PropertyDescriptions(vocabulary);
  }

  /** Up to {@code limit} rows, fewer when their cards would not fit {@code maxChars}. */
  public ExpandPage expand(final ExpandRequest request) {
    final List<ExpansionRow> rows = readRows(request);
    final List<ExpansionRow> candidates = rows.subList(0, Math.min(rows.size(), request.limit()));
    final Map<String, NodeCard> cardsById = cardsOf(candidates);
    final List<ExpansionRow> page =
        candidates.subList(
            0, PageBudget.rowsThatFit(candidates, cardsById, descriptions, request.maxChars()));
    final List<NodeCard> pageCards =
        page.stream()
            .map(ExpansionRow::other)
            .distinct()
            .map(cardsById::get)
            .filter(Objects::nonNull)
            .toList();
    return ExpansionRow.toPage(
        page,
        pageCards,
        descriptions.describe(relationshipNames(page, pageCards)),
        rows.size() > page.size() ? request.offset() + page.size() : null);
  }

  private static List<String> relationshipNames(
      final List<ExpansionRow> page, final List<NodeCard> pageCards) {
    return Stream.concat(
            page.stream().map(ExpansionRow::relationship),
            pageCards.stream().flatMap(card -> PropertyDescriptions.namesOf(card).stream()))
        .toList();
  }

  private Map<String, NodeCard> cardsOf(final List<ExpansionRow> rows) {
    return cards.read(rows.stream().map(ExpansionRow::other).toList()).stream()
        .collect(Collectors.toMap(NodeCard::id, Function.identity()));
  }

  private List<ExpansionRow> readRows(final ExpandRequest request) {
    return SparqlRows.parse(graph.select(ExplorationQueries.expansion(request))).stream()
        .map(this::row)
        .toList();
  }

  private ExpansionRow row(final QuerySolution solution) {
    return new ExpansionRow(
        SparqlRows.iri(solution, ExplorationQueries.NODE),
        vocabulary.compact(SparqlRows.iri(solution, ExplorationQueries.PREDICATE)),
        Direction.fromValue(solution.getLiteral(ExplorationQueries.DIRECTION).getString()),
        SparqlRows.iri(solution, ExplorationQueries.OTHER));
  }

  /** One (node, relationship, direction, far node) solution, in page order. */
  record ExpansionRow(String node, String relationship, Direction direction, String other) {

    private GroupKey key() {
      return new GroupKey(node, relationship, direction);
    }

    static ExpandPage toPage(
        final List<ExpansionRow> rows,
        final List<NodeCard> cards,
        final Map<String, RelationshipAbout> properties,
        final Integer nextOffset) {
      final Map<GroupKey, List<String>> groups = new LinkedHashMap<>();
      rows.forEach(
          row -> groups.computeIfAbsent(row.key(), key -> new ArrayList<>()).add(row.other));
      return new ExpandPage(
          groups.entrySet().stream().map(entry -> entry.getKey().group(entry.getValue())).toList(),
          cards,
          properties,
          NotCoveredGroups.of(cards),
          nextOffset);
    }
  }

  private record GroupKey(String node, String relationship, Direction direction) {
    ExpansionGroup group(final List<String> nodes) {
      return new ExpansionGroup(node, relationship, direction, nodes);
    }
  }
}
