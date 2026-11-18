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
import static org.openmetadata.it.tests.harness.AgentHarnessApi.ADMIN_TOKEN;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.HARNESS;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.admin;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersona;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersonaHarness;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.encoded;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.read;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.send;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.uniqueName;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openmetadata.it.tests.mcp.McpTestBase;
import org.openmetadata.it.tests.mcp.McpTestUtils;
import org.openmetadata.schema.api.context.AgentHarnessManagedSettings;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.api.context.PreviewAgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessToolAccess;
import org.openmetadata.schema.entity.context.ToolAccessDefault;
import org.openmetadata.schema.entity.context.ToolAccessLevel;
import org.openmetadata.schema.entity.context.ToolAccessServer;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.harness.HarnessSpecs;

@Execution(ExecutionMode.CONCURRENT)
class AgentHarnessCompileIT extends McpTestBase {
  private static final String PROMPT_HEADING = "## Your users";

  @BeforeAll
  static void setup() throws Exception {
    initAuth();
  }

  @Test
  void getContextReturnsTheSessionStartCompileOfTheCallersPersona() throws Exception {
    final Caller caller = callerWithHarness("compile_session", spec -> spec);

    final CompiledAgentContext rest = compile(caller.token(), CompileEvent.SESSION_START);
    final JsonNode tool = toolResult(caller.token(), "get_context", Map.of());

    assertThat(rest.getText()).contains(PROMPT_HEADING).contains(caller.prompt());
    assertThat(rest.getHarness().getPersona().getId()).isEqualTo(caller.harnessId());
    assertThat(rest.getHarness().getDefault().getName()).isEqualTo("CompanyDefault");
    assertThat(tool.path("text").asText()).isEqualTo(rest.getText());
    assertThat(tool.path("tokens").asInt()).isEqualTo(rest.getTokens());
    assertThat(tool.path("skills").isArray()).isTrue();
  }

  @Test
  void harnessHookAnswersSessionStartWithHostHookOutput() throws Exception {
    final Caller caller = callerWithHarness("hook_session", spec -> spec);

    final JsonNode output =
        hook(caller.token(), Map.of("hook_event_name", "SessionStart", "session_id", "s1"));

    assertThat(output.path("hookSpecificOutput").path("hookEventName").asText())
        .isEqualTo("SessionStart");
    assertThat(output.path("hookSpecificOutput").path("additionalContext").asText())
        .isEqualTo(compile(caller.token(), CompileEvent.SESSION_START).getText());
    assertThat(
            hook(caller.token(), Map.of("hook_event_name", "Stop", "session_id", "s1")).isEmpty())
        .isTrue();
  }

  @Test
  void aPersonaOrHarnessNamedByTheClientIsIgnored() throws Exception {
    final Caller caller = callerWithHarness("hook_own", spec -> spec);
    final Caller other = callerWithHarness("hook_other", spec -> spec);

    final JsonNode output =
        hook(
            caller.token(),
            Map.of(
                "hook_event_name", "SessionStart",
                "persona", other.persona().getFullyQualifiedName(),
                "personaName", other.persona().getFullyQualifiedName(),
                "harness", other.harnessId().toString()));

    final String context = output.path("hookSpecificOutput").path("additionalContext").asText();
    assertThat(context).contains(caller.prompt()).doesNotContain(other.prompt());
    final JsonNode viaHeader =
        toolResult(
            caller.token(), "get_context", Map.of(), other.persona().getFullyQualifiedName());
    assertThat(viaHeader.path("harness").path("persona").path("id").asText())
        .isEqualTo(caller.harnessId().toString());
  }

  @Test
  void aBlockedMcpServerIsDeniedAtPreToolUse() throws Exception {
    final String blocked = uniqueName("blocked").toLowerCase();
    final Caller caller =
        callerWithHarness(
            "hook_block",
            spec ->
                spec.withToolAccess(
                    new AgentHarnessToolAccess()
                        .withDefault(ToolAccessDefault.ALLOW)
                        .withServers(
                            List.of(
                                new ToolAccessServer()
                                    .withMatch(blocked)
                                    .withLevel(ToolAccessLevel.BLOCK)
                                    .withTools(List.of())))));

    final JsonNode denied =
        hook(
            caller.token(),
            Map.of(
                "hook_event_name",
                "PreToolUse",
                "tool_name",
                "mcp__" + blocked + "__run_query",
                "tool_input",
                Map.of("sql", "select 1")));
    final JsonNode allowed =
        hook(
            caller.token(),
            Map.of(
                "hook_event_name", "PreToolUse",
                "tool_name", "mcp__elsewhere__run_query",
                "tool_input", Map.of("sql", "select 1")));

    assertThat(denied.path("hookSpecificOutput").path("permissionDecision").asText())
        .isEqualTo("deny");
    assertThat(denied.path("hookSpecificOutput").path("permissionDecisionReason").asText())
        .isEqualTo("MCP tool run_query on server " + blocked + " is blocked by the agent harness.");
    assertThat(allowed.path("hookSpecificOutput").has("permissionDecision")).isFalse();
  }

