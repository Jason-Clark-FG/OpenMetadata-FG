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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.RDFNode;
import org.openmetadata.schema.utils.JsonUtils;

/**
 * Follows named relationships from a set of nodes. Every far node of every relationship asked is
 * listed in short form first; full cards follow, one page at a time, for as many as fit.
 */
public final class GraphExpansion {

  /** The most far nodes one relationship lists; a response rarely holds more. */
  static final int LIST_CAP = 200;

  private static final int GROUP_CHARS = 160;
  private static final int REST_CHARS = 400;

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

  /**
   * Every group listed, as far as {@code maxChars} holds them, then up to {@code limit} rows of
   * cards that fit what is left.
   */
  public ExpandPage expand(final ExpandRequest request) {
    final List<GroupKey> keys = groupKeys(request);
    final Map<GroupKey, List<FarNode>> listed = fitLists(keys, readListed(request), request);
    final long listChars = listChars(keys, listed);
    final List<ExpansionRow> rows = readRows(request);
    final List<ExpansionRow> candidates = rows.subList(0, Math.min(rows.size(), request.limit()));
    final Map<String, NodeCard> cardsById = cardsOf(candidates);
    final List<ExpansionRow> page =
        candidates.subList(
            0,
            PageBudget.rowsThatFit(
                candidates, cardsById, descriptions, request.maxChars() - listChars));
    final List<NodeCard> pageCards =
        page.stream()
            .map(ExpansionRow::other)
            .distinct()
            .map(cardsById::get)
            .filter(Objects::nonNull)
            .toList();
    final Integer nextOffset = rows.size() > page.size() ? request.offset() + page.size() : null;
    final List<ExpansionGroup> groups =
        groups(keys, readTotals(request), listed, carded(page), nextOffset, request.offset());
    return new ExpandPage(
        groups,
        pageCards,
        descriptions.describe(names(groups, pageCards)),
        NotCoveredGroups.of(pageCards),
        nextOffset);
  }

  private List<GroupKey> groupKeys(final ExpandRequest request) {
    final List<GroupKey> keys = new ArrayList<>();
    for (final String node : new TreeSet<>(request.nodes())) {
      for (final String relationship : new TreeSet<>(request.relationships())) {
        request.directions().stream()
            .sorted(Comparator.comparing(Direction::value))
            .forEach(
                direction ->
                    keys.add(new GroupKey(node, vocabulary.compact(relationship), direction)));
      }
    }
    return keys;
  }

  private List<ExpansionGroup> groups(
      final List<GroupKey> keys,
      final Map<GroupKey, Long> totals,
      final Map<GroupKey, List<FarNode>> listed,
      final Map<GroupKey, List<String>> carded,
      final Integer nextOffset,
      final int offset) {
    return keys.stream()
        .map(
            key -> {
              final long total = totals.getOrDefault(key, 0L);
              final List<FarNode> shortList = listed.getOrDefault(key, List.of());
              final List<String> withCards = carded.getOrDefault(key, List.of());
              return new ExpansionGroup(
                  key.node(),
                  key.relationship(),
                  key.direction(),
                  total,
                  shortList,
                  withCards,
                  ExpansionRest.of(
                      key,
                      vocabulary.expand(key.relationship()),
                      total,
                      shortList.size(),
                      withCards.size(),
                      nextOffset,
                      offset));
            })
        .toList();
  }

  private static Map<GroupKey, List<String>> carded(final List<ExpansionRow> page) {
    final Map<GroupKey, List<String>> carded = new LinkedHashMap<>();
    page.forEach(row -> carded.computeIfAbsent(row.key(), key -> new ArrayList<>()).add(row.other));
    return carded;
  }

  private static List<String> names(
      final List<ExpansionGroup> groups, final List<NodeCard> pageCards) {
    return Stream.of(
            groups.stream().map(ExpansionGroup::relationship),
            groups.stream()
                .flatMap(group -> group.listed().stream())
                .flatMap(far -> far.status().keySet().stream()),
            pageCards.stream().flatMap(card -> PropertyDescriptions.namesOf(card).stream()))
        .flatMap(Function.identity())
        .distinct()
        .toList();
  }

