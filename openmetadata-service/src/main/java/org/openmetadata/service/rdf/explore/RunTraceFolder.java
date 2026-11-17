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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Folds the recorded steps of a run into one subgraph. A node is listed once, at the step that
 * first returned it; an offered relationship becomes explored once an expansion from its node
 * followed it in the same direction.
 */
final class RunTraceFolder {

  private final Map<String, TraceNode> nodes = new LinkedHashMap<>();
  private final Map<RelationshipKey, RunStep.Edge> offers = new LinkedHashMap<>();
  private final Map<RelationshipKey, Integer> offeredAt = new LinkedHashMap<>();
  private final Map<RunStep.Edge, Integer> explored = new LinkedHashMap<>();
  private final Set<RelationshipKey> opened = new HashSet<>();
  private final List<TraceStep> steps = new ArrayList<>();

  static RunTrace fold(final UUID run, final List<RunStep> recorded) {
    final RunTraceFolder folder = new RunTraceFolder();
    recorded.forEach(folder::add);
    return new RunTrace(run, List.copyOf(folder.nodes.values()), folder.edges(), folder.steps);
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
    step.nodes().forEach(node -> addNode(node, sequence));
    step.edges().forEach(edge -> addEdge(edge, sequence));
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
      opened.add(key);
      explored.putIfAbsent(edge, sequence);
    }
  }

  private List<TraceEdge> edges() {
    final List<TraceEdge> edges = new ArrayList<>();
    offers.forEach((key, edge) -> edges.add(offer(key, edge)));
    explored.forEach(
        (edge, sequence) -> edges.add(traceEdge(edge, ExplorationState.EXPLORED, sequence)));
    return edges;
  }

  private TraceEdge offer(final RelationshipKey key, final RunStep.Edge edge) {
    final ExplorationState state =
        opened.contains(key) ? ExplorationState.EXPLORED : ExplorationState.OFFERED;
    return traceEdge(edge, state, offeredAt.get(key));
  }

  private static TraceNode traceNode(final RunStep.Node node, final int sequence) {
    return new TraceNode(
        node.id(), node.label(), node.entityType(), ExplorationState.EXPLORED, sequence);
  }

  private static TraceEdge traceEdge(
      final RunStep.Edge edge, final ExplorationState state, final int sequence) {
    return new TraceEdge(
        edge.node(),
        edge.relationship(),
        edge.direction(),
        edge.other(),
        edge.count(),
        state,
        sequence);
  }

  private record RelationshipKey(String node, String relationship, Direction direction) {
    static RelationshipKey of(final RunStep.Edge edge) {
      return new RelationshipKey(edge.node(), edge.relationship(), edge.direction());
    }
  }
}
