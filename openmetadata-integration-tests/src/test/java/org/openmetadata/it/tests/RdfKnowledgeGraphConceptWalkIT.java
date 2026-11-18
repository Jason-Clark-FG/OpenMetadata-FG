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

package org.openmetadata.it.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.openmetadata.it.tests.OntologyBindingFixtures.admin;
import static org.openmetadata.it.tests.OntologyBindingFixtures.bind;
import static org.openmetadata.it.tests.OntologyBindingFixtures.unbind;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openmetadata.it.factories.DatabaseSchemaTestFactory;
import org.openmetadata.it.tests.mcp.McpTestBase;
import org.openmetadata.it.tests.mcp.McpTestUtils;
import org.openmetadata.it.util.RdfTestUtils;
import org.openmetadata.it.util.TestNamespace;
import org.openmetadata.it.util.TestNamespaceExtension;
import org.openmetadata.schema.api.data.CreateOntologyProperty;
import org.openmetadata.schema.api.data.CreateTable;
import org.openmetadata.schema.entity.data.Ontology;
import org.openmetadata.schema.entity.data.OntologyConcept;
import org.openmetadata.schema.entity.data.OntologyProperty;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnDataType;
import org.openmetadata.schema.type.OntologyColumnBinding;
import org.openmetadata.schema.type.OntologyPropertyKind;
import org.openmetadata.schema.type.TableConstraint;
import org.openmetadata.service.rdf.RdfUtils;

/**
 * The acceptance walk of the knowledge-graph design, on a live server with RDF on: a caller starts
 * from a business concept bound in Ontology Studio and reaches the table and the column that
 * implement it through the {@code knowledge_graph} tool alone, then reads back what it looked at.
 * Fixtures are made through the public APIs; the graph is read only through the tool.
 */
@ExtendWith(TestNamespaceExtension.class)
class RdfKnowledgeGraphConceptWalkIT extends McpTestBase {

  private static final String BASE_URI = "https://open-metadata.org/";
  private static final String TOOL = "knowledge_graph";
  private static final String BOUND_ASSET = "om:boundAsset";
  private static final String BOUND_COLUMN = "om:boundColumn";
  private static final String HAS_COLUMN = "om:hasColumn";
  private static final String HAS_CONSTRAINT = "om:hasConstraint";
  private static final String OUTGOING = "outgoing";
  private static final String INCOMING = "incoming";
  private static final String EXPLORED = "explored";
  private static final String TENANT_ID = "tenant_id";
  private static final String SUBSCRIBER_ID = "subscriber_id";
  private static final String POSTAL_CODE = "postal_code";
  private static final Duration PROJECTION_WAIT = Duration.ofSeconds(90);

  @BeforeAll
  static void setUp() throws Exception {
    assumeTrue(RdfTestUtils.isRdfEnabled(), "Runs in the RDF lane, where the store is on");
    initAuth();
  }

  @AfterEach
  void cleanup(final TestNamespace ns) {
    BaseOntologyIT.deleteRoots(ns);
  }

  @Test
  void aCallerWalksFromABoundConceptToTheTableAndColumnThatImplementIt(final TestNamespace ns)
      throws Exception {
    final Walk walk = boundWalk(ns);

    final JsonNode toTable = awaitTableBehindConcept(walk);
    final String run = toTable.path("run").asText();
    assertTableCard(card(toTable, walk.tableIri()));
    final JsonNode backToConcept = expand(run, walk.tableIri(), BOUND_ASSET, INCOMING);
    assertConceptCard(card(backToConcept, walk.conceptIri()), 1L);
    final JsonNode columns = expand(run, walk.tableIri(), HAS_COLUMN, OUTGOING);
    assertReachable(card(columns, walk.boundColumnIri()), BOUND_COLUMN, INCOMING, 1L);
    assertThat(reachable(card(columns, walk.keyColumnIri()), BOUND_COLUMN, INCOMING)).isEmpty();
    assertTrace(call(Map.of("operation", "trace", "run", run)), walk);

    unbind(walk.concept(), walk.table(), 1L);
    awaitBindingGone(run, walk);
  }

