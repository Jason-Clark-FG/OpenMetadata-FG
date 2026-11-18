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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openmetadata.mcp.util.PageCursor;
import org.openmetadata.service.rdf.explore.Citation;
import org.openmetadata.service.rdf.explore.CitedRelationship;
import org.openmetadata.service.rdf.explore.Direction;
import org.openmetadata.service.rdf.explore.OntologyTermKind;

class KnowledgeGraphCallTest {

  private static final String TABLE = "https://open-metadata.org/entity/table/" + UUID.randomUUID();

  static List<Arguments> corrections() {
    return List.of(
        Arguments.of(Map.of(), List.of("'operation'", "find, expand, query, trace or cite")),
        Arguments.of(Map.of("operation", "search"), List.of("'operation'", "'search'")),
        Arguments.of(Map.of("operation", "find"), List.of("'question'", "find")),
        Arguments.of(
            Map.of("operation", "find", "question", "orders", "entityTypes", List.of("table x")),
            List.of("'entityTypes'", "'table x'")),
        Arguments.of(Map.of("operation", "expand"), List.of("'nodes'", "'id' of")),
        Arguments.of(
            Map.of(
                "operation", "expand", "nodes", List.of("orders"), "relationships", "om:hasOwner"),
            List.of("'nodes'", "'orders'")),
        Arguments.of(
            Map.of("operation", "expand", "nodes", List.of(TABLE)),
            List.of("'relationships'", "reachable", "om:hasOwner")),
        Arguments.of(
            Map.of(
                "operation",
                "expand",
                "nodes",
                List.of(TABLE),
                "relationships",
                List.of("om:hasOwner"),
                "direction",
                "up"),
            List.of("'direction'", "outgoing, incoming or both", "'up'")),
        Arguments.of(
            Map.of(
                "operation",
                "expand",
                "nodes",
                List.of(TABLE),
                "relationships",
                List.of("om:hasOwner"),
                "cursor",
                "not-a-cursor"),
            List.of("'cursor'", "'nextCursor'")),
        Arguments.of(
            Map.of(
                "operation",
                "expand",
                "nodes",
                Collections.nCopies(KnowledgeGraphCall.MAX_NODES + 1, TABLE),
                "relationships",
                List.of("om:hasOwner")),
            List.of("'nodes'", "at most " + KnowledgeGraphCall.MAX_NODES)),
        Arguments.of(
            Map.of(
                "operation",
                "expand",
                "nodes",
                List.of(TABLE),
                "relationships",
                Collections.nCopies(KnowledgeGraphCall.MAX_RELATIONSHIPS + 1, "om:hasOwner")),
            List.of("'relationships'", "at most " + KnowledgeGraphCall.MAX_RELATIONSHIPS)),
        Arguments.of(Map.of("operation", "query"), List.of("'sparql'")),
        Arguments.of(Map.of("operation", "trace"), List.of("'run'", "trace")),
        Arguments.of(
            Map.of("operation", "cite", "nodes", List.of(TABLE)), List.of("'run'", "cite")),
        Arguments.of(
            Map.of("operation", "cite", "run", UUID.randomUUID().toString()),
            List.of("'nodes'", "cite")),
        Arguments.of(
            Map.of(
                "operation",
                "cite",
                "run",
                UUID.randomUUID().toString(),
                "nodes",
                List.of(TABLE),
                "edges",
                List.of(Map.of("node", TABLE, "relationship", "om:upstream"))),
            List.of("'edges'", "'node'", "'relationship'", "'other'")),
        Arguments.of(
            Map.of(
                "operation",
                "cite",
                "run",
                UUID.randomUUID().toString(),
                "nodes",
                Collections.nCopies(KnowledgeGraphCall.MAX_CITED + 1, TABLE)),
            List.of("'nodes'", "at most " + KnowledgeGraphCall.MAX_CITED)),
        Arguments.of(
            Map.of("operation", "find", "question", "orders", "run", "run-1"),
            List.of("'run'", "'run-1'")));
  }

  @ParameterizedTest
  @MethodSource("corrections")
  void aBadArgumentIsACorrectionNamingTheArgumentAndTheFix(
      final Map<String, Object> arguments, final List<String> expectedFragments) {
    assertThatThrownBy(() -> KnowledgeGraphCall.parse(arguments))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(failure -> assertThat(failure.getMessage()).contains(expectedFragments));
  }

