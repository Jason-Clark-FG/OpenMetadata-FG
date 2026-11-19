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
package org.openmetadata.it.tests.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.admin;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersona;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersonaHarness;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.uniqueName;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openmetadata.it.tests.mcp.McpTestBase;
import org.openmetadata.it.tests.mcp.McpTestUtils;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.entity.context.AgentHarnessMemory;
import org.openmetadata.schema.entity.context.AgentMemoryReview;
import org.openmetadata.schema.entity.context.AgentMemoryVisibility;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.User;

/**
 * An agent records a fact through the remember tool as the signed-in user, and the memory follows
 * the memory settings of the harness that user is served by.
 */
@Execution(ExecutionMode.CONCURRENT)
@ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK, mode = ResourceAccessMode.READ)
class RememberToolIT extends McpTestBase {
  private static final String DESKTOP = "Claude/1.0 (Claude Desktop)";
  private static final String REMEMBER = "remember";

  @BeforeAll
  static void setup() throws Exception {
    initAuth();
  }

  @Test
  void aPersonaMemoryIsLinkedToTheAssetAndReachesAnotherHolderButNotAnOutsider() throws Exception {
    final Team team =
        team(
            "persona_share",
            AgentMemoryReview.STEWARD_REVIEW,
            AgentMemoryVisibility.PERSONA,
            true,
            true);
    final Table table = createServiceDatabaseSchemaTable(uniqueName("remember"));
    final String fact = "Revenue in " + table.getName() + " is booked in cents, not dollars.";

    final JsonNode saved =
        remember(team.owner(), Map.of("fact", fact, "about", List.of(asset(table))));

    assertThat(saved.path("captured").asBoolean()).isTrue();
    assertThat(saved.path("status").asText()).isEqualTo("Approved");
    assertThat(saved.path("visibility").asText()).isEqualTo("Shared");
    assertThat(saved.path("linkedAssets")).hasSize(1);
    assertThat(saved.path("linkedAssets").get(0).path("fqn").asText())
        .isEqualTo(table.getFullyQualifiedName());
    assertThat(saved.path("linkedAssets").get(0).path("type").asText()).isEqualTo("table");
    assertThat(saved.path("url").asText())
        .contains("/context-center/memories?memory=agent-memory-" + saved.path("id").asText());
    assertThat(saved.path("fact").asText()).isEqualTo(fact);
    assertThat(saved.path("savedAt").asLong()).isPositive();
    assertThat(saved.path("sharedWith")).hasSize(1);
    final String id = saved.path("id").asText();

    final JsonNode seenByOwner = memory(team.owner(), id);
    assertThat(seenByOwner.path("sourceType").asText()).isEqualTo("AgentCapture");
    assertThat(seenByOwner.path("answer").asText()).isEqualTo(fact);
    assertThat(seenByOwner.path("primaryEntity").path("fullyQualifiedName").asText())
        .isEqualTo(table.getFullyQualifiedName());
    assertThat(seenByOwner.path("owners").get(0).path("name").asText())
        .isEqualTo(team.owner().user().getName());
    assertThat(status(team.peer(), id)).isEqualTo(200);
    assertThat(status(team.outsider(), id)).isEqualTo(403);
    org.awaitility.Awaitility.await("the agent memory is listed by its source")
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () ->
                assertThat(listedBySource(team.owner(), "AgentCapture").findValuesAsText("id"))
                    .contains(id));
    assertThat(listedBySource(team.owner(), "Manual").findValuesAsText("id")).doesNotContain(id);
    assertThat(
            getResponse("contextCenter/memories?sourceType=Bogus", team.owner().token())
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void authorOnlyKeepsTheMemoryPrivateToItsAuthor() throws Exception {
    final Team team =
        team(
            "author_only",
            AgentMemoryReview.AUTHOR_ONLY,
            AgentMemoryVisibility.PRIVATE,
            true,
            true);
    final Table table = createServiceDatabaseSchemaTable(uniqueName("remember"));

    final JsonNode saved =
        remember(
            team.owner(),
            Map.of(
                "fact",
                "Only finance may read " + table.getName(),
                "about",
                List.of(asset(table))));

    assertThat(saved.path("visibility").asText()).isEqualTo("Private");
    assertThat(status(team.owner(), saved.path("id").asText())).isEqualTo(200);
    assertThat(status(team.peer(), saved.path("id").asText())).isEqualTo(403);
    assertThat(status(team.outsider(), saved.path("id").asText())).isEqualTo(403);
  }

  @Test
  void aRestatementReturnsTheExistingMemoryInsteadOfSavingASecondOne() throws Exception {
    final Team team =
        team("dedup", AgentMemoryReview.STEWARD_REVIEW, AgentMemoryVisibility.PRIVATE, true, true);
    final Table table = createServiceDatabaseSchemaTable(uniqueName("remember"));
    final String fact = "Use " + table.getName() + " for finance reporting.";

    final JsonNode first =
        remember(team.owner(), Map.of("fact", fact, "about", List.of(asset(table))));
    final JsonNode again =
        remember(
            team.owner(),
            Map.of(
                "fact",
                "  use " + table.getName().toUpperCase() + " for finance reporting ",
                "about",
                List.of(asset(table))));

    assertThat(first.path("captured").asBoolean()).isTrue();
    assertThat(again.path("captured").asBoolean()).isFalse();
    assertThat(again.path("deduplicatedInto").asText()).isEqualTo(first.path("id").asText());
    assertThat(again.path("id").asText()).isEqualTo(first.path("id").asText());
    final JsonNode listed =
        OBJECT_MAPPER.readTree(
            getResponse(
                    "contextCenter/memories?primaryEntityId=" + table.getId(), team.owner().token())
                .body());
    assertThat(listed.path("data")).hasSize(1);
  }

  @Test
  void whenDedupIsOffARestatementIsSavedAgain() throws Exception {
    final Team team =
        team(
            "no_dedup",
            AgentMemoryReview.STEWARD_REVIEW,
            AgentMemoryVisibility.PRIVATE,
            true,
            false);
    final Table table = createServiceDatabaseSchemaTable(uniqueName("remember"));
    final Map<String, Object> arguments =
        Map.of("fact", "Prefer " + table.getName(), "about", List.of(asset(table)));

    final JsonNode first = remember(team.owner(), arguments);
    final JsonNode second = remember(team.owner(), arguments);

    assertThat(second.path("captured").asBoolean()).isTrue();
    assertThat(second.path("id").asText()).isNotEqualTo(first.path("id").asText());
  }

  @Test
  void whenCaptureIsOffNothingIsSaved() throws Exception {
    final Team team =
        team(
            "capture_off",
            AgentMemoryReview.STEWARD_REVIEW,
            AgentMemoryVisibility.PRIVATE,
            false,
            true);
    final Table table = createServiceDatabaseSchemaTable(uniqueName("remember"));

    final JsonNode result =
        callTool(
            team.owner(), REMEMBER, Map.of("fact", "Anything", "about", List.of(asset(table))));

    assertThat(result.path("isError").asBoolean()).isFalse();
    assertThat(result.path("structuredContent").path("captured").asBoolean()).isFalse();
    assertThat(result.path("content").get(0).path("text").asText()).contains("turned off");
    final JsonNode listed =
        OBJECT_MAPPER.readTree(
            getResponse(
                    "contextCenter/memories?primaryEntityId=" + table.getId(), team.owner().token())
                .body());
    assertThat(listed.path("data")).isEmpty();
  }

  @Test
  void theMemoryIsAlsoLinkedToWhatTheConversationReadAndSkipsWhatTheCallerCannotFind()
      throws Exception {
    final Team team =
        team(
            "session", AgentMemoryReview.STEWARD_REVIEW, AgentMemoryVisibility.PRIVATE, true, true);
    final Table named = createServiceDatabaseSchemaTable(uniqueName("remember"));
    final Table read = createServiceDatabaseSchemaTable(uniqueName("remember"));
    final JsonNode details =
        callTool(
            team.owner(),
            "get_entity_details",
            Map.of("entityType", "table", "fqn", read.getFullyQualifiedName()));
    assertThat(details.path("isError").asBoolean(false)).as(details.toString()).isFalse();

    final JsonNode saved =
        remember(
            team.owner(),
            Map.of(
                "fact",
                "Join " + named.getName() + " to " + read.getName() + " on order_id.",
                "about",
                List.of(asset(named), Map.of("fqn", "no.such.table", "entityType", "table"))));

    assertThat(saved.path("linkedAssets").findValuesAsText("fqn"))
        .containsExactly(named.getFullyQualifiedName(), read.getFullyQualifiedName());
    assertThat(saved.path("skipped").get(0).asText()).isEqualTo("no.such.table");
    final JsonNode stored = memory(team.owner(), saved.path("id").asText());
    assertThat(stored.path("primaryEntity").path("fullyQualifiedName").asText())
        .isEqualTo(named.getFullyQualifiedName());
    assertThat(stored.path("relatedEntities").get(0).path("fullyQualifiedName").asText())
        .isEqualTo(read.getFullyQualifiedName());
  }

  @Test
  void aFactOverTheLimitIsRefused() throws Exception {
    final Team team =
        team(
            "too_long",
            AgentMemoryReview.STEWARD_REVIEW,
            AgentMemoryVisibility.PRIVATE,
            true,
            true);

    final HttpResponse<String> response =
        post(team.owner(), REMEMBER, Map.of("fact", "x".repeat(2001)));

    final JsonNode result =
        OBJECT_MAPPER.readTree(extractJsonFromResponse(response.body())).path("result");
    assertThat(result.path("isError").asBoolean()).isTrue();
    assertThat(result.path("content").get(0).path("text").asText()).contains("2000");
  }

  private record Caller(User user, String token) {}

  private record Team(Caller owner, Caller peer, Caller outsider) {}

  /** An owner and a peer who hold a persona whose harness carries the memory settings, and an outsider. */
  private static Team team(
      final String prefix,
      final AgentMemoryReview review,
      final AgentMemoryVisibility visibility,
      final boolean capture,
      final boolean dedup)
      throws Exception {
    final User owner = AgentHarnessApi.createUser(uniqueName(prefix + "_owner"));
    final User peer = AgentHarnessApi.createUser(uniqueName(prefix + "_peer"));
    final User outsider = AgentHarnessApi.createUser(uniqueName(prefix + "_out"));
    final Persona persona = createPersona(uniqueName(prefix), List.of(owner.getId(), peer.getId()));
    for (final User holder : List.of(owner, peer)) {
      patch(
          "users/" + holder.getId(),
          "[{\"op\":\"add\",\"path\":\"/defaultPersona\",\"value\":{\"id\":\""
              + persona.getId()
              + "\",\"type\":\"persona\"}}]");
    }
    final AgentHarnessState created = createPersonaHarness(persona);
    final AgentHarnessSpec spec = created.getDraft();
    spec.setMemory(
        new AgentHarnessMemory()
            .withCapture(capture)
            .withLinkAssets(true)
            .withDedup(dedup)
            .withReview(review)
            .withVisibility(visibility));
    final UUID id = created.getPublished().getId();
    final AgentHarnessState drafted =
        admin("PUT", "/" + id + "/draft", spec, created.getDraftRevision());
    admin("POST", "/" + id + "/publish", null, drafted.getDraftRevision());
    return new Team(
        new Caller(owner, AgentHarnessApi.tokenFor(owner)),
        new Caller(peer, AgentHarnessApi.tokenFor(peer)),
        new Caller(outsider, AgentHarnessApi.tokenFor(outsider)));
  }

  private static Map<String, Object> asset(final Table table) {
    return Map.of("fqn", table.getFullyQualifiedName(), "entityType", "table");
  }

  private static JsonNode memory(final Caller caller, final String id) throws Exception {
    final HttpResponse<String> response =
        getResponse(
            "contextCenter/memories/" + id + "?fields=primaryEntity,relatedEntities,owners",
            caller.token());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return OBJECT_MAPPER.readTree(response.body());
  }

  private static JsonNode listedBySource(final Caller caller, final String sourceType)
      throws Exception {
    final HttpResponse<String> response =
        getResponse("contextCenter/memories?limit=100&sourceType=" + sourceType, caller.token());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return OBJECT_MAPPER.readTree(response.body()).path("data");
  }

  private static int status(final Caller caller, final String id) throws Exception {
    return getResponse("contextCenter/memories/" + id, caller.token()).statusCode();
  }

  private JsonNode remember(final Caller caller, final Map<String, Object> arguments)
      throws Exception {
    final JsonNode result = callTool(caller, REMEMBER, arguments);
    assertThat(result.path("isError").asBoolean(false)).as(result.toString()).isFalse();
    return result.path("structuredContent");
  }

  private JsonNode callTool(
      final Caller caller, final String tool, final Map<String, Object> arguments)
      throws Exception {
    final HttpResponse<String> response = post(caller, tool, arguments);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return OBJECT_MAPPER.readTree(extractJsonFromResponse(response.body())).path("result");
  }

  private HttpResponse<String> post(
      final Caller caller, final String tool, final Map<String, Object> arguments)
      throws Exception {
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(getMcpUrl("/mcp")))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", caller.token())
            .header("User-Agent", DESKTOP)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    OBJECT_MAPPER.writeValueAsString(
                        McpTestUtils.createToolCallRequest(tool, new HashMap<>(arguments)))))
            .timeout(Duration.ofSeconds(30))
            .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