  @Test
  void findStartsFromAQuestionNamingTheConceptAndSeesItsBindingComeAndGo(final TestNamespace ns)
      throws Exception {
    final Walk walk = boundWalk(ns);

    final String question = "which tables hold " + walk.concept().getName() + " records";
    awaitTermFound(question, walk.conceptIri(), card -> assertConceptCard(card, 1L));
    unbind(walk.concept(), walk.table(), 1L);
    awaitTermFound(
        question,
        walk.conceptIri(),
        card -> assertThat(reachable(card, BOUND_ASSET, OUTGOING)).isEmpty());
  }

  @Test
  void findStartsFromAQuestionNamingThePropertyAndReachesTheColumnItIsBoundTo(
      final TestNamespace ns) throws Exception {
    final Walk walk = boundWalk(ns);

    final JsonNode found =
        awaitTermFound(
            "which column holds the " + walk.property().getDisplayName(),
            walk.propertyIri(),
            card -> assertReachable(card, BOUND_COLUMN, OUTGOING, 1L));
    final JsonNode column =
        expand(found.path("run").asText(), walk.propertyIri(), BOUND_COLUMN, OUTGOING);

    assertThat(farNodes(column, walk.propertyIri(), BOUND_COLUMN, OUTGOING))
        .containsExactly(walk.boundColumnIri());
  }

  private static Walk boundWalk(final TestNamespace ns) {
    final Ontology ontology = BaseOntologyApprovalIT.createOntology(ns);
    final String conceptName = "Subscriber" + ns.shortPrefix();
    final OntologyConcept concept =
        BaseOntologyApprovalIT.declareClass(
            ontology, conceptName, URI.create(ontology.getIri() + "/" + conceptName));
    final OntologyProperty postalCode = postalCode(ontology, concept, ns);
    BaseOntologyApprovalIT.activate(ns, ontology);
    final Table table = subscribersTable(ns);
    bind(
        concept,
        table,
        List.of(
            new OntologyColumnBinding()
                .withPropertyId(postalCode.getId())
                .withColumnPath(List.of(POSTAL_CODE))),
        0L);
    return new Walk(concept, postalCode, table);
  }

  private static OntologyProperty postalCode(
      final Ontology ontology, final OntologyConcept domain, final TestNamespace ns) {
    return admin()
        .ontologyProperties()
        .create(
            new CreateOntologyProperty()
                .withName("postalCode")
                .withDisplayName("Postal code " + ns.shortPrefix())
                .withOntology(ontology.getFullyQualifiedName())
                .withKind(OntologyPropertyKind.DATA)
                .withDomain(List.of(domain.getIri()))
                .withRange(List.of(URI.create("http://www.w3.org/2001/XMLSchema#string"))));
  }

  private static Table subscribersTable(final TestNamespace ns) {
    return admin()
        .tables()
        .create(
            new CreateTable()
                .withName(ns.prefix("subscribers"))
                .withDatabaseSchema(
                    DatabaseSchemaTestFactory.createSimple(ns).getFullyQualifiedName())
                .withColumns(
                    List.of(
                        column(TENANT_ID, ColumnDataType.BIGINT),
                        column(SUBSCRIBER_ID, ColumnDataType.BIGINT),
                        column(POSTAL_CODE, ColumnDataType.STRING)))
                .withTableConstraints(
                    List.of(
                        new TableConstraint()
                            .withConstraintType(TableConstraint.ConstraintType.PRIMARY_KEY)
                            .withColumns(List.of(TENANT_ID, SUBSCRIBER_ID)))));
  }

  private static Column column(final String name, final ColumnDataType type) {
    return new Column().withName(name).withDataType(type);
  }