  @Test
  void findReadsTheQuestionTypesAndLimit() {
    final KnowledgeGraphCall call =
        KnowledgeGraphCall.parse(
            Map.of(
                "operation", "find",
                "question", "customer orders",
                "entityTypes", List.of("table", "dashboard"),
                "limit", "3"));

    assertThat(call.run()).isNull();
    assertThat(call.request())
        .isEqualTo(
            new KnowledgeGraphCall.Find("customer orders", List.of("table", "dashboard"), 3));
  }

  @Test
  void entityTypesChooseTheCatalogTypesToSearchAndTheOntologyTermsToMatch() {
    final KnowledgeGraphCall.Find everything = new KnowledgeGraphCall.Find("x", List.of(), 5);
    final KnowledgeGraphCall.Find catalog =
        new KnowledgeGraphCall.Find("x", List.of("table", "dashboard"), 5);
    final KnowledgeGraphCall.Find properties =
        new KnowledgeGraphCall.Find("x", List.of("ontologyProperty"), 5);
    final KnowledgeGraphCall.Find mixed =
        new KnowledgeGraphCall.Find("x", List.of("table", "ontologyConcept"), 5);

    assertThat(everything.termKinds()).containsExactlyInAnyOrder(OntologyTermKind.values());
    assertThat(everything.searchesCatalog()).isTrue();
    assertThat(catalog.termKinds()).isEmpty();
    assertThat(catalog.catalogTypes()).containsExactly("table", "dashboard");
    assertThat(properties.termKinds()).containsExactly(OntologyTermKind.PROPERTY);
    assertThat(properties.searchesCatalog()).isFalse();
    assertThat(mixed.termKinds()).containsExactly(OntologyTermKind.CONCEPT);
    assertThat(mixed.catalogTypes()).containsExactly("table");
    assertThat(mixed.searchesCatalog()).isTrue();
  }

  @Test
  void citeReadsTheNodesAndRelationshipsAnAnswerReliedOn() {
    final UUID run = UUID.randomUUID();
    final String raw = "https://open-metadata.org/entity/table/" + UUID.randomUUID();

    final KnowledgeGraphCall call =
        KnowledgeGraphCall.parse(
            Map.of(
                "operation",
                "cite",
                "run",
                run.toString(),
                "nodes",
                List.of(TABLE),
                "edges",
                List.of(Map.of("node", TABLE, "relationship", "om:upstream", "other", raw))));

    assertThat(call.run()).isEqualTo(run);
    assertThat(call.request())
        .isEqualTo(
            new KnowledgeGraphCall.Cite(
                new Citation(
                    List.of(TABLE), List.of(new CitedRelationship(TABLE, "om:upstream", raw)))));
  }

  @Test
  void expandFollowsBothDirectionsUnlessOneIsNamed() {
    final KnowledgeGraphCall both =
        KnowledgeGraphCall.parse(
            Map.of(
                "operation", "expand",
                "nodes", List.of(TABLE),
                "relationships", List.of("om:hasOwner", "om:upstream")));
    final KnowledgeGraphCall incoming =
        KnowledgeGraphCall.parse(
            Map.of(
                "operation", "expand",
                "nodes", TABLE,
                "relationships", "om:upstream",
                "direction", "incoming"));

    assertThat(((KnowledgeGraphCall.Expand) both.request()).directions())
        .isEqualTo(Set.of(Direction.OUTGOING, Direction.INCOMING));
    assertThat(((KnowledgeGraphCall.Expand) incoming.request()).directions())
        .isEqualTo(Set.of(Direction.INCOMING));
  }

  @Test
  void expandResumesFromTheCursorAnEarlierPageReturned() {
    final UUID run = UUID.randomUUID();

    final KnowledgeGraphCall call =
        KnowledgeGraphCall.parse(
            Map.of(
                "operation", "expand",
                "run", run.toString(),
                "nodes", List.of(TABLE),
                "relationships", List.of("om:hasOwner"),
                "cursor", PageCursor.encodeOffset(20)));

    assertThat(call.run()).isEqualTo(run);
    assertThat(((KnowledgeGraphCall.Expand) call.request()).offset()).isEqualTo(20);
  }

  @Test
  void limitsAreClampedToWhatAPageCanCarry() {
    final KnowledgeGraphCall find =
        KnowledgeGraphCall.parse(Map.of("operation", "find", "question", "orders", "limit", 500));

    assertThat(((KnowledgeGraphCall.Find) find.request()).limit())
        .isEqualTo(KnowledgeGraphCall.MAX_FIND_LIMIT);
  }
}
