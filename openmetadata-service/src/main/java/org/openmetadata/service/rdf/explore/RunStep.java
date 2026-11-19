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
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * What one call served, as stored: the nodes returned as cards, each with the relationships its
 * card offered, the memories it showed, the edges an expansion returned, and for a cite what the
 * turn learnt and what became of it.
 */
public record RunStep(
    Operation operation,
    long at,
    String principal,
    Input input,
    List<Node> nodes,
    List<Edge> edges,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<LearnedOutcome> learned) {

  public RunStep {
    nodes = List.copyOf(nodes);
    edges = List.copyOf(edges);
    learned = learned == null ? List.of() : List.copyOf(learned);
  }

  /** A step that learnt nothing; steps stored before candidates were recorded read back so. */
  public RunStep(
      final Operation operation,
      final long at,
      final String principal,
      final Input input,
      final List<Node> nodes,
      final List<Edge> edges) {
    this(operation, at, principal, input, nodes, edges, List.of());
  }

  /**
   * What the caller asked for. An expand keeps its {@code direction} ({@code outgoing}, {@code
   * incoming} or {@code both}), {@code limit} and {@code offset}; steps stored before these were
   * recorded read back without them.
   */
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record Input(
      String question,
      List<String> nodes,
      List<String> relationships,
      String sparql,
      String direction,
      Integer limit,
      Integer offset,
      String agent,
      String connection,
      String tool,
      Map<String, Object> arguments) {
    public Input {
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
      relationships = relationships == null ? List.of() : List.copyOf(relationships);
    }

    public Input(
        final String question,
        final List<String> nodes,
        final List<String> relationships,
        final String sparql,
        final String direction,
        final Integer limit,
        final Integer offset,
        final String agent) {
      this(
          question,
          nodes,
          relationships,
          sparql,
          direction,
          limit,
          offset,
          agent,
          null,
          null,
          null);
    }

    public Input(
        final String question,
        final List<String> nodes,
        final List<String> relationships,
        final String sparql,
        final String direction,
        final Integer limit,
        final Integer offset) {
      this(question, nodes, relationships, sparql, direction, limit, offset, null);
    }

    /** A task handed to the sub-agent {@code agent}, with the nodes it was given. */
    public static Input delegate(final String agent, final String task, final List<String> nodes) {
      return new Input(task, nodes, List.of(), null, null, null, null, agent);
    }

    /**
     * A delegate to the connection {@code connection}: its tools when {@code tool} is null, the
     * tool's schema when {@code arguments} is null, else the tool called with them.
     */
    public static Input connection(
        final String connection, final String tool, final Map<String, Object> arguments) {
      return new Input(
          null, List.of(), List.of(), null, null, null, null, null, connection, tool, arguments);
    }

    public static Input find(final String question) {
      return new Input(question, List.of(), List.of(), null, null, null, null);
    }

    public static Input expand(
        final List<String> nodes,
        final List<String> relationships,
        final String direction,
        final int limit,
        final int offset) {
      return new Input(null, nodes, relationships, null, direction, limit, offset);
    }

    public static Input query(final String sparql) {
      return new Input(null, List.of(), List.of(), sparql, null, null, null);
    }

    public static Input cite(final List<String> nodes) {
      return new Input(null, nodes, List.of(), null, null, null, null);
    }
  }

  /** A node returned as a card, with the relationships its card offered. */
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record Node(
      String id, String label, String entityType, String fullyQualifiedName, List<Offer> offers) {
    public Node {
      offers = offers == null ? List.of() : List.copyOf(offers);
    }
  }

  /**
   * One {@code reachable} entry, stored under short keys: a find step of ten wide cards holds
   * about three hundred of them.
   */
  public record Offer(
      @JsonProperty("r") String relationship,
      @JsonProperty("d") Direction direction,
      @JsonProperty("c") long count) {}

  /** An edge an expansion returned; {@code count} is set only while folding offers. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Edge(
      String node, String relationship, Direction direction, String other, Long count) {}
}