  /**
   * Each group's far nodes in turn, one at a time, while they fit the response: every group gets
   * its first far node before any gets its second.
   */
  private Map<GroupKey, List<FarNode>> fitLists(
      final List<GroupKey> keys,
      final Map<GroupKey, List<String>> ids,
      final ExpandRequest request) {
    final Map<String, FarNode> shortForms =
        shortForms(ids.values().stream().flatMap(List::stream).distinct().toList());
    final Map<GroupKey, List<FarNode>> listed = new LinkedHashMap<>();
    keys.forEach(key -> listed.put(key, new ArrayList<>()));
    long used = (long) keys.size() * (GROUP_CHARS + REST_CHARS);
    final int longest = ids.values().stream().mapToInt(List::size).max().orElse(0);
    for (int index = 0; index < longest; index++) {
      for (final GroupKey key : keys) {
        final List<String> groupIds = ids.getOrDefault(key, List.of());
        if (index < groupIds.size()) {
          final FarNode far = shortForms.get(groupIds.get(index));
          final long cost = JsonUtils.pojoToJson(far).length() + 1L;
          if (used + cost > request.maxChars()) {
            return listed;
          }
          used += cost;
          listed.get(key).add(far);
        }
      }
    }
    return listed;
  }

  private static long listChars(
      final List<GroupKey> keys, final Map<GroupKey, List<FarNode>> listed) {
    return keys.size() * (long) (GROUP_CHARS + REST_CHARS)
        + listed.values().stream()
            .flatMap(List::stream)
            .mapToLong(far -> JsonUtils.pojoToJson(far).length() + 1L)
            .sum();
  }

  private Map<String, FarNode> shortForms(final List<String> ids) {
    final List<String> queryable = ids.stream().filter(ExplorationQueries::isQueryable).toList();
    final Map<String, ShortForm> read = new HashMap<>();
    if (!queryable.isEmpty()) {
      SparqlRows.parse(
              graph.select(ExplorationQueries.farNodes(queryable, vocabulary.statusProperties())))
          .forEach(
              row ->
                  read.computeIfAbsent(
                          SparqlRows.iri(row, ExplorationQueries.OTHER), ignored -> new ShortForm())
                      .add(row, vocabulary));
    }
    return ids.stream()
        .collect(
            Collectors.toMap(
                Function.identity(),
                id -> read.getOrDefault(id, new ShortForm()).farNode(id, cards, vocabulary),
                (first, second) -> first,
                LinkedHashMap::new));
  }

  private Map<GroupKey, List<String>> readListed(final ExpandRequest request) {
    final Map<GroupKey, List<String>> ids = new LinkedHashMap<>();
    SparqlRows.parse(graph.select(ExplorationQueries.listedFarNodes(request, LIST_CAP)))
        .forEach(
            row ->
                ids.computeIfAbsent(key(row), ignored -> new ArrayList<>())
                    .add(SparqlRows.iri(row, ExplorationQueries.OTHER)));
    ids.values().forEach(list -> list.sort(Comparator.naturalOrder()));
    return ids;
  }

  private Map<GroupKey, Long> readTotals(final ExpandRequest request) {
    return SparqlRows.parse(graph.select(ExplorationQueries.expansionTotals(request))).stream()
        .collect(
            Collectors.toMap(this::key, row -> row.getLiteral(ExplorationQueries.COUNT).getLong()));
  }

  private GroupKey key(final QuerySolution row) {
    return new GroupKey(
        SparqlRows.iri(row, ExplorationQueries.NODE),
        vocabulary.compact(SparqlRows.iri(row, ExplorationQueries.PREDICATE)),
        Direction.fromValue(row.getLiteral(ExplorationQueries.DIRECTION).getString()));
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

    GroupKey key() {
      return new GroupKey(node, relationship, direction);
    }
  }

  record GroupKey(String node, String relationship, Direction direction) {}

  /** What the short-form read says about one far node. */
  private static final class ShortForm {
    private final Map<String, String> fields = new HashMap<>();
    private final Set<String> types = new TreeSet<>();
    private final Map<String, Object> status = new TreeMap<>();

    void add(final QuerySolution row, final OntologyVocabulary vocabulary) {
      final String field = row.getLiteral(ExplorationQueries.FIELD).getString();
      final RDFNode value = row.get(ExplorationQueries.VALUE);
      switch (field) {
        case "type" -> types.add(value.asResource().getURI());
        case "status" -> status.put(
            vocabulary.compact(SparqlRows.iri(row, ExplorationQueries.STATUS)),
            value.isLiteral() ? value.asLiteral().getLexicalForm() : Boolean.TRUE);
        default -> fields.putIfAbsent(
            field, value.isLiteral() ? value.asLiteral().getLexicalForm() : value.toString());
      }
    }

    FarNode farNode(
        final String id, final NodeCardReader cards, final OntologyVocabulary vocabulary) {
      final String name =
          Stream.of("display", "label", "fqn")
              .map(fields::get)
              .filter(Objects::nonNull)
              .findFirst()
              .orElse(null);
      final String type =
          Optional.ofNullable(cards.entityType(id, types))
              .orElse(types.isEmpty() ? null : vocabulary.compact(types.iterator().next()));
      return new FarNode(id, name, type, status);
    }
  }
}
