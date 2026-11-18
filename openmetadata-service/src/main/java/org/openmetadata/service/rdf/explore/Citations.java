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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Checks what an answer cites against its run's trace: a node must have been served in the run,
 * a relationship must be an edge an expansion returned, named from either end.
 */
final class Citations {

  private Citations() {}

  record Checked(CiteResult result, List<RunStep.Edge> edges) {
    boolean citesAnything() {
      return !result.cited().nodes().isEmpty() || !edges.isEmpty();
    }
  }

  static Checked check(
      final RunTrace trace, final Citation citation, final UnaryOperator<String> relationshipName) {
    final Set<String> served =
        trace.nodes().stream().map(TraceNode::id).collect(Collectors.toSet());
    final List<TraceEdge> explored =
        trace.edges().stream().filter(edge -> edge.other() != null).toList();
    final Map<Boolean, List<String>> nodes =
        citation.nodes().stream().distinct().collect(Collectors.partitioningBy(served::contains));
    final Map<Boolean, List<CitedRelationship>> relationships =
        citation.relationships().stream()
            .collect(
                Collectors.partitioningBy(
                    cited -> !matching(explored, cited, relationshipName).isEmpty()));
    return new Checked(
        new CiteResult(
            new Citation(nodes.get(true), relationships.get(true)),
            new Citation(nodes.get(false), relationships.get(false))),
        edgesOf(explored, relationships.get(true), relationshipName));
  }

  private static List<RunStep.Edge> edgesOf(
      final List<TraceEdge> explored,
      final List<CitedRelationship> reached,
      final UnaryOperator<String> relationshipName) {
    return reached.stream()
        .flatMap(cited -> matching(explored, cited, relationshipName).stream())
        .distinct()
        .toList();
  }

  private static List<RunStep.Edge> matching(
      final List<TraceEdge> explored,
      final CitedRelationship cited,
      final UnaryOperator<String> relationshipName) {
    final String name = relationshipName.apply(cited.relationship());
    return explored.stream()
        .filter(edge -> edge.relationship().equals(name))
        .filter(edge -> joins(edge, cited))
        .map(
            edge ->
                new RunStep.Edge(
                    edge.node(), edge.relationship(), edge.direction(), edge.other(), null))
        .toList();
  }

  private static boolean joins(final TraceEdge edge, final CitedRelationship cited) {
    final boolean forward = edge.node().equals(cited.node()) && edge.other().equals(cited.other());
    final boolean backward = edge.node().equals(cited.other()) && edge.other().equals(cited.node());
    return forward || backward;
  }
}
