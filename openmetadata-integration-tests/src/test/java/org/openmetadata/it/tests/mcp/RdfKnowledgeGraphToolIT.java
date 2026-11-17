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

package org.openmetadata.it.tests.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openmetadata.it.factories.DatabaseSchemaTestFactory;
import org.openmetadata.it.factories.DatabaseServiceTestFactory;
import org.openmetadata.it.util.RdfTestUtils;
import org.openmetadata.it.util.TestNamespace;
import org.openmetadata.it.util.TestNamespaceExtension;
import org.openmetadata.schema.api.data.CreateTable;
import org.openmetadata.schema.entity.data.DatabaseSchema;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.sdk.fluent.Tables;
import org.openmetadata.sdk.fluent.builders.ColumnBuilder;

/**
 * The {@code knowledge_graph} tool against a real Fuseki store and the live projection: a table's
 * card is read through the bounded asserted-only path, one relationship is expanded, and the run
 * is traced in the server database.
 */
@ExtendWith(TestNamespaceExtension.class)
public class RdfKnowledgeGraphToolIT extends McpTestBase {

  private static final String BASE_URI = "https://open-metadata.org/";
  private static final String TOOL = "knowledge_graph";
  private static final String BELONGS_TO_SCHEMA = "om:belongsToSchema";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @BeforeAll
  static void setUp() throws Exception {
    assumeTrue(RdfTestUtils.isRdfEnabled(), "Runs in the RDF lane, where the store is on");
    initAuth();
  }

  @Test
  void aCardIsReadAndOneRelationshipExpandedFromTheLiveGraph(final TestNamespace namespace)
      throws Exception {
    final DatabaseSchema schema =
        DatabaseSchemaTestFactory.createSimple(
            namespace, DatabaseServiceTestFactory.createPostgres(namespace));
    final Table table =
        Tables.create(
            new CreateTable()
                .withName(namespace.prefix("kgCard"))
                .withDatabaseSchema(schema.getFullyQualifiedName())
                .withColumns(List.of(ColumnBuilder.of("id", "BIGINT").build())));
    RdfTestUtils.verifyEntityInRdf(table, "dcat:Dataset");
    final String tableIri = BASE_URI + "entity/table/" + table.getId();
    final String schemaIri = BASE_URI + "entity/databaseSchema/" + schema.getId();

    final String run = awaitCard(table.getName(), tableIri).path("run").asText();
    final JsonNode expanded =
        call(
            Map.of(
                "operation",
                "expand",
                "run",
                run,
                "nodes",
                List.of(tableIri),
                "relationships",
                List.of(BELONGS_TO_SCHEMA),
                "direction",
                "outgoing"));
    final JsonNode trace = call(Map.of("operation", "trace", "run", run));

    assertThat(expanded.path("expansions").path(0).path("nodes").path(0).asText())
        .isEqualTo(schemaIri);
    assertThat(expanded.path("nodes").path(0).path("label").asText()).isEqualTo(schema.getName());
    assertThat(trace.path("steps").size()).isEqualTo(2);
  }

  /** Search indexes the table asynchronously, so find is retried until the hit appears. */
  private JsonNode awaitCard(final String question, final String tableIri) {
    final List<JsonNode> found = new ArrayList<>();
    Awaitility.await("find returns the new table's card")
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .ignoreExceptions()
        .untilAsserted(
            () -> {
              final JsonNode response =
                  call(
                      Map.of(
                          "operation",
                          "find",
                          "question",
                          question,
                          "entityTypes",
                          List.of("table")));
              final JsonNode card = cardFor(response, tableIri);
              assertThat(card.path("inGraph").asBoolean()).isTrue();
              assertThat(card.path("reachable").toString())
                  .contains("\"relationship\":\"" + BELONGS_TO_SCHEMA + "\"");
              found.add(response);
            });
    return found.getLast();
  }

  private static JsonNode cardFor(final JsonNode response, final String iri) {
    for (final JsonNode card : response.path("nodes")) {
      if (iri.equals(card.path("id").asText())) {
        return card;
      }
    }
    throw new AssertionError("find returned no card for " + iri + ": " + response);
  }

  private JsonNode call(final Map<String, Object> arguments) throws Exception {
    final JsonNode result =
        executeMcpRequest(McpTestUtils.createToolCallRequest(TOOL, arguments)).path("result");
    assertThat(result.path("isError").asBoolean(false)).as(result.toString()).isFalse();
    return OBJECT_MAPPER.readTree(result.path("content").path(0).path("text").asText());
  }
}
