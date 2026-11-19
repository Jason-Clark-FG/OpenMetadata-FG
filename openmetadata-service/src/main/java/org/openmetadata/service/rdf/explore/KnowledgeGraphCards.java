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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the {@link KnowledgeGraphCard} of a call: what it served, or the whole run from its trace.
 * A card shows at most {@value #MAX_NODES} nodes, {@value #MAX_EDGES} relationships, {@value
 * #MAX_GROUPS} groups, {@value #MAX_MEMORIES} memories and {@value #MAX_STEPS} steps.
 */
public final class KnowledgeGraphCards {

  public static final int MAX_NODES = 40;
  public static final int MAX_EDGES = 60;
  public static final int MAX_GROUPS = 10;
  public static final int MAX_MEMORIES = 10;
  public static final int MAX_STEPS = 20;

  private static final String OFFERED = "offered";
  private static final String EXPLORED = "explored";
  private static final String CITED = "cited";
  private static final String TESTS = "om:testsEntity";
  private static final String FAILED = "om:latestResultStatus=Failed";
  private static final String INCIDENT = "om:hasOpenIncident";
  private static final String RESULT_STATUS = "om:latestResultStatus";
  private static final String FAILED_VALUE = "Failed";
  private static final int LINE_CHARS = 80;

  private KnowledgeGraphCards() {}

  /** find and expand: the cards served, the far nodes listed without one, the memories shown. */
  public static KnowledgeGraphCard ofCall(
      final Operation operation,
      final UUID run,
      final List<NodeCard> cards,
      final List<ExpansionGroup> groups,
      final List<KnowledgeGraphCard.Memory> shown) {
    final Set<String> carded = cards.stream().map(NodeCard::id).collect(Collectors.toSet());
    final List<KnowledgeGraphCard.Node> listed =
        groups.stream()
            .flatMap(group -> group.listed().stream())
            .filter(far -> !carded.contains(far.id()))
            .collect(
                Collectors.toMap(
                    FarNode::id, KnowledgeGraphCards::node, (a, b) -> a, LinkedHashMap::new))
            .values()
            .stream()
            .toList();
    final List<KnowledgeGraphCard.Node> nodes =
        Stream.concat(cards.stream().map(card -> node(card, EXPLORED)), listed.stream()).toList();
    final List<KnowledgeGraphCard.Memory> memories =
        Stream.concat(cards.stream().flatMap(KnowledgeGraphCards::memoriesOf), shown.stream())
            .toList();
    return new KnowledgeGraphCard(
        operation.value(),
        run.toString(),
        new KnowledgeGraphCard.Counts(listed.size(), cards.size(), 0, 1),
        head(nodes, MAX_NODES),
        more(nodes, MAX_NODES),
        List.of(),
        0,
        head(groups.stream().map(KnowledgeGraphCards::group).toList(), MAX_GROUPS),
        head(memories, MAX_MEMORIES),
        more(memories, MAX_MEMORIES),
        List.of(),
        0,
        null,
        more(nodes, MAX_NODES) > 0
            ? "%d more nodes are not shown.".formatted(more(nodes, MAX_NODES))
            : null);
  }

  /** query: how many rows came back, and the query's first line. */
  public static KnowledgeGraphCard ofQuery(
      final UUID run, final String sparql, final int returned, final int total) {
    return new KnowledgeGraphCard(
        Operation.QUERY.value(),
        run.toString(),
        new KnowledgeGraphCard.Counts(0, 0, 0, 1),
        List.of(),
        0,
        List.of(),
        0,
        List.of(),
        List.of(),
        0,
        List.of(),
        0,
        shorten(sparql.replaceAll("(?s)PREFIX[^\\n]*\\n", "").strip(), 400),
        returned < total
            ? "%d of %d rows were returned.".formatted(returned, total)
            : "%d rows.".formatted(total));
  }

  /**
   * cite and trace: the run's nodes, cited first, then explored, then only offered (seen in a
   * listing, never as a card); the relationships its expansions followed; its steps. {@code
   * trusted} are cards read for the cited nodes, which carry their trust and memories.
   */
  public static KnowledgeGraphCard ofRun(
      final Operation operation, final RunTrace trace, final List<NodeCard> trusted) {
    final Map<String, NodeCard> byId =
        trusted.stream().collect(Collectors.toMap(NodeCard::id, Function.identity(), (a, b) -> a));
    final Set<String> offering =
        trace.edges().stream().map(TraceEdge::node).collect(Collectors.toSet());
    final List<KnowledgeGraphCard.Node> nodes =
        trace.nodes().stream()
            .map(node -> runNode(node, stateOf(node, offering), byId.get(node.id())))
            .sorted(Comparator.comparingInt(KnowledgeGraphCards::rank))
            .toList();
    final List<KnowledgeGraphCard.Edge> edges =
        trace.edges().stream()
            .filter(edge -> edge.other() != null)
            .map(KnowledgeGraphCards::edge)
            .distinct()
            .toList();
    final List<KnowledgeGraphCard.Memory> memories =
        trusted.stream().flatMap(KnowledgeGraphCards::memoriesOf).toList();
    final List<KnowledgeGraphCard.Step> steps =
        trace.steps().stream().map(KnowledgeGraphCards::step).toList();
    return new KnowledgeGraphCard(
        operation.value(),
        trace.run().toString(),
        counts(nodes, trace.steps().size()),
        head(nodes, MAX_NODES),
        more(nodes, MAX_NODES),
        head(edges, MAX_EDGES),
        more(edges, MAX_EDGES),
        List.of(),
        head(memories, MAX_MEMORIES),
        more(memories, MAX_MEMORIES),
        head(steps, MAX_STEPS),
        more(steps, MAX_STEPS),
        null,
        more(nodes, MAX_NODES) > 0
            ? "%d more nodes the run reached are not shown.".formatted(more(nodes, MAX_NODES))
            : null);
  }

  private static String stateOf(final TraceNode node, final Set<String> offering) {
    final String state;
    if (node.citedStep() != null) {
      state = CITED;
    } else if (offering.contains(node.id())) {
      state = EXPLORED;
    } else {
      state = OFFERED;
    }
    return state;
  }

  private static int rank(final KnowledgeGraphCard.Node node) {
    return switch (node.state()) {
      case CITED -> 0;
      case EXPLORED -> 1;
      default -> 2;
    };
  }

  private static KnowledgeGraphCard.Counts counts(
      final List<KnowledgeGraphCard.Node> nodes, final int calls) {
    final Map<String, Long> byState =
        nodes.stream()
            .collect(Collectors.groupingBy(KnowledgeGraphCard.Node::state, Collectors.counting()));
    return new KnowledgeGraphCard.Counts(
        byState.getOrDefault(OFFERED, 0L).intValue(),
        byState.getOrDefault(EXPLORED, 0L).intValue(),
        byState.getOrDefault(CITED, 0L).intValue(),
        calls);
  }

  private static KnowledgeGraphCard.Node runNode(
      final TraceNode node, final String state, final NodeCard card) {
    return card == null
        ? new KnowledgeGraphCard.Node(
            node.id(),
            name(node.label(), node.id()),
            node.entityType(),
            state,
            null,
            null,
            null,
            null,
            null)
        : withState(node(card, state), state);
  }

  private static KnowledgeGraphCard.Node withState(
      final KnowledgeGraphCard.Node node, final String state) {
    return new KnowledgeGraphCard.Node(
        node.id(),
        node.name(),
        node.type(),
        state,
        node.tier(),
        node.certification(),
        node.status(),
        node.failingTests(),
        node.openIncidents());
  }

  static KnowledgeGraphCard.Node node(final NodeCard card, final String state) {
    final KeyFacts facts = card.facts();
    return new KnowledgeGraphCard.Node(
        card.id(),
        name(card.label(), card.id()),
        card.entityType() != null
            ? card.entityType()
            : card.types().stream().findFirst().orElse(null),
        state,
        facts == null ? null : facts.tier(),
        facts == null ? null : facts.certification(),
        facts == null ? null : facts.status(),
        positive(statusCount(card, TESTS, Direction.INCOMING, FAILED)),
        positive(
            statusCount(card, TESTS, Direction.INCOMING, INCIDENT)
                + reachableCount(card, INCIDENT, Direction.OUTGOING)));
  }

  private static KnowledgeGraphCard.Node node(final FarNode far) {
    final Map<String, Object> status = far.status();
    return new KnowledgeGraphCard.Node(
        far.id(),
        name(far.name(), far.id()),
        far.type(),
        OFFERED,
        null,
        null,
        null,
        FAILED_VALUE.equals(status.get(RESULT_STATUS)) ? Long.valueOf(1) : null,
        status.containsKey(INCIDENT) ? Long.valueOf(1) : null);
  }

  private static long statusCount(
      final NodeCard card, final String relationship, final Direction direction, final String key) {
    return card.reachable().stream()
        .filter(
            entry -> relationship.equals(entry.relationship()) && entry.direction() == direction)
        .mapToLong(entry -> entry.status().getOrDefault(key, 0L))
        .sum();
  }

  private static long reachableCount(
      final NodeCard card, final String relationship, final Direction direction) {
    return card.reachable().stream()
        .filter(
            entry -> relationship.equals(entry.relationship()) && entry.direction() == direction)
        .mapToLong(Reachable::count)
        .sum();
  }

  private static Long positive(final long value) {
    return value > 0 ? value : null;
  }

  private static Stream<KnowledgeGraphCard.Memory> memoriesOf(final NodeCard card) {
    return card.memories() == null
        ? Stream.empty()
        : card.memories().shown().stream().map(memory -> memory(memory, card.id(), "card"));
  }

  /** A memory a call showed, with where it was shown. */
  public static KnowledgeGraphCard.Memory memory(
      final MemoryEntry memory, final String about, final String origin) {
    return new KnowledgeGraphCard.Memory(
        memory.id(), memory.title(), memory.stage(), memory.awaitingReview(), about, origin);
  }

  private static KnowledgeGraphCard.Group group(final ExpansionGroup group) {
    return new KnowledgeGraphCard.Group(
        group.node(),
        group.relationship(),
        group.direction().value(),
        group.total(),
        group.listed().stream().map(FarNode::id).toList());
  }

  private static KnowledgeGraphCard.Edge edge(final TraceEdge edge) {
    final boolean incoming = edge.direction() == Direction.INCOMING;
    return new KnowledgeGraphCard.Edge(
        incoming ? edge.other() : edge.node(),
        incoming ? edge.node() : edge.other(),
        edge.relationship(),
        edge.citedStep() != null ? CITED : EXPLORED);
  }

  private static KnowledgeGraphCard.Step step(final TraceStep step) {
    final RunStep.Input input = step.input();
    final String asked =
        switch (step.operation()) {
          case FIND -> "“" + Objects.toString(input.question(), "") + "”";
          case EXPAND -> String.join(", ", input.relationships())
              + " from "
              + input.nodes().size()
              + " node(s)";
          case QUERY -> Objects.toString(input.sparql(), "").replaceAll("\\s+", " ");
          case CITE -> input.nodes().size() + " node(s) cited";
          case TRACE -> "trace";
        };
    return new KnowledgeGraphCard.Step(
        step.sequence(),
        step.operation().value(),
        shorten(asked, LINE_CHARS) + " → " + step.nodes().size() + " node(s)");
  }

  private static String name(final String label, final String id) {
    return label != null ? label : id.substring(id.lastIndexOf('/') + 1);
  }

  private static String shorten(final String text, final int limit) {
    return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
  }

  private static <T> List<T> head(final List<T> items, final int max) {
    return items.size() <= max ? List.copyOf(items) : List.copyOf(items.subList(0, max));
  }

  private static <T> int more(final List<T> items, final int max) {
    return Math.max(0, items.size() - max);
  }
}
