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
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.User;

/** A hookless MCP client gets the SessionStart context in front of its first tool result. */
@Execution(ExecutionMode.CONCURRENT)
class FirstCallContextIT extends McpTestBase {
  private static final String DESKTOP = "Claude/1.0 (Claude Desktop)";
  private static final String CLAUDE_CODE = "claude-cli/2.1.0 (external, cli)";
  private static final String HEADING = "## OpenMetadata context for ";
  private static final String SEARCH = "search_metadata";

  @BeforeAll
  static void setup() throws Exception {
    initAuth();
  }

  @Test
  @ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK, mode = ResourceAccessMode.READ)
  void theFirstCallCarriesThePersonaContextAheadOfTheNormalResult() throws Exception {
    final Caller caller = callerWithHarness("first_call");

    final JsonNode first = call(caller.token(), SEARCH, DESKTOP);

    final JsonNode content = first.path("content");
    assertThat(content).hasSize(2);
    final String block = content.get(0).path("text").asText();
    assertThat(block).startsWith(HEADING + caller.user().getName() + " (");
    assertThat(block)
        .contains(" harness v")
        .contains(caller.prompt())
        .contains("standing instructions");
    assertThat(OBJECT_MAPPER.readTree(content.get(1).path("text").asText()).has("returnedCount"))
        .isTrue();
    assertThat(first.path("structuredContent").has("returnedCount")).isTrue();
  }

  @Test
  @ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK, mode = ResourceAccessMode.READ)
  void aSecondCallInTheSameConversationIsTheNormalResultAlone() throws Exception {
    final Caller caller = callerWithHarness("second_call");
    call(caller.token(), SEARCH, DESKTOP);

    final JsonNode second = call(caller.token(), SEARCH, DESKTOP);

    assertThat(second.path("content")).hasSize(1);
    assertThat(second.path("content").get(0).path("text").asText()).doesNotContain(HEADING);
  }

  @Test
  @ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK, mode = ResourceAccessMode.READ)
  void anotherUserAndAnotherClientEachGetTheirOwnFirstCall() throws Exception {
    final Caller alice = callerWithHarness("alice");
    final Caller bob = callerWithHarness("bob");
    call(alice.token(), SEARCH, DESKTOP);

    final JsonNode bobFirst = call(bob.token(), SEARCH, DESKTOP);
    final JsonNode aliceOtherClient = call(alice.token(), SEARCH, "Cursor/0.50");

    assertThat(bobFirst.path("content").get(0).path("text").asText())
        .contains(bob.prompt())
        .doesNotContain(alice.prompt());
    assertThat(aliceOtherClient.path("content").get(0).path("text").asText())
        .contains(alice.prompt());
  }

  @Test
  @ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK, mode = ResourceAccessMode.READ)
  void aClientThatSendsTheHookIsNotGivenTheContextTwice() throws Exception {
    final Caller caller = callerWithHarness("hooked");
    call(caller.token(), "harness_hook", CLAUDE_CODE, Map.of("hook_event_name", "SessionStart"));

    final JsonNode search = call(caller.token(), SEARCH, CLAUDE_CODE);

    assertThat(search.path("content")).hasSize(1);
  }

  @Test
  @ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK, mode = ResourceAccessMode.READ)
  void callingGetContextMarksTheConversationDelivered() throws Exception {
    final Caller caller = callerWithHarness("explicit");

    final JsonNode context = call(caller.token(), "get_context", DESKTOP);
    final JsonNode search = call(caller.token(), SEARCH, DESKTOP);

    assertThat(context.path("content")).hasSize(1);
    assertThat(search.path("content")).hasSize(1);
  }

  private record Caller(User user, String token, String prompt) {}

  private static Caller callerWithHarness(final String prefix) throws Exception {
    final User user = AgentHarnessApi.createUser(uniqueName(prefix));
    final Persona persona = createPersona(uniqueName(prefix), List.of(user.getId()));
    patch(
        "users/" + user.getId(),
        "[{\"op\":\"add\",\"path\":\"/defaultPersona\",\"value\":{\"id\":\""
            + persona.getId()
            + "\",\"type\":\"persona\"}}]");
    final AgentHarnessState created = createPersonaHarness(persona);
    final String prompt = "Answer for " + persona.getName() + ".";
    final AgentHarnessSpec spec = created.getDraft();
    spec.getContext().withPrompt(prompt);
    final UUID id = created.getPublished().getId();
    final AgentHarnessState drafted =
        admin("PUT", "/" + id + "/draft", spec, created.getDraftRevision());
    admin("POST", "/" + id + "/publish", null, drafted.getDraftRevision());
    return new Caller(user, AgentHarnessApi.tokenFor(user), prompt);
  }

  private JsonNode call(final String token, final String tool, final String userAgent)
      throws Exception {
    final Map<String, Object> arguments = new HashMap<>();
    if (SEARCH.equals(tool)) {
      arguments.put("query", "*");
      arguments.put("limit", 1);
    }
    return call(token, tool, userAgent, arguments);
  }

  private JsonNode call(
      final String token,
      final String tool,
      final String userAgent,
      final Map<String, Object> arguments)
      throws Exception {
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(getMcpUrl("/mcp")))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", token)
            .header("User-Agent", userAgent)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    OBJECT_MAPPER.writeValueAsString(
                        McpTestUtils.createToolCallRequest(tool, new HashMap<>(arguments)))))
            .timeout(Duration.ofSeconds(30))
            .build();
    final HttpResponse<String> response =
        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    final JsonNode result =
        OBJECT_MAPPER.readTree(extractJsonFromResponse(response.body())).path("result");
    assertThat(result.path("isError").asBoolean(false)).as(result.toString()).isFalse();
    return result;
  }
}
