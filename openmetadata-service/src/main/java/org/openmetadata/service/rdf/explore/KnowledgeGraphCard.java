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
 * What an MCP Apps host draws for one knowledge_graph call: for find, expand and query what the
 * call served; for cite and trace the whole run, each node and followed relationship with its
 * state (offered, explored, cited) and what says how far to trust it. Every list is bounded
 * ({@link KnowledgeGraphCards}); the {@code more*} counts say what was left out.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record KnowledgeGraphCard(
    String operation,
    String run,
    Counts counts,
    List<Node> nodes,
    int moreNodes,
    List<Edge> edges,
    int moreEdges,
    List<Group> groups,
    List<Memory> memories,
    int moreMemories,
    List<Step> steps,
    int moreSteps,
    String query,
    String rest) {

  /** A node and its trust: tier, certification, status, failing tests, open incidents. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Node(
      String id,
      String name,
      String type,
      String state,
      String tier,
      String certification,
      String status,
      Long failingTests,
      Long openIncidents) {}

  /** A relationship the run followed, from one node to another. */
  public record Edge(String from, String to, String relationship, String state) {}

  /** The far nodes an expand listed for one relationship of one node. */
  public record Group(
      String node, String relationship, String direction, long total, List<String> nodes) {}

  /** A memory the run showed (on a card, among find's notes, as a preference) or saved. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Memory(
      String id, String title, String stage, Boolean awaitingReview, String about, String origin) {}

  /** One call of the run, in one short line. */
  public record Step(int sequence, String operation, String line) {}

  /** How many nodes were offered, explored and cited, and how many calls the run made. */
  public record Counts(int offered, int explored, int cited, int calls) {}
}
