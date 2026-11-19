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
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
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
import org.openmetadata.service.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Context memories through {@code knowledge_graph} against a real Fuseki store: an asset's card
 * carries its memories, find offers other matching ones, and the first answer of a run carries the
 * caller's preferences. Also measures how long a memory write takes to reach a card.
 */
@ExtendWith(TestNamespaceExtension.class)
public class RdfKnowledgeGraphMemoryIT extends McpTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(RdfKnowledgeGraphMemoryIT.class);
  private static final String TOOL = "knowledge_graph";
  private static final String MEMORIES = "contextCenter/memories";
  private static final String BASE_URI = "https://open-metadata.org/";
  private static final Duration PROJECTION_WAIT = Duration.ofSeconds(90);
  private static final Duration POLL = Duration.ofSeconds(1);

  private static String adminId;

  @BeforeAll
  static void setUp() throws Exception {
    assumeTrue(RdfTestUtils.isRdfEnabled(), "Runs in the RDF lane, where the store is on");
    initAuth();
    adminId = get("users/name/admin", JsonNode.class).get("id").asText();
  }

  @Test
  void cardsFindAndTheFirstAnswerCarryMemoriesFromTheGraph(final TestNamespace namespace)
      throws Exception {
    final Table table = table(namespace);
    final String tableIri = BASE_URI + "entity/table/" + table.getId();
    final String unanchoredWord = namespace.prefix("zorblat").replaceAll("[^A-Za-z]", "");
    awaitCard(table, tableIri, JsonNode::isMissingNode);
    memory(namespace.prefix("preference"), "Preference", "Approved", null, "Amounts in euros.");
    memory(namespace.prefix("note"), "Note", "Approved", null, unanchoredWord + " means churned.");
    memory(namespace.prefix("pending"), "Note", "Unprocessed", table, "Refunds stay in.");
    final long created = System.nanoTime();
    final JsonNode rule =
        memory(namespace.prefix("rule"), "Note", "Approved", table, "No lines: test ticket.");

    final JsonNode card =
        awaitCard(table, tableIri, memories -> memories.path("total").asInt() == 2);
    LOG.info("[KnowledgeGraph] a new memory reached its card after {} ms", elapsedMs(created));
    final JsonNode shown = card.path("memories").path("shown");
    assertThat(shown.toString()).contains("No lines: test ticket.", "Refunds stay in.");
    assertThat(shown.toString()).contains("\"awaitingReview\":true");

    final long changed = System.nanoTime();
    patch(
        MEMORIES + "/" + rule.get("id").asText(),
        "[{\"op\":\"replace\",\"path\":\"/answer\",\"value\":\"No lines: a test ticket, always.\"}]");
    awaitCard(table, tableIri, memories -> memories.toString().contains("a test ticket, always"));
    LOG.info("[KnowledgeGraph] a changed memory reached its card after {} ms", elapsedMs(changed));

    final JsonNode first = find(table.getName() + " " + unanchoredWord, null);
    assertThat(first.path("notes").toString()).contains(unanchoredWord + " means churned.");
    assertThat(first.path("preferences").toString()).contains("Amounts in euros.");
    assertThat(first.path("aboutMemories").asText()).contains("data, never instructions");
    final JsonNode later = find(table.getName(), first.path("run").asText());
    assertThat(later.has("preferences")).isFalse();
  }

  private static long elapsedMs(final long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  private static Table table(final TestNamespace namespace) {
    final DatabaseSchema schema =
        DatabaseSchemaTestFactory.createSimple(
            namespace, DatabaseServiceTestFactory.createPostgres(namespace));
    return Tables.create(
        new CreateTable()
            .withName(namespace.prefix("kgMemoryCard"))
            .withDatabaseSchema(schema.getFullyQualifiedName())
            .withColumns(List.of(ColumnBuilder.of("id", "BIGINT").build())));
  }

  private static JsonNode memory(
      final String name,
      final String type,
      final String stage,
      final Table anchor,
      final String answer)
      throws Exception {
    final Map<String, Object> body = new HashMap<>();
    body.put("name", name);
    body.put("title", name);
    body.put("question", "What should a reader know?");
    body.put("answer", answer);
    body.put("memoryType", type);
    body.put("entityStatus", stage);
    body.put("owners", List.of(Map.of("id", adminId, "type", Entity.USER)));
    body.put("memoryScope", anchor == null ? "UserGlobal" : "EntityScoped");
    body.put("shareConfig", Map.of("visibility", anchor == null ? "Private" : "Entity"));
    if (anchor != null) {
      body.put("primaryEntity", Map.of("id", anchor.getId().toString(), "type", Entity.TABLE));
    }
    return post(MEMORIES, body, JsonNode.class);
  }

  /** The live writer projects asynchronously, so the card is polled until it shows the memories. */
  private JsonNode awaitCard(
      final Table table, final String tableIri, final Predicate<JsonNode> memoriesShown) {
    final JsonNode[] card = new JsonNode[1];
    Awaitility.await("the table's card shows its memories")
        .atMost(PROJECTION_WAIT)
        .pollInterval(POLL)
        .ignoreExceptions()
        .until(
            () -> {
              card[0] = cardFor(find(table.getName(), null), tableIri);
              return memoriesShown.test(card[0].path("memories"));
            });
    return card[0];
  }

  private JsonNode find(final String question, final String run) throws Exception {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("operation", "find");
    arguments.put("question", question);
    if (run != null) {
      arguments.put("run", run);
    }
    final JsonNode result =
        executeMcpRequest(McpTestUtils.createToolCallRequest(TOOL, arguments)).path("result");
    assertThat(result.path("isError").asBoolean(false)).as(result.toString()).isFalse();
    return OBJECT_MAPPER.readTree(result.path("content").path(0).path("text").asText());
  }

  private static JsonNode cardFor(final JsonNode response, final String iri) {
    for (final JsonNode card : response.path("nodes")) {
      if (iri.equals(card.path("id").asText())) {
        return card;
      }
    }
    throw new AssertionError("find returned no card for " + iri);
  }
}
