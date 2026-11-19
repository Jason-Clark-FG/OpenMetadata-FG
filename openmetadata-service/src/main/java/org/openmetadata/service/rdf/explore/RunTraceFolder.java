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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Folds the recorded steps of a run into one subgraph. A node is listed once, at the step that
 * first returned it; an offered relationship becomes explored once an expansion from its node
 * followed it in the same direction, at the step recorded as {@code exploredStep}. A cite step
 * marks the nodes and edges it accepted, and the offers those edges belong to, as cited.
 */
final class RunTraceFolder {

  private final Map<String, TraceNode> nodes = new LinkedHashMap<>();
  private final Map<RelationshipKey, RunStep.Edge> offers = new LinkedHashMap<>();
  private final Map<RelationshipKey, Integer> offeredAt = new LinkedHashMap<>();
  private final Map<RunStep.Edge, Integer> explored = new LinkedHashMap<>();
  private final Map<RelationshipKey, Integer> openedAt = new HashMap<>();
  private final Map<String, Integer> citedNodes = new HashMap<>();
  private final Map<RunStep.Edge, Integer> citedEdges = new HashMap<>();
  private final Map<RelationshipKey, Integer> citedOffers = new HashMap<>();
  private final List<TraceStep> steps = new ArrayList<>();

  static RunTrace fold(final UUID run, final List<RunStep> recorded) {
    final RunTraceFolder folder = new RunTraceFolder();
    recorded.forEach(folder::add);
    return new RunTrace(run, folder.nodes(), folder.edges(), folder.steps);
  }

  private void add(final RunStep step) {
    final int sequence = steps.size() + 1;
    steps.add(
        new TraceStep(
            sequence,
            step.operation(),
            step.at(),
            step.input(),
            step.nodes().stream().map(RunStep.Node::id).toList()));
    if (step.operation() == Operation.CITE) {
      cite(step, sequence);
    } else {
      step.nodes().forEach(node -> addNode(node, sequence));
      step.edges().forEach(edge -> addEdge(edge, sequence));
    }
  }

  private void cite(final RunStep step, final int sequence) {
    step.input().nodes().forEach(node -> citedNodes.putIfAbsent(node, sequence));
    step.edges()
        .forEach(
            edge -> {
              citedEdges.putIfAbsent(edge, sequence);
              citedOffers.putIfAbsent(RelationshipKey.of(edge), sequence);
            });
  }

  private void addNode(final RunStep.Node node, final int sequence) {
    nodes.putIfAbsent(node.id(), traceNode(node, sequence));
    node.offers().stream()
        .map(
            offer ->
                new RunStep.Edge(
                    node.id(), offer.relationship(), offer.direction(), null, offer.count()))
        .forEach(edge -> addEdge(edge, sequence));
  }

  private void addEdge(final RunStep.Edge edge, final int sequence) {
    final RelationshipKey key = RelationshipKey.of(edge);
    if (edge.other() == null) {
      offers.put(key, edge);
      offeredAt.putIfAbsent(key, sequence);
    } else {
      openedAt.putIfAbsent(key, sequence);
      explored.putIfAbsent(edge, sequence);
    }
  }

  private List<TraceNode> nodes() {
    return nodes.values().stream().map(this::withCitation).toList();
  }

  private TraceNode withCitation(final TraceNode node) {
    final Integer cited = citedNodes.get(node.id());
    return cited == null
        ? node
        : new TraceNode(
            node.id(),
            node.label(),
            node.entityType(),
            node.fullyQualifiedName(),
            ExplorationState.CITED,
            node.firstStep(),
            cited);
  }

  private List<TraceEdge> edges() {
    final List<TraceEdge> edges = new ArrayList<>();
    offers.forEach((key, edge) -> edges.add(offer(key, edge)));
    explored.forEach((edge, sequence) -> edges.add(exploredEdge(edge, sequence)));
    return edges;
  }

  private TraceEdge offer(final RelationshipKey key, final RunStep.Edge edge) {
    final Integer followed = openedAt.get(key);
    final Integer cited = citedOffers.get(key);
    final ExplorationState state =
        cited != null
            ? ExplorationState.CITED
            : followed == null ? ExplorationState.OFFERED : ExplorationState.EXPLORED;
    return traceEdge(edge, state, offeredAt.get(key), followed, cited);
  }

  private TraceEdge exploredEdge(final RunStep.Edge edge, final int sequence) {
    final Integer cited = citedEdges.get(edge);
    final ExplorationState state =
        cited == null ? ExplorationState.EXPLORED : ExplorationState.CITED;
    return traceEdge(edge, state, sequence, sequence, cited);
  }

  private static TraceNode traceNode(final RunStep.Node node, final int sequence) {
    return new TraceNode(
        node.id(),
        node.label(),
        node.entityType(),
        node.fullyQualifiedName(),
        ExplorationState.EXPLORED,
        sequence,
        null);
  }

  private static TraceEdge traceEdge(
      final RunStep.Edge edge,
      final ExplorationState state,
      final int firstStep,
      final Integer exploredStep,
      final Integer citedStep) {
    return new TraceEdge(
        edge.node(),
        edge.relationship(),
        edge.direction(),
        edge.other(),
        edge.count(),
        state,
        firstStep,
        exploredStep,
        citedStep);
  }

  private record RelationshipKey(String node, String relationship, Direction direction) {
    static RelationshipKey of(final RunStep.Edge edge) {
      return new RelationshipKey(edge.node(), edge.relationship(), edge.direction());
    }
  }
}
