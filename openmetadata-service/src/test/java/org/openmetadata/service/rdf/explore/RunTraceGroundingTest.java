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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnDataType;
import org.openmetadata.schema.type.TableConstraint;
import org.openmetadata.service.Entity;
import org.openmetadata.service.rdf.translator.JsonLdTranslator;

/** What a run's trace says about how an answer was reached and what grounded it. */
class RunTraceGroundingTest {

  private static final String BASE = NodeCardReaderTest.BASE;
  private static final String OM = "https://open-metadata.org/ontology/";
  private static final String ORDERS = NodeCardReaderTest.ORDERS;
  private static final String RAW = NodeCardReaderTest.RAW;
  private static final String DAILY = NodeCardReaderTest.DAILY;
  private static final String UPSTREAM = "om:upstream";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC);

  private final InMemoryRunTraceStore traces = new InMemoryRunTraceStore();
  private final RunContext run = new RunContext(UUID.randomUUID(), "analyst");

  private KnowledgeGraphExplorer explorer(final GraphReader graph) {
    return new KnowledgeGraphExplorer(graph, OntologyVocabulary.canonical(), BASE, traces, CLOCK);
  }

  private KnowledgeGraphExplorer shop() {
    return explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));
  }

  private static FindRequest catalog(final String question) {
    return new FindRequest(question, Set.of(), 10, Long.MAX_VALUE);
  }

  private static SearchHit hit(final String iri) {
    return new SearchHit("table", null, UUID.fromString(iri.substring(iri.lastIndexOf('/') + 1)));
  }

  private static ExpandRequest upstreamOf(
      final String node, final Direction direction, final int limit, final int offset) {
    return new ExpandRequest(
        List.of(node), List.of(OM + "upstream"), Set.of(direction), limit, offset, Long.MAX_VALUE);
  }

  private void findOrdersThenFollowItsUpstream(final KnowledgeGraphExplorer explorer) {
    explorer.find(run, catalog("orders"), List.of(hit(ORDERS)));
    explorer.expand(run, upstreamOf(ORDERS, Direction.OUTGOING, 10, 0));
  }

  @Test
  void anExpandStepKeepsItsDirectionLimitAndOffset() {
    final KnowledgeGraphExplorer explorer = shop();

    explorer.expand(run, upstreamOf(ORDERS, Direction.INCOMING, 7, 3));

    assertThat(explorer.trace(run.run()).steps().getFirst().input())
        .extracting(RunStep.Input::direction, RunStep.Input::limit, RunStep.Input::offset)
        .containsExactly("incoming", 7, 3);
  }

  @Test
  void anOfferedRelationshipSaysAtWhichStepItWasFollowed() {
    final KnowledgeGraphExplorer explorer = shop();

    findOrdersThenFollowItsUpstream(explorer);

    assertThat(explorer.trace(run.run()).edges())
        .filteredOn(edge -> ORDERS.equals(edge.node()) && edge.other() == null)
        .filteredOn(edge -> UPSTREAM.equals(edge.relationship()))
        .extracting(TraceEdge::direction, TraceEdge::firstStep, TraceEdge::exploredStep)
        .containsExactlyInAnyOrder(
            tuple(Direction.OUTGOING, 1, 2), tuple(Direction.INCOMING, 1, null));
  }

  @Test
  void traceNodesCarryTheQualifiedNameTheirCardHas() {
    final KnowledgeGraphExplorer explorer = shop();

    explorer.find(run, catalog("orders"), List.of(hit(ORDERS)));

    assertThat(explorer.trace(run.run()).nodes())
        .extracting(TraceNode::id, TraceNode::fullyQualifiedName)
        .containsExactly(tuple(ORDERS, "warehouse.shop.public.orders"));
  }

  @Test
  void ontologyTermsAreCardedAndTracedWithTheirKind() {
    final KnowledgeGraphExplorer explorer = explorer(OntologyTermsTest.releaseAndCatalog());

    final FindResult found =
        explorer.find(
            run,
            new FindRequest(
                "subscriber postal code",
                EnumSet.allOf(OntologyTermKind.class),
                10,
                Long.MAX_VALUE),
            List.of());

    assertThat(found.nodes())
        .extracting(NodeCard::id, NodeCard::entityType)
        .contains(
            tuple(OntologyTermsTest.SUBSCRIBER, "ontologyConcept"),
            tuple(OntologyTermsTest.POSTAL_CODE, "ontologyProperty"));
    assertThat(explorer.trace(run.run()).nodes())
        .extracting(TraceNode::id, TraceNode::entityType)
        .contains(tuple(OntologyTermsTest.SUBSCRIBER, "ontologyConcept"));
  }

  @Test
  void aConstraintAndALineageDetailsNodeAreLabelledByWhatTheyAre() {
    EntityInterface.CANONICAL_ENTITY_NAME_MAP.put("table", Entity.TABLE);
    final UUID id = UUID.randomUUID();
    final Model projected =
        new JsonLdTranslator(new ObjectMapper(), BASE)
            .toRdf(
                new Table()
                    .withId(id)
                    .withName("orders")
                    .withFullyQualifiedName("warehouse.shop.public.orders")
                    .withColumns(
                        List.of(
                            new Column()
                                .withName("id")
                                .withDataType(ColumnDataType.INT)
                                .withFullyQualifiedName("warehouse.shop.public.orders.id")))
                    .withTableConstraints(
                        List.of(
                            new TableConstraint()
                                .withConstraintType(TableConstraint.ConstraintType.PRIMARY_KEY)
                                .withColumns(List.of("id")))));
    projected.add(
        InMemoryGraph.model(
            "<%slineageDetails/a/b> a om:LineageDetails ; om:lineageSource \"Manual\" ."
                .formatted(BASE)));
    final String constraint = BASE + "entity/table/" + id + "/constraint/PRIMARY_KEY/0";

    final List<NodeCard> cards =
        NodeCardReaderTest.reader(InMemoryGraph.of(projected))
            .read(List.of(constraint, BASE + "lineageDetails/a/b"));

    assertThat(cards)
        .extracting(NodeCard::label)
        .containsExactly("TableConstraint PRIMARY_KEY", "LineageDetails Manual");
  }

  @Test
  void aQueryStepRecordsTheNodesItsRowsNamedAndItsText() {
    final KnowledgeGraphExplorer explorer = shop();
    final String sparql = "SELECT ?table ?name WHERE { ?table rdfs:label ?name }";
    final String body =
        """
        {"head":{"vars":["table","name"]},"results":{"bindings":[
          {"table":{"type":"uri","value":"%s"},
           "name":{"type":"literal","value":"orders",
                   "datatype":"http://www.w3.org/2001/XMLSchema#string"}},
          {"table":{"type":"uri","value":"%s"},"name":{"type":"literal","value":"raw"}},
          {"table":{"type":"uri","value":"%s"},"name":{"type":"literal","value":"again"}},
          {"table":{"type":"bnode","value":"b0"}}]}}
        """
            .formatted(ORDERS, RAW, ORDERS);

    explorer.recordQuery(run, sparql, body);
    explorer.recordQuery(run, sparql, "{\"results\": {\"bindings\": [{\"cut");

    final RunTrace trace = explorer.trace(run.run());
    assertThat(trace.steps())
        .extracting(step -> step.input().sparql(), TraceStep::nodes)
        .containsExactly(tuple(sparql, List.of(ORDERS, RAW)), tuple(sparql, List.of()));
    assertThat(trace.nodes()).extracting(TraceNode::id).containsExactly(ORDERS, RAW);
  }

  @Test
  void citingAcceptsWhatTheRunReachedAndRefusesTheRest() {
    final KnowledgeGraphExplorer explorer = shop();
    findOrdersThenFollowItsUpstream(explorer);
    final CitedRelationship followed = new CitedRelationship(ORDERS, UPSTREAM, RAW);
    final CitedRelationship reversed = new CitedRelationship(RAW, OM + "upstream", ORDERS);
    final CitedRelationship onlyOffered = new CitedRelationship(ORDERS, "om:hasOwner", RAW);

    final CiteResult result =
        explorer.cite(
            run,
            new Citation(List.of(ORDERS, RAW, DAILY), List.of(followed, reversed, onlyOffered)));

    assertThat(result.cited())
        .isEqualTo(new Citation(List.of(ORDERS, RAW), List.of(followed, reversed)));
    assertThat(result.refused()).isEqualTo(new Citation(List.of(DAILY), List.of(onlyOffered)));
  }

  @Test
  void aNodeAQueryAskedAboutByNameAndGotRowsForIsServedAndCanBeCited() {
    final KnowledgeGraphExplorer explorer = shop();
    final String rows =
        """
        {"head":{"vars":["p","o"]},"results":{"bindings":[
          {"p":{"type":"uri","value":"%supstream"},"o":{"type":"literal","value":"raw"}}]}}
        """
            .formatted(OM);
    final String noRows = "{\"head\":{\"vars\":[\"o\"]},\"results\":{\"bindings\":[]}}";

    explorer.recordQuery(
        run,
        "PREFIX om: <%s>\nSELECT ?p ?o WHERE { <%s> ?p ?o . ?o <%supstream> ?x }"
            .formatted(OM, RAW, OM),
        rows);
    explorer.recordQuery(run, "SELECT ?o WHERE { <%s> ?p ?o }".formatted(DAILY), noRows);
    final CiteResult result = explorer.cite(run, new Citation(List.of(RAW, DAILY), List.of()));

    assertThat(result.cited().nodes()).containsExactly(RAW);
    assertThat(result.refused().nodes()).containsExactly(DAILY);
    assertThat(explorer.trace(run.run()).nodes())
        .extracting(TraceNode::id)
        .contains(RAW)
        .doesNotContain(DAILY);
  }

  @Test
  void citedNodesAndRelationshipsAreTracedAsCitedAtTheStepThatCitedThem() {
    final KnowledgeGraphExplorer explorer = shop();
    findOrdersThenFollowItsUpstream(explorer);

    explorer.cite(
        run, new Citation(List.of(RAW), List.of(new CitedRelationship(ORDERS, UPSTREAM, RAW))));
    final RunTrace trace = explorer.trace(run.run());

    assertThat(trace.steps().getLast().operation()).isEqualTo(Operation.CITE);
    assertThat(trace.nodes())
        .extracting(TraceNode::id, TraceNode::state, TraceNode::firstStep, TraceNode::citedStep)
        .containsExactly(
            tuple(ORDERS, ExplorationState.EXPLORED, 1, null),
            tuple(RAW, ExplorationState.CITED, 2, 3));
    assertThat(trace.edges())
        .filteredOn(edge -> UPSTREAM.equals(edge.relationship()))
        .filteredOn(edge -> edge.direction() == Direction.OUTGOING)
        .extracting(TraceEdge::other, TraceEdge::state, TraceEdge::citedStep)
        .containsExactlyInAnyOrder(
            tuple(null, ExplorationState.CITED, 3), tuple(RAW, ExplorationState.CITED, 3));
  }

  @Test
  void citingARunWithNoStepsIsACorrection() {
    final UUID unknown = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                shop()
                    .cite(
                        new RunContext(unknown, "analyst"),
                        new Citation(List.of(ORDERS), List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(unknown.toString());
  }
}
