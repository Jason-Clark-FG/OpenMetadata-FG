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

package org.openmetadata.mcp.tools;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.openmetadata.mcp.util.McpParams;
import org.openmetadata.mcp.util.PageCursor;
import org.openmetadata.service.rdf.RdfIriValidator;
import org.openmetadata.service.rdf.explore.Citation;
import org.openmetadata.service.rdf.explore.CitedRelationship;
import org.openmetadata.service.rdf.explore.Direction;
import org.openmetadata.service.rdf.explore.LearnedCandidate;
import org.openmetadata.service.rdf.explore.OntologyTermKind;
import org.openmetadata.service.rdf.explore.Operation;

/**
 * The arguments of one {@code knowledge_graph} call, validated once. Every rejection names the
 * argument, the rule and an accepted value, so a model can repeat the call correctly.
 */
/**
 * One knowledge_graph call: its run and what it asks. A {@code maxChars} argument is ignored: the
 * response size is the server's, since a model cannot know its client's limit.
 */
record KnowledgeGraphCall(UUID run, Request request) {

  static final int DEFAULT_FIND_LIMIT = 5;
  static final int MAX_FIND_LIMIT = 10;
  static final int DEFAULT_EXPAND_LIMIT = 10;
  static final int MAX_EXPAND_LIMIT = 50;
  static final int MAX_NODES = 20;
  static final int MAX_RELATIONSHIPS = 10;
  static final int MAX_CITED = 50;

  private static final String OPERATION = "operation";
  private static final String RUN = "run";
  private static final String QUESTION = "question";
  private static final String ENTITY_TYPES = "entityTypes";
  private static final String LIMIT = "limit";
  private static final String NODES = "nodes";
  private static final String RELATIONSHIPS = "relationships";
  private static final String DIRECTION = "direction";
  private static final String CURSOR = "cursor";
  private static final String SPARQL = "sparql";
  private static final String EDGES = "edges";
  private static final String LEARNED = "learned";
  private static final String EDGE_NODE = "node";
  private static final String EDGE_RELATIONSHIP = "relationship";
  private static final String EDGE_OTHER = "other";
  private static final String BOTH_DIRECTIONS = "both";
  private static final Set<Operation> RUN_REQUIRED = Set.of(Operation.TRACE, Operation.CITE);

  sealed interface Request permits Find, Expand, Query, Trace, Cite {}

  /**
   * No {@code entityTypes} looks everywhere. Otherwise {@code ontologyConcept} and {@code
   * ontologyProperty} ask for those ontology terms, and every other type is searched in the catalog.
   */
  record Find(String question, List<String> entityTypes, int limit) implements Request {
    Set<OntologyTermKind> termKinds() {
      return OntologyTermKind.requestedBy(entityTypes);
    }

    List<String> catalogTypes() {
      return entityTypes.stream().filter(type -> !OntologyTermKind.isTermType(type)).toList();
    }

    boolean searchesCatalog() {
      return entityTypes.isEmpty() || !catalogTypes().isEmpty();
    }
  }

  record Expand(
      List<String> nodes,
      List<String> relationships,
      Set<Direction> directions,
      int limit,
      int offset)
      implements Request {}

  record Query(String sparql) implements Request {}

  record Trace() implements Request {}

  record Cite(Citation citation, List<LearnedCandidate> learned) implements Request {
    /** A cite of what the answer relied on, carrying nothing learnt. */
    Cite(final Citation citation) {
      this(citation, List.of());
    }
  }

  static KnowledgeGraphCall parse(final Map<String, Object> values) {
    final McpToolParameters parameters = McpToolParameters.from(values);
    final Operation operation = operation(parameters.optionalString(OPERATION));
    final UUID run = run(parameters.optionalString(RUN), operation);
    return new KnowledgeGraphCall(run, request(operation, values, parameters));
  }

  /** The run this call belongs to: the one passed back, or a new one. */
  UUID runOrNew() {
    return run == null ? UUID.randomUUID() : run;
  }

  private static Request request(
      final Operation operation,
      final Map<String, Object> values,
      final McpToolParameters parameters) {
    return switch (operation) {
      case FIND -> find(values, parameters);
      case EXPAND -> expand(values, parameters);
      case QUERY -> new Query(required(parameters, SPARQL, "for query: the SELECT to run"));
      case TRACE -> new Trace();
      case CITE -> cite(values);
    };
  }

