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
import static org.openmetadata.it.tests.harness.AgentHarnessApi.uniqueName;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openmetadata.it.tests.harness.RememberToolIT.Caller;
import org.openmetadata.it.tests.harness.RememberToolIT.Team;
import org.openmetadata.it.tests.mcp.McpTestBase;
import org.openmetadata.it.tests.mcp.McpTestUtils;
import org.openmetadata.schema.entity.context.AgentMemoryReview;
import org.openmetadata.schema.entity.context.AgentMemoryVisibility;
import org.openmetadata.schema.entity.data.Table;

/**
 * A fact the user states under an explicit cue in a prompt is saved by the UserPromptSubmit hook,
 * as that user and under their harness's memory settings, with no call from the model.
 */
@Execution(ExecutionMode.CONCURRENT)
@ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK, mode = ResourceAccessMode.READ)
class AutomaticMemoryCaptureIT extends McpTestBase {
  private static final String CLAUDE_CODE = "claude-cli/2.1.0 (external, cli)";

  @BeforeAll
  static void setup() throws Exception {
    initAuth();
  }

  @Test
  void aCuedFactIsSavedLinkedToItsTableAndReportedInTheHookOutput() throws Exception {
    final Team team =
        RememberToolIT.team(
            "auto_save",
            AgentMemoryReview.STEWARD_REVIEW,
            AgentMemoryVisibility.PERSONA,
            true,
            true);
    final Table table = searchableTable("arr_daily");
    final String fact = "monthly ARR is the " + table.getName() + " snapshot on the last day";

    final String context = promptContext(team.owner(), "Remember: " + fact);

    assertThat(context)
        .startsWith("Saved to OpenMetadata as a memory (Shared): Monthly ARR is the ")
        .contains("/context-center/memories?memory=")
        .contains("Tell the user it was saved.");
    final JsonNode memories = memoriesAbout(team.owner(), table);
    assertThat(memories).hasSize(1);
    final JsonNode stored =
        RememberToolIT.memory(team.owner(), memories.get(0).path("id").asText());
    assertThat(stored.path("sourceType").asText()).isEqualTo("AgentCapture");
    assertThat(stored.path("description").asText()).contains("automatically");
    assertThat(stored.path("answer").asText())
        .isEqualTo("Monthly ARR is the " + table.getName() + " snapshot on the last day");
    assertThat(stored.path("primaryEntity").path("fullyQualifiedName").asText())
        .isEqualTo(table.getFullyQualifiedName());
    assertThat(
            getResponse(
                    "contextCenter/memories/" + stored.path("id").asText(), team.outsider().token())
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void aQuestionOrAnUncuedStatementSavesNothing() throws Exception {
    final Team team =
        RememberToolIT.team(
            "auto_question",
            AgentMemoryReview.STEWARD_REVIEW,
            AgentMemoryVisibility.PRIVATE,
            true,
            true);
    final Table table = searchableTable("arr_daily");

    final String question =
        promptContext(team.owner(), "Should I remember that " + table.getName() + " is monthly?");
    final String plain = promptContext(team.owner(), table.getName() + " is a monthly snapshot");

    assertThat(question).doesNotContain("Saved to OpenMetadata");
    assertThat(plain).doesNotContain("Saved to OpenMetadata");
    assertThat(memoriesAbout(team.owner(), table)).isEmpty();
  }

  @Test
  void aReplayedPromptKeepsOneMemoryAndRepeatsTheNote() throws Exception {
    final Team team =
        RememberToolIT.team(
            "auto_replay",
            AgentMemoryReview.STEWARD_REVIEW,
            AgentMemoryVisibility.PRIVATE,
            true,
            true);
    final Table table = searchableTable("arr_daily");
    final String prompt = "Remember that " + table.getName() + " is rebuilt every night.";
    final String anchorless = "Remember that fiscal years start in February " + uniqueName("fy");

    final String first = promptContext(team.owner(), prompt);
    final String again = promptContext(team.owner(), prompt);
    final String anchorlessFirst = promptContext(team.owner(), anchorless);
    final String anchorlessAgain = promptContext(team.owner(), anchorless);

    assertThat(first).startsWith("Saved to OpenMetadata");
    assertThat(note(again)).isEqualTo(note(first));
    assertThat(note(anchorlessAgain)).isEqualTo(note(anchorlessFirst));
    assertThat(note(anchorlessFirst)).startsWith("Saved to OpenMetadata");
    assertThat(memoriesAbout(team.owner(), table)).hasSize(1);
    final String anchorlessFact = anchorless.substring("Remember that ".length());
    Awaitility.await("the anchorless memory is listed by its source")
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () ->
                assertThat(
                        OBJECT_MAPPER
                            .readTree(
                                getResponse(
                                        "contextCenter/memories?limit=100&fields=answer"
                                            + "&sourceType=AgentCapture",
                                        team.owner().token())
                                    .body())
                            .path("data")
                            .findValuesAsText("answer")
                            .stream()
                            .filter(
                                answer ->
                                    answer.toLowerCase().contains(anchorlessFact.toLowerCase()))
                            .count())
                    .isEqualTo(1));
  }

  @Test
  void whenCaptureIsOffNothingIsSavedAndNoNoteIsAdded() throws Exception {
    final Team team =
        RememberToolIT.team(
            "auto_off",
            AgentMemoryReview.STEWARD_REVIEW,
            AgentMemoryVisibility.PRIVATE,
            false,
            true);
    final Table table = searchableTable("arr_daily");

    final String context =
        promptContext(team.owner(), "Remember that " + table.getName() + " is rebuilt nightly");

    assertThat(context).doesNotContain("Saved to OpenMetadata");
    assertThat(memoriesAbout(team.owner(), table)).isEmpty();
  }

  @Test
  void aFactThatLooksLikeASecretIsNotSaved() throws Exception {
    final Team team =
        RememberToolIT.team(
            "auto_secret",
            AgentMemoryReview.STEWARD_REVIEW,
            AgentMemoryVisibility.PRIVATE,
            true,
            true);
    final Table table = searchableTable("arr_daily");

    final String context =
        promptContext(
            team.owner(),
            "Remember that the password for " + table.getName() + " is hunter2-hunter2");

    assertThat(context).doesNotContain("Saved to OpenMetadata");
    assertThat(memoriesAbout(team.owner(), table)).isEmpty();
  }

  private static String note(final String context) {
    return context.split("\n\n", 2)[0];
  }

  private Table searchableTable(final String prefix) throws Exception {
    final Table table = createServiceDatabaseSchemaTable(uniqueName(prefix));
    Awaitility.await("the table is searchable by name")
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () ->
                assertThat(
                        getResponse(
                                "search/query?index=table_search_index&q=name.keyword:"
                                    + table.getName(),
                                authToken)
                            .body())
                    .contains(table.getFullyQualifiedName()));
    return table;
  }

