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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnDataType;
import org.openmetadata.service.Entity;
import org.openmetadata.service.rdf.translator.JsonLdTranslator;

class KnowledgeGraphExplorerTest {

  private static final String BASE = NodeCardReaderTest.BASE;
  private static final String OM = "https://open-metadata.org/ontology/";
  private static final String ORDERS = NodeCardReaderTest.ORDERS;
  private static final String RAW = NodeCardReaderTest.RAW;
  private static final String DAILY = NodeCardReaderTest.DAILY;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

  private final InMemoryRunTraceStore traces = new InMemoryRunTraceStore();
  private final RunContext run = new RunContext(UUID.randomUUID(), "analyst");

  private KnowledgeGraphExplorer explorer(final GraphReader graph) {
    return new KnowledgeGraphExplorer(graph, OntologyVocabulary.canonical(), BASE, traces, CLOCK);
  }

  private static SearchHit hit(final String iri) {
    return new SearchHit("table", null, UUID.fromString(iri.substring(iri.lastIndexOf('/') + 1)));
  }

  @Test
  void findMapsSearchHitsToTheIrisTheProjectionMints() {
    EntityInterface.CANONICAL_ENTITY_NAME_MAP.put("table", Entity.TABLE);
    final UUID id = UUID.randomUUID();
    final Table table =
        new Table()
            .withId(id)
            .withName("orders")
            .withFullyQualifiedName("warehouse.shop.public.orders")
            .withColumns(
                List.of(
                    new Column()
                        .withName("id")
                        .withDataType(ColumnDataType.INT)
                        .withFullyQualifiedName("warehouse.shop.public.orders.id")));
    final Model projected = new JsonLdTranslator(new ObjectMapper(), BASE).toRdf(table);

    final FindResult found =
        explorer(InMemoryGraph.of(projected))
            .find(
                run,
                "orders",
                List.of(
                    new SearchHit("table", table.getFullyQualifiedName(), id),
                    new SearchHit(Entity.TABLE_COLUMN, "warehouse.shop.public.orders.id", null)));

    assertThat(found.nodes())
        .extracting(NodeCard::fullyQualifiedName, NodeCard::inGraph)
        .containsExactly(
            tuple("warehouse.shop.public.orders", true),
            tuple("warehouse.shop.public.orders.id", true));
    assertThat(found.nodes().getFirst().id()).isEqualTo(BASE + "entity/table/" + id);
  }

  @Test
  void aFindThenExpandRunYieldsATraceOfNodesEdgesStatesAndSteps() {
    final KnowledgeGraphExplorer explorer =
        explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));

    explorer.find(run, "orders", List.of(hit(ORDERS)));
    explorer.expand(
        run,
        new ExpandRequest(
            List.of(ORDERS),
            List.of(OM + "upstream"),
            Set.of(Direction.OUTGOING),
            10,
            0,
            Long.MAX_VALUE));
    final RunTrace trace = explorer.trace(run.run());

    assertThat(trace.steps())
        .extracting(TraceStep::sequence, TraceStep::operation, TraceStep::nodes)
        .containsExactly(
            tuple(1, Operation.FIND, List.of(ORDERS)), tuple(2, Operation.EXPAND, List.of(RAW)));
    assertThat(trace.nodes())
        .extracting(TraceNode::id, TraceNode::state, TraceNode::firstStep)
        .containsExactly(
            tuple(ORDERS, ExplorationState.EXPLORED, 1), tuple(RAW, ExplorationState.EXPLORED, 2));
    assertThat(trace.edges())
        .extracting(
            TraceEdge::node,
            TraceEdge::relationship,
            TraceEdge::direction,
            TraceEdge::other,
            TraceEdge::state)
        .contains(
            tuple(ORDERS, "om:upstream", Direction.OUTGOING, null, ExplorationState.EXPLORED),
            tuple(ORDERS, "om:upstream", Direction.OUTGOING, RAW, ExplorationState.EXPLORED),
            tuple(ORDERS, "om:upstream", Direction.INCOMING, null, ExplorationState.OFFERED),
            tuple(ORDERS, "om:hasOwner", Direction.OUTGOING, null, ExplorationState.OFFERED))
        .doesNotContain(
            tuple(ORDERS, "om:upstream", Direction.INCOMING, DAILY, ExplorationState.EXPLORED));
  }

  @Test
  void anOfferedRelationshipCarriesTheCountItWasOfferedWith() {
    final KnowledgeGraphExplorer explorer =
        explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));

    explorer.find(run, "orders", List.of(hit(ORDERS)));

    assertThat(explorer.trace(run.run()).edges())
        .filteredOn(edge -> edge.relationship().equals("om:hasTag"))
        .singleElement()
        .extracting(TraceEdge::count, TraceEdge::firstStep)
        .containsExactly(2L, 1);
  }

  @Test
  void aQueryIsAStepOfTheRun() {
    final KnowledgeGraphExplorer explorer =
        explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));

    explorer.recordQuery(run, "SELECT ?s WHERE { ?s ?p ?o } LIMIT 1");

    assertThat(explorer.trace(run.run()).steps())
        .singleElement()
        .satisfies(
            step -> {
              assertThat(step.operation()).isEqualTo(Operation.QUERY);
              assertThat(step.input().sparql()).isEqualTo("SELECT ?s WHERE { ?s ?p ?o } LIMIT 1");
              assertThat(step.at()).isEqualTo(CLOCK.millis());
            });
  }

  @Test
  void runsAreKeptApart() {
    final KnowledgeGraphExplorer explorer =
        explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));
    final RunContext other = new RunContext(UUID.randomUUID(), "analyst");

    explorer.find(run, "orders", List.of(hit(ORDERS)));
    explorer.find(other, "raw", List.of(hit(RAW)));

    assertThat(explorer.trace(run.run()).nodes()).extracting(TraceNode::id).containsExactly(ORDERS);
  }

  @Test
  void aRunWithNoRecordedStepsIsACorrectionNotAnEmptyTrace() {
    final UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> explorer(InMemoryGraph.ofTurtle("")).trace(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(unknown.toString())
        .hasMessageContaining("24 hours");
  }
}