  @Test
  void aDeprecatedTableNamedInTheToolInputIsDeniedFromItsLiveState() throws Exception {
    final Table table = createServiceDatabaseSchemaTable("harness_deprecated");
    final Caller caller = callerWithHarness("hook_deprecated", spec -> spec);
    final Map<String, Object> call =
        Map.of(
            "hook_event_name", "PreToolUse",
            "tool_name", "mcp__warehouse__run_query",
            "tool_input",
                JsonUtils.pojoToJson(
                    Map.of("sql", "select * from " + table.getFullyQualifiedName())));

    assertThat(hook(caller.token(), call).path("hookSpecificOutput").has("permissionDecision"))
        .isFalse();
    patch(
        "tables/" + table.getId(),
        "[{\"op\":\"add\",\"path\":\"/entityStatus\",\"value\":\"Deprecated\"}]");
    final JsonNode denied = hook(caller.token(), call);

    assertThat(denied.path("hookSpecificOutput").path("permissionDecision").asText())
        .isEqualTo("deny");
    assertThat(denied.path("hookSpecificOutput").path("permissionDecisionReason").asText())
        .isEqualTo("table " + table.getFullyQualifiedName() + " is deprecated.");
  }

  @Test
  @ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK)
  void aPersonaCannotLoosenTheDefaultsToolAccess() {
    final String warned = uniqueName("warned").toLowerCase();
    addDefaultServer(warned, ToolAccessLevel.WARN);
    final Persona persona = createPersona(uniqueName("loosen"), List.of());
    final AgentHarnessState created = createPersonaHarness(persona);
    final AgentHarnessSpec loosened =
        created
            .getDraft()
            .withToolAccess(
                new AgentHarnessToolAccess()
                    .withDefault(ToolAccessDefault.ALLOW)
                    .withServers(
                        List.of(
                            new ToolAccessServer()
                                .withMatch(warned)
                                .withLevel(ToolAccessLevel.ALLOW)
                                .withTools(List.of()))));

    final HttpResponse<String> response =
        send(
            "PUT",
            HARNESS + "/" + created.getPublished().getId() + "/draft",
            loosened,
            ADMIN_TOKEN,
            created.getDraftRevision());

    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    assertThat(response.body()).contains("toolAccess server " + warned + " cannot be Allow");
  }

  @Test
  void previewCompilesABodySpecWithoutPublishingItAndIsAdminOnly() throws Exception {
    final Caller caller = callerWithHarness("preview", spec -> spec);
    final String previewPrompt = "Preview prompt " + uniqueName("p");
    final AgentHarnessState state =
        admin("GET", "/persona/" + encoded(caller.persona().getFullyQualifiedName()), null, null);
    final AgentHarnessSpec spec = HarnessSpecs.specOf(state.getPublished());
    spec.getContext().withPrompt(previewPrompt);
    final String path = HARNESS + "/" + caller.harnessId() + "/preview?event=SessionStart";
    final PreviewAgentHarness body = new PreviewAgentHarness().withSpec(spec);

    final CompiledAgentContext preview =
        read(send("POST", path, body, ADMIN_TOKEN), 200, CompiledAgentContext.class);

    assertThat(preview.getText()).contains(previewPrompt).doesNotContain(caller.prompt());
    assertThat(compile(caller.token(), CompileEvent.SESSION_START).getText())
        .contains(caller.prompt())
        .doesNotContain(previewPrompt);
    assertThat(send("POST", path, body, caller.token()).statusCode()).isEqualTo(403);
  }

  @Test
  void rulePreviewMovesToTheHarnessAndIsAdminOnly() throws Exception {
    final Table table = createServiceDatabaseSchemaTable("harness_rule_preview");
    final Caller caller = callerWithHarness("rule_preview", spec -> spec);
    final ContextRule rule =
        new ContextRule()
            .withName("Preview tables")
            .withEntityType(Entity.TABLE)
            .withQueryFilter(
                "{\"query\":{\"term\":{\"fullyQualifiedName\":\""
                    + table.getFullyQualifiedName()
                    + "\"}}}")
            .withSections(Set.of())
            .withMaxAssets(1)
            .withEnabled(true)
            .withFilteredInSearch(false);
    final String path = HARNESS + "/" + caller.harnessId() + "/rules/preview";

    final JsonNode preview = read(send("POST", path, rule, ADMIN_TOKEN), 200, JsonNode.class);

    assertThat(preview.path("matchedCount").asInt()).isEqualTo(1);
    assertThat(send("POST", path, rule, caller.token()).statusCode()).isEqualTo(403);
  }

  @Test
  void managedSettingsAreAdminOnlyAndDenyNothingByDefault() throws Exception {
    final Caller caller = callerWithHarness("managed", spec -> spec);

    final AgentHarnessManagedSettings settings =
        read(
            send("GET", HARNESS + "/managedSettings", null, ADMIN_TOKEN),
            200,
            AgentHarnessManagedSettings.class);

    assertThat(settings.getServerName()).isEqualTo("openmetadata");
    assertThat(settings.getServerUrl()).endsWith("/mcp");
    assertThat(JsonUtils.valueToTree(settings.getClaudeCode()).path("deniedMcpServers").isArray())
        .isTrue();
    assertThat(send("GET", HARNESS + "/managedSettings", null, caller.token()).statusCode())
        .isEqualTo(403);
  }

  @Test
  @ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK, mode = ResourceAccessMode.READ)
  void aCallerWithoutAPersonaReceivesTheDefaultAlone() throws Exception {
    final User user = AgentHarnessApi.createUser(uniqueName("no_persona"));

    final CompiledAgentContext compiled =
        compile(AgentHarnessApi.tokenFor(user), CompileEvent.SESSION_START);

    assertThat(compiled.getHarness().getDefault().getName()).isEqualTo("CompanyDefault");
    assertThat(compiled.getHarness().getPersona()).isNull();
  }

  /** A user whose default persona has a published harness with its own prompt. */
  private record Caller(Persona persona, UUID harnessId, String token, String prompt) {}

  private interface SpecChange {
    AgentHarnessSpec apply(AgentHarnessSpec spec);
  }

  private static Caller callerWithHarness(final String prefix, final SpecChange change)
      throws Exception {
    final User user = AgentHarnessApi.createUser(uniqueName(prefix));
    final Persona persona = createPersona(uniqueName(prefix), List.of(user.getId()));
    patch(
        "users/" + user.getId(),
        "[{\"op\":\"add\",\"path\":\"/defaultPersona\",\"value\":{\"id\":\""
            + persona.getId()
            + "\",\"type\":\"persona\"}}]");
    final AgentHarnessState created = createPersonaHarness(persona);
    final String prompt = "Answer for " + persona.getName() + ".";
    final AgentHarnessSpec spec = change.apply(created.getDraft());
    spec.getContext().withPrompt(prompt);
    final UUID id = created.getPublished().getId();
    final AgentHarnessState drafted =
        admin("PUT", "/" + id + "/draft", spec, created.getDraftRevision());
    admin("POST", "/" + id + "/publish", null, drafted.getDraftRevision());
    return new Caller(persona, id, AgentHarnessApi.tokenFor(user), prompt);
  }

  private static void addDefaultServer(final String server, final ToolAccessLevel level) {
    final AgentHarnessState state = admin("GET", "/default", null, null);
    final AgentHarnessSpec spec =
        state.getDraft() == null ? HarnessSpecs.specOf(state.getPublished()) : state.getDraft();
    final AgentHarnessToolAccess access =
        spec.getToolAccess() == null
            ? new AgentHarnessToolAccess().withDefault(ToolAccessDefault.ALLOW)
            : spec.getToolAccess();
    final List<ToolAccessServer> servers = new ArrayList<>(access.getServers());
    servers.add(new ToolAccessServer().withMatch(server).withLevel(level).withTools(List.of()));
    spec.withToolAccess(access.withServers(servers));
    final String id = "/" + state.getPublished().getId();
    final AgentHarnessState drafted = admin("PUT", id + "/draft", spec, state.getDraftRevision());
    admin("POST", id + "/publish", null, drafted.getDraftRevision());
  }

  private static CompiledAgentContext compile(final String token, final CompileEvent event) {
    return read(
        send("POST", HARNESS + "/compile", new CompileAgentContext().withEvent(event), token),
        200,
        CompiledAgentContext.class);
  }

  private JsonNode hook(final String token, final Map<String, Object> input) throws Exception {
    return toolResult(token, "harness_hook", input);
  }

  private JsonNode toolResult(
      final String token, final String tool, final Map<String, Object> arguments) throws Exception {
    return toolResult(token, tool, arguments, null);
  }

  private JsonNode toolResult(
      final String token,
      final String tool,
      final Map<String, Object> arguments,
      final String activePersona)
      throws Exception {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create(getMcpUrl("/mcp")))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    OBJECT_MAPPER.writeValueAsString(
                        McpTestUtils.createToolCallRequest(tool, new HashMap<>(arguments)))))
            .timeout(Duration.ofSeconds(30));
    if (activePersona != null) {
      request.header("X-OpenMetadata-Persona", activePersona);
    }
    final HttpResponse<String> response =
        HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    final JsonNode result =
        OBJECT_MAPPER.readTree(extractJsonFromResponse(response.body())).path("result");
    assertThat(result.path("isError").asBoolean(false)).as(result.toString()).isFalse();
    return OBJECT_MAPPER.readTree(result.path("content").get(0).path("text").asText());
  }
}