  /** The binding reaches the graph through the bound table's queued write, so expand is polled. */
  private JsonNode awaitTableBehindConcept(final Walk walk) {
    final List<JsonNode> expanded = new ArrayList<>();
    Awaitility.await("the concept's binding edge reaches the serving graph")
        .atMost(PROJECTION_WAIT)
        .pollInterval(Duration.ofSeconds(1))
        .ignoreExceptions()
        .untilAsserted(
            () -> {
              final JsonNode response = expand(null, walk.conceptIri(), BOUND_ASSET, OUTGOING);
              assertThat(farNodes(response, walk.conceptIri(), BOUND_ASSET, OUTGOING))
                  .containsExactly(walk.tableIri());
              expanded.add(response);
            });
    return expanded.getLast();
  }

  private void awaitBindingGone(final String run, final Walk walk) throws Exception {
    Awaitility.await("removing the binding removes its edges from the serving graph")
        .atMost(PROJECTION_WAIT)
        .pollInterval(Duration.ofSeconds(1))
        .ignoreExceptions()
        .untilAsserted(
            () ->
                assertThat(
                        expand(run, walk.conceptIri(), BOUND_ASSET, OUTGOING)
                            .path("expansions")
                            .isEmpty())
                    .isTrue());
    final JsonNode columns = expand(run, walk.tableIri(), HAS_COLUMN, OUTGOING);
    assertThat(reachable(card(columns, walk.boundColumnIri()), BOUND_COLUMN, INCOMING)).isEmpty();
    assertThat(reachable(tableCardFound(walk), BOUND_ASSET, INCOMING)).isEmpty();
  }

  private JsonNode tableCardFound(final Walk walk) throws Exception {
    final JsonNode found =
        call(
            Map.of(
                "operation",
                "find",
                "question",
                walk.table().getName(),
                "entityTypes",
                List.of("table")));
    return card(found, walk.tableIri());
  }

  private JsonNode awaitTermFound(
      final String question, final String termIri, final Consumer<JsonNode> expectation) {
    final List<JsonNode> found = new ArrayList<>();
    Awaitility.await("find returns the card of " + termIri)
        .atMost(PROJECTION_WAIT)
        .pollInterval(Duration.ofSeconds(1))
        .ignoreExceptions()
        .untilAsserted(
            () -> {
              final JsonNode response = call(Map.of("operation", "find", "question", question));
              final JsonNode term = card(response, termIri);
              assertThat(term.path("inGraph").asBoolean()).isTrue();
              expectation.accept(term);
              found.add(response);
            });
    return found.getLast();
  }

  private static void assertTableCard(final JsonNode table) {
    assertReachable(table, BOUND_ASSET, INCOMING, 1L);
    assertReachable(table, HAS_COLUMN, OUTGOING, 3L);
    assertReachable(table, HAS_CONSTRAINT, OUTGOING, 1L);
  }

  private static void assertConceptCard(final JsonNode concept, final long boundAssets) {
    final JsonNode binding = assertReachable(concept, BOUND_ASSET, OUTGOING, boundAssets);
    assertThat(binding.path("about").path("comment").asText()).isNotBlank();
  }

  private static void assertTrace(final JsonNode trace, final Walk walk) {
    assertThat(steps(trace))
        .containsExactly(
            new Step(1, "expand", walk.conceptIri(), BOUND_ASSET),
            new Step(2, "expand", walk.tableIri(), BOUND_ASSET),
            new Step(3, "expand", walk.tableIri(), HAS_COLUMN));
    assertThat(edge(trace, walk.conceptIri(), BOUND_ASSET, walk.tableIri()))
        .as("the concept to table edge the walk followed")
        .hasValueSatisfying(edge -> assertThat(edge.path("state").asText()).isEqualTo(EXPLORED));
    assertThat(edge(trace, walk.tableIri(), HAS_COLUMN, walk.boundColumnIri()))
        .as("the table to bound column edge the walk followed")
        .hasValueSatisfying(edge -> assertThat(edge.path("firstStep").asInt()).isEqualTo(3));
  }

