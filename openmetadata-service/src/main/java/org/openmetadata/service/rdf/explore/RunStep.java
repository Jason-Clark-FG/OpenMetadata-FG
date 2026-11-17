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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What one call served, as stored: the nodes returned as cards and the edges it showed. An edge
 * without {@code other} is a relationship a card offered, with its {@code count}; an edge with
 * {@code other} is one an expansion returned.
 */
public record RunStep(
    Operation operation,
    long at,
    String principal,
    Input input,
    List<Node> nodes,
    List<Edge> edges) {

  public RunStep {
    nodes = List.copyOf(nodes);
    edges = List.copyOf(edges);
  }

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record Input(
      String question, List<String> nodes, List<String> relationships, String sparql) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Node(String id, String label, String entityType) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Edge(
      String node, String relationship, Direction direction, String other, Long count) {}
}
