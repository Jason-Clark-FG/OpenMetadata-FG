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
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openmetadata.it.auth.JwtAuthProvider;
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
 * The final step of a turn against a real store, for a user who is not an admin: a cite that
 * carries a correction saves it, the run sees it at once, a new run sees it once the graph holds
 * it, the same correction again is not saved, and one about a node the run never saw is refused.
 */
@ExtendWith(TestNamespaceExtension.class)
public class RdfKnowledgeGraphLearningIT extends McpTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(RdfKnowledgeGraphLearningIT.class);
  private static final String TOOL = "knowledge_graph";
  private static final String BASE_URI = "https://open-metadata.org/";
  private static final String RULE = "Orders with at most one line are test tickets";
  private static final Duration WAIT = Duration.ofSeconds(120);
  private static final Duration POLL = Duration.ofSeconds(1);

  private static String analyst;

  @BeforeAll
  static void setUp() throws Exception {
    assumeTrue(RdfTestUtils.isRdfEnabled(), "Runs in the RDF lane, where the store is on");
    initAuth();
    final String name = "kg_learner_" + System.nanoTime();
    final JsonNode user =
        post(
            "users",
            Map.of("name", name, "email", name + "@test.openmetadata.org"),
            JsonNode.class);
    final String email = user.get("email").asText();
    analyst = "Bearer " + JwtAuthProvider.tokenFor(email, email, new String[] {}, 3_600);
  }

  @Test
  void aCorrectionInTheFinalCiteIsSavedSeenAtOnceAndNotSavedTwice(final TestNamespace namespace)
      throws Exception {
    final Table orders = table(namespace, "kgLearnOrders");
    final String ordersIri = BASE_URI + "entity/table/" + orders.getId();
    final String run = awaitCard(orders, ordersIri).path("run").asText();

    final long cited = System.nanoTime();
    final JsonNode saved = cite(run, List.of(correction(ordersIri))).path("learned").path(0);
    LOG.info("[KnowledgeGraph] the cite that saved a memory took {} ms", elapsedMs(cited));
    assertThat(saved.path("saved").path("title").asText()).as(saved.toString()).isEqualTo(RULE);
    assertThat(saved.path("about").asText()).isEqualTo(ordersIri);
    final String memory = saved.path("saved").path("id").asText();

    final JsonNode sameRun = cardFor(find(orders.getName(), run), ordersIri);
    assertThat(sameRun.path("memories").toString()).as("in force at once").contains(RULE);
    awaitMemoryInNewRun(orders, ordersIri);
    LOG.info(
        "[KnowledgeGraph] a saved memory reached a new run's card after {} ms", elapsedMs(cited));

    final String second = awaitCard(orders, ordersIri).path("run").asText();
    final JsonNode again = cite(second, List.of(correction(ordersIri))).path("learned").path(0);
    assertThat(again.has("saved")).isFalse();
    assertThat(again.path("sameAs").asText()).isEqualTo(memory);
    final String unseenIri = BASE_URI + "entity/table/" + UUID.randomUUID();
    final JsonNode refused = cite(second, List.of(correction(unseenIri))).path("learned").path(0);
    assertThat(refused.path("reason").asText()).contains("'about'", unseenIri);
  }

  /** A plain create, timed, to tell the create's own cost from the graph queue's. */
  @Test
  void aMemoryCreatedThroughTheApiIsTimedToItsCard(final TestNamespace namespace) throws Exception {
    final Table table = table(namespace, "kgLearnTimed");
    final String iri = BASE_URI + "entity/table/" + table.getId();
    awaitCard(table, iri);
    final Map<String, Object> body = new HashMap<>();
    body.put("name", namespace.prefix("timed"));
    body.put("title", "Timed note");
    body.put("question", "What is timed?");
    body.put("answer", "This note is timed.");
    body.put("memoryScope", "EntityScoped");
    body.put("shareConfig", Map.of("visibility", "Entity"));
    body.put("primaryEntity", Map.of("id", table.getId().toString(), "type", Entity.TABLE));

    final long started = System.nanoTime();
    post("contextCenter/memories", body, JsonNode.class);
    LOG.info("[KnowledgeGraph] the memory create request took {} ms", elapsedMs(started));
    Awaitility.await("the timed memory reaches its card")
        .atMost(WAIT)
        .pollInterval(POLL)
        .ignoreExceptions()
        .until(
            () ->
                cardFor(find(table.getName(), null), iri)
                    .path("memories")
                    .toString()
                    .contains("Timed note"));
    LOG.info(
        "[KnowledgeGraph] the created memory reached its card after {} ms", elapsedMs(started));
  }

  private static Map<String, Object> correction(final String about) {
    return Map.of(
        "title",
        RULE,
        "question",
        "Which orders are test tickets?",
        "answer",
        "Orders with zero or one line are test tickets; leave them out of order counts.",
        "kind",
        "correction",
        "about",
        about,
        "evidence",
        Map.of("source", "user", "quote", "an order with one line is a test ticket too"));
  }

  private static long elapsedMs(final long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  private static Table table(final TestNamespace namespace, final String name) {
    final DatabaseSchema schema =
        DatabaseSchemaTestFactory.createSimple(
            namespace, DatabaseServiceTestFactory.createPostgres(namespace));
    return Tables.create(
        new CreateTable()
            .withName(namespace.prefix(name))
            .withDatabaseSchema(schema.getFullyQualifiedName())
            .withColumns(List.of(ColumnBuilder.of("id", "BIGINT").build())));
  }

  /** Search indexes the table and the live writer projects it asynchronously. */
  private JsonNode awaitCard(final Table table, final String iri) {
    final JsonNode[] found = new JsonNode[1];
    Awaitility.await("the analyst's find shows the table's card")
        .atMost(WAIT)
        .pollInterval(POLL)
        .ignoreExceptions()
        .until(
            () -> {
              found[0] = find(table.getName(), null);
              return cardFor(found[0], iri).path("inGraph").asBoolean();
            });
    return found[0];
  }

  private void awaitMemoryInNewRun(final Table table, final String iri) {
    Awaitility.await("a new run's card shows the saved memory")
        .atMost(WAIT)
        .pollInterval(POLL)
        .ignoreExceptions()
        .until(
            () ->
                cardFor(find(table.getName(), null), iri)
                    .path("memories")
                    .toString()
                    .contains(RULE));
  }

  private JsonNode find(final String question, final String run) throws Exception {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("operation", "find");
    arguments.put("question", question);
    if (run != null) {
      arguments.put("run", run);
    }
    return call(arguments);
  }

  private JsonNode cite(final String run, final List<Map<String, Object>> learned)
      throws Exception {
    return call(Map.of("operation", "cite", "run", run, "learned", learned));
  }

  private JsonNode call(final Map<String, Object> arguments) throws Exception {
    final JsonNode result =
        executeMcpRequest(McpTestUtils.createToolCallRequest(TOOL, arguments), analyst)
            .path("result");
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
