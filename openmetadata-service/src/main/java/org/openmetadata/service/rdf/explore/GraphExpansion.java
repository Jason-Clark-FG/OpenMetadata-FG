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
import org.apache.jena.query.QuerySolution;

/** Follows named relationships from a set of nodes, one page of far nodes at a time. */
public final class GraphExpansion {

  private final GraphReader graph;
  private final NodeCardReader cards;
  private final OntologyVocabulary vocabulary;

  public GraphExpansion(
      final GraphReader graph, final NodeCardReader cards, final OntologyVocabulary vocabulary) {
    this.graph = Objects.requireNonNull(graph);
    this.cards = Objects.requireNonNull(cards);
    this.vocabulary = Objects.requireNonNull(vocabulary);
  }

  public ExpandPage expand(final ExpandRequest request) {
    final List<ExpansionRow> rows = readRows(request);
    final boolean hasMore = rows.size() > request.limit();
    final List<ExpansionRow> page = hasMore ? rows.subList(0, request.limit()) : rows;
    return ExpansionRow.toPage(
        page,
        cards.read(page.stream().map(ExpansionRow::other).toList()),
        hasMore ? request.offset() + page.size() : null);
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
        final List<ExpansionRow> rows, final List<NodeCard> cards, final Integer nextOffset) {
      final Map<GroupKey, List<String>> groups = new LinkedHashMap<>();
      rows.forEach(
          row -> groups.computeIfAbsent(row.key(), key -> new ArrayList<>()).add(row.other));
      return new ExpandPage(
          groups.entrySet().stream().map(entry -> entry.getKey().group(entry.getValue())).toList(),
          cards,
          nextOffset);
    }
  }

  private record GroupKey(String node, String relationship, Direction direction) {
    ExpansionGroup group(final List<String> nodes) {
      return new ExpansionGroup(node, relationship, direction, nodes);
    }
  }
}