  private static List<Step> steps(final JsonNode trace) {
    return stream(trace.path("steps"))
        .map(
            step ->
                new Step(
                    step.path("sequence").asInt(),
                    step.path("operation").asText(),
                    step.path("input").path("nodes").path(0).asText(),
                    step.path("input").path("relationships").path(0).asText()))
        .toList();
  }

  private static Optional<JsonNode> edge(
      final JsonNode trace, final String node, final String relationship, final String other) {
    return stream(trace.path("edges"))
        .filter(edge -> node.equals(edge.path("node").asText()))
        .filter(edge -> relationship.equals(edge.path("relationship").asText()))
        .filter(edge -> other.equals(edge.path("other").asText()))
        .findFirst();
  }

  private static JsonNode assertReachable(
      final JsonNode card, final String relationship, final String direction, final long count) {
    final Optional<JsonNode> entry = reachable(card, relationship, direction);
    assertThat(entry).as("%s %s on %s", relationship, direction, card).isPresent();
    assertThat(entry.get().path("count").asLong()).isEqualTo(count);
    return entry.get();
  }

  private static Optional<JsonNode> reachable(
      final JsonNode card, final String relationship, final String direction) {
    return stream(card.path("reachable"))
        .filter(entry -> relationship.equals(entry.path("relationship").asText()))
        .filter(entry -> direction.equals(entry.path("direction").asText()))
        .findFirst();
  }

  private static List<String> farNodes(
      final JsonNode expanded,
      final String node,
      final String relationship,
      final String direction) {
    return stream(expanded.path("expansions"))
        .filter(group -> node.equals(group.path("node").asText()))
        .filter(group -> relationship.equals(group.path("relationship").asText()))
        .filter(group -> direction.equals(group.path("direction").asText()))
        .flatMap(group -> stream(group.path("nodes")))
        .map(JsonNode::asText)
        .toList();
  }

  private static JsonNode card(final JsonNode response, final String iri) {
    return stream(response.path("nodes"))
        .filter(card -> iri.equals(card.path("id").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no card for " + iri + " in " + response));
  }

  private static Stream<JsonNode> stream(final JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false);
  }

  private JsonNode expand(
      final String run, final String node, final String relationship, final String direction)
      throws Exception {
    final Map<String, Object> arguments =
        new HashMap<>(
            Map.of(
                "operation",
                "expand",
                "nodes",
                List.of(node),
                "relationships",
                List.of(relationship),
                "direction",
                direction));
    Optional.ofNullable(run).ifPresent(value -> arguments.put("run", value));
    return call(arguments);
  }

  private JsonNode call(final Map<String, Object> arguments) throws Exception {
    final JsonNode result =
        executeMcpRequest(McpTestUtils.createToolCallRequest(TOOL, arguments)).path("result");
    assertThat(result.path("isError").asBoolean(false)).as(result.toString()).isFalse();
    return OBJECT_MAPPER.readTree(result.path("content").path(0).path("text").asText());
  }

  private record Step(int sequence, String operation, String node, String relationship) {}

  private record Walk(OntologyConcept concept, OntologyProperty property, Table table) {
    String conceptIri() {
      return concept.getIri().toString();
    }

    String propertyIri() {
      return property.getIri().toString();
    }

    String tableIri() {
      return RdfUtils.entityUri(BASE_URI, "table", table.getId());
    }

    String boundColumnIri() {
      return columnIri(POSTAL_CODE);
    }

    String keyColumnIri() {
      return columnIri(TENANT_ID);
    }

    private String columnIri(final String name) {
      return RdfUtils.columnUri(BASE_URI, table.getFullyQualifiedName() + "." + name);
    }
  }
}