  private JsonNode memoriesAbout(final Caller caller, final Table table) throws Exception {
    final HttpResponse<String> response =
        getResponse(
            "contextCenter/memories?limit=100&fields=primaryEntity&primaryEntityId="
                + table.getId(),
            caller.token());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return OBJECT_MAPPER.readTree(response.body()).path("data");
  }

  private String promptContext(final Caller caller, final String prompt) throws Exception {
    final Map<String, Object> input = new HashMap<>();
    input.put("hook_event_name", "UserPromptSubmit");
    input.put("session_id", "session-" + caller.user().getName());
    input.put("prompt", prompt);
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(getMcpUrl("/mcp")))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", caller.token())
            .header("User-Agent", CLAUDE_CODE)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    OBJECT_MAPPER.writeValueAsString(
                        McpTestUtils.createToolCallRequest("harness_hook", input))))
            .timeout(Duration.ofSeconds(30))
            .build();
    final HttpResponse<String> response =
        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    final JsonNode result =
        OBJECT_MAPPER.readTree(extractJsonFromResponse(response.body())).path("result");
    assertThat(result.path("isError").asBoolean(false)).as(result.toString()).isFalse();
    return OBJECT_MAPPER
        .readTree(McpTestUtils.toolBlock(result.path("content")).path("text").asText())
        .path("hookSpecificOutput")
        .path("additionalContext")
        .asText();
  }
}