  private static Operation operation(final String value) {
    return Operation.fromValue(value)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "'operation' must be one of find, expand, query, trace or cite; got '%s'"
                        .formatted(value)));
  }

  private static UUID run(final String value, final Operation operation) {
    final boolean absent = McpToolParameters.isBlank(value);
    if (absent && RUN_REQUIRED.contains(operation)) {
      throw new IllegalArgumentException(
          "'run' is required for %s: pass the 'run' an earlier knowledge_graph call returned"
              .formatted(operation.value()));
    }
    return absent ? null : parseRun(value);
  }

  private static UUID parseRun(final String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "'run' must be the 'run' an earlier knowledge_graph call returned, a UUID; got '%s'"
              .formatted(value),
          exception);
    }
  }

  private static Find find(final Map<String, Object> values, final McpToolParameters parameters) {
    final String question =
        required(parameters, QUESTION, "for find: the words to look for, e.g. 'customer orders'");
    final List<String> entityTypes =
        McpParams.getStringList(values, ENTITY_TYPES).stream()
            .map(KnowledgeGraphCall::entityType)
            .toList();
    return new Find(question, entityTypes, limit(parameters, DEFAULT_FIND_LIMIT, MAX_FIND_LIMIT));
  }

  private static String entityType(final String value) {
    try {
      return McpEntityReference.validateType(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "'entityTypes' entries must be entity type names such as table or dashboard; got '%s'"
              .formatted(value),
          exception);
    }
  }

  private static Expand expand(
      final Map<String, Object> values, final McpToolParameters parameters) {
    final List<String> nodes = nodes(McpParams.getStringList(values, NODES));
    final List<String> relationships =
        requireAtMost(
            McpParams.getStringList(values, RELATIONSHIPS), RELATIONSHIPS, MAX_RELATIONSHIPS);
    if (relationships.isEmpty()) {
      throw new IllegalArgumentException(
          "'relationships' is required for expand: pass names from a card's 'reachable' list, e.g. om:hasOwner");
    }
    return new Expand(
        nodes,
        relationships,
        directions(parameters.optionalString(DIRECTION)),
        limit(parameters, DEFAULT_EXPAND_LIMIT, MAX_EXPAND_LIMIT),
        offset(parameters.optionalString(CURSOR)));
  }

  private static List<String> nodes(final List<String> values) {
    if (values.isEmpty()) {
      throw new IllegalArgumentException(
          "'nodes' is required for expand: pass the 'id' of one or more cards find or expand returned");
    }
    return requireAtMost(values, NODES, MAX_NODES).stream().map(KnowledgeGraphCall::node).toList();
  }

  private static <T> List<T> requireAtMost(final List<T> values, final String name, final int max) {
    if (values.size() > max) {
      throw new IllegalArgumentException(
          "'%s' takes at most %d entries per call; got %d. Split them over several calls with the same 'run'."
              .formatted(name, max, values.size()));
    }
    return values;
  }

  private static String node(final String value) {
    return Optional.ofNullable(RdfIriValidator.validateEntityIri(value))
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "'nodes' entries must be the 'id' of a card, an absolute http(s) IRI; got '%s'"
                        .formatted(value)));
  }

  /** A cite names what the answer relied on, what the turn learnt, or both. */
  private static Cite cite(final Map<String, Object> values) {
    final List<String> nodes = McpParams.getStringList(values, NODES);
    final List<LearnedCandidate> learned = LearnedCandidates.parse(values.get(LEARNED));
    if (nodes.isEmpty() && learned.isEmpty()) {
      throw new IllegalArgumentException(
          "'nodes' is required for cite: pass the 'id' of every card your answer relied on, or 'learned' with what the turn taught");
    }
    return new Cite(
        new Citation(
            requireAtMost(nodes, NODES, MAX_CITED).stream().map(KnowledgeGraphCall::node).toList(),
            requireAtMost(edges(values.get(EDGES)), EDGES, MAX_CITED)),
        learned);
  }

  private static List<CitedRelationship> edges(final Object value) {
    final List<?> entries = value instanceof List<?> list ? list : List.of();
    return entries.stream().map(KnowledgeGraphCall::edge).toList();
  }

  private static CitedRelationship edge(final Object entry) {
    final Map<?, ?> fields = entry instanceof Map<?, ?> map ? map : Map.of();
    final boolean complete =
        Stream.of(EDGE_NODE, EDGE_RELATIONSHIP, EDGE_OTHER)
            .allMatch(name -> fields.get(name) instanceof String text && !text.isBlank());
    if (!complete) {
      throw new IllegalArgumentException(
          "'edges' entries for cite are objects with 'node', 'relationship' and 'other', e.g. {\"node\": \"<card id>\", \"relationship\": \"om:upstream\", \"other\": \"<card id>\"}");
    }
    return new CitedRelationship(
        node((String) fields.get(EDGE_NODE)),
        (String) fields.get(EDGE_RELATIONSHIP),
        node((String) fields.get(EDGE_OTHER)));
  }

  private static Set<Direction> directions(final String value) {
    final boolean both = McpToolParameters.isBlank(value) || BOTH_DIRECTIONS.equals(value);
    return both ? Set.of(Direction.OUTGOING, Direction.INCOMING) : Set.of(direction(value));
  }

  private static Direction direction(final String value) {
    try {
      return Direction.fromValue(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "'direction' must be outgoing, incoming or both; got '%s'".formatted(value), exception);
    }
  }

  private static int offset(final String cursor) {
    final Optional<PageCursor.Cursor> decoded = PageCursor.decode(cursor);
    final boolean unreadable =
        !McpToolParameters.isBlank(cursor) && decoded.filter(PageCursor.Cursor::isOffset).isEmpty();
    if (unreadable) {
      throw new IllegalArgumentException(
          "'cursor' must be the 'nextCursor' an earlier expand returned, passed back unchanged");
    }
    return decoded.map(PageCursor.Cursor::offset).orElse(0);
  }

  private static int limit(final McpToolParameters parameters, final int fallback, final int max) {
    return Math.min(Math.max(parameters.integer(LIMIT, fallback), 1), max);
  }

  private static String required(
      final McpToolParameters parameters, final String name, final String purpose) {
    final String value = parameters.optionalString(name);
    if (McpToolParameters.isBlank(value)) {
      throw new IllegalArgumentException("'%s' is required %s".formatted(name, purpose));
    }
    return value;
  }
}
