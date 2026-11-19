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
import static org.openmetadata.it.tests.harness.AgentHarnessApi.HARNESS;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.admin;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersonaHarness;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.encoded;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.read;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.send;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openmetadata.it.auth.JwtAuthProvider;
import org.openmetadata.it.bootstrap.TestSuiteBootstrap;
import org.openmetadata.it.tests.mcp.McpTestBase;
import org.openmetadata.it.tests.mcp.McpTestUtils;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.api.policies.CreatePolicy;
import org.openmetadata.schema.api.teams.CreatePersona;
import org.openmetadata.schema.api.teams.CreateRole;
import org.openmetadata.schema.api.teams.CreateTeam;
import org.openmetadata.schema.api.teams.CreateUser;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessEvent;
import org.openmetadata.schema.entity.context.HookEvent;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.entity.policies.Policy;
import org.openmetadata.schema.entity.policies.accessControl.Rule;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.Role;
import org.openmetadata.schema.entity.teams.Team;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.harness.HarnessSpecs;
import org.openmetadata.service.jdbi3.locator.ConnectionType;

/**
 * What a persona's members receive from its published harness: the persona context route, the
 * assets a member may see, the MCP search scope, and the event budgets.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentHarnessPersonaContextIT extends McpTestBase {
  private static final String ACTIVE_PERSONA_HEADER = "X-OpenMetadata-Persona";
  private static Persona persona;
  private static Table table;
  private static String directMemberToken;
  private static String inheritedMemberToken;
  private static String nonMemberToken;
  private static String botToken;

  @BeforeAll
  static void setup() throws Exception {
    initAuth();
    final String suffix = shortId();
    table = createServiceDatabaseSchemaTable("persona_context_" + suffix);
    final User directMember = createUser("persona_direct_" + suffix);
    final User inheritedMember = createUser("persona_inherited_" + suffix);
    final User nonMember = createUser("persona_non_member_" + suffix);
    final User bot = get("users/name/profiler-bot", User.class);
    persona =
        post(
            "personas",
            new CreatePersona()
                .withName("persona_context_" + suffix)
                .withUsers(List.of(directMember.getId())),
            Persona.class);
    post(
        "teams",
        new CreateTeam()
            .withName("persona_context_team_" + suffix)
            .withTeamType(CreateTeam.TeamType.GROUP)
            .withUsers(List.of(inheritedMember.getId()))
            .withDefaultPersona(persona.getId()),
        JsonNode.class);
    directMemberToken = tokenFor(directMember);
    inheritedMemberToken = tokenFor(inheritedMember);
    nonMemberToken = tokenFor(nonMember);
    botToken = tokenFor(bot);
    publishRules(persona, List.of(preloadedRule("Baseline tables", table)));
  }

  @Test
  void thePersonaContextRouteServesMembersBotsAndAdminsOnly() throws Exception {
    final String path = "personas/name/" + persona.getFullyQualifiedName() + "/context";
    final HttpResponse<String> direct = getResponse(path + "?format=json", directMemberToken);
    assertThat(direct.statusCode()).isEqualTo(200);
    assertThat(
            OBJECT_MAPPER
                .readTree(direct.body())
                .path("harness")
                .path("persona")
                .path("name")
                .asText())
        .isEqualTo(persona.getId().toString());
    assertThat(getResponse(path, inheritedMemberToken).statusCode()).isEqualTo(200);
    assertThat(getResponse(path, nonMemberToken).statusCode()).isEqualTo(403);
    assertThat(getResponse(path + "?refresh=true", directMemberToken).statusCode()).isEqualTo(403);
    assertThat(getResponse(path + "?refresh=true", botToken).statusCode()).isEqualTo(200);
    assertThat(getResponse(path + "?refresh=true", authToken).statusCode()).isEqualTo(200);

    final JsonNode nonMemberContext =
        toolResult(
            executeMcp(
                McpTestUtils.createToolCallRequest("get_context", Map.of()),
                nonMemberToken,
                persona.getFullyQualifiedName()));
    assertThat(nonMemberContext.path("harness").path("default").path("name").asText())
        .isEqualTo(HarnessSpecs.DEFAULT_NAME);
    assertThat(nonMemberContext.path("harness").has("persona")).isFalse();
  }

  @Test
  void aMembersContextLeavesOutAssetsTheyCannotView() throws Exception {
    final String suffix = shortId();
    final Policy policy =
        post(
            "policies",
            new CreatePolicy()
                .withName("persona_deny_" + suffix)
                .withRules(
                    List.of(
                        new Rule()
                            .withName("DenyTables")
                            .withResources(List.of(Entity.TABLE))
                            .withOperations(List.of(MetadataOperation.VIEW_ALL))
                            .withEffect(Rule.Effect.DENY))),
            Policy.class);
    final Role role =
        post(
            "roles",
            new CreateRole()
                .withName("persona_deny_" + suffix)
                .withPolicies(List.of(policy.getFullyQualifiedName())),
            Role.class);
    final User denied =
        post(
            "users",
            new CreateUser()
                .withName("persona_denied_" + suffix)
                .withEmail("persona_denied_" + suffix + "@example.com")
                .withRoles(List.of(role.getId())),
            User.class);
    final Persona owned = createPersona("persona_denied_" + suffix, List.of(denied.getId()));
    final String path = "personas/" + owned.getId() + "/context";
    try {
      publishRules(owned, List.of(preloadedRule("Preloaded tables", table)));
      assertThat(getResponse(path + "?refresh=true", authToken).body())
          .contains(table.getFullyQualifiedName());
      final String deniedToken = tokenFor(denied);
      assertThat(getResponse("tables/" + table.getId(), deniedToken).statusCode()).isEqualTo(403);
      for (final String format : List.of("json", "markdown")) {
        final HttpResponse<String> response = getResponse(path + "?format=" + format, deniedToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain(table.getFullyQualifiedName());
      }
      final JsonNode mcp =
          executeMcp(
              McpTestUtils.createToolCallRequest("get_context", Map.of()),
              deniedToken,
              owned.getFullyQualifiedName());
      assertThat(mcp.path("result").path("isError").asBoolean(false)).isFalse();
      assertThat(mcp.toString()).doesNotContain(table.getFullyQualifiedName());
      assertThat(getResponse(path, authToken).body()).contains(table.getFullyQualifiedName());
    } finally {
      deleteResponse("personas/" + owned.getId() + "?hardDelete=true", authToken);
      deleteResponse("users/" + denied.getId() + "?hardDelete=true", authToken);
      deleteResponse("roles/" + role.getId() + "?hardDelete=true", authToken);
      deleteResponse("policies/" + policy.getId() + "?hardDelete=true", authToken);
    }
  }

  /**
   * Two scoped rules select the union of their assets when the filter really runs in the search
   * engine, a preloaded rule leaves the scope alone, and a caller can search beyond the scope.
   */
  @Test
  void scopedRulesNarrowMcpSearchToTheirUnionAndCanBeBypassed() throws Exception {
    final String suffix = shortId();
    final Table first = createServiceDatabaseSchemaTable("persona_union_a_" + suffix);
    final Table second = createServiceDatabaseSchemaTable("persona_union_b_" + suffix);
    final Table preloaded = createServiceDatabaseSchemaTable("persona_preload_" + suffix);
    final Table excluded = createServiceDatabaseSchemaTable("persona_excluded_" + suffix);
    final List<Table> candidates = List.of(first, second, preloaded, excluded);
    final User member = createUser("persona_union_member_" + suffix);
    final Persona owned = createPersona("persona_union_" + suffix, List.of(member.getId()));
    final String token = tokenFor(member);
    try {
      publishRules(owned, List.of(scopedRule("Union first", first)));
      awaitScope(owned, token, candidates, Set.of(first));

      publishRules(
          owned,
          List.of(
              scopedRule("Union first", first),
              scopedRule("Union second", second),
              preloadedRule("Preloaded", preloaded)));
      awaitScope(owned, token, candidates, Set.of(first, second));

      final JsonNode unscoped = searchWithPersona(owned, token, candidates, true);
      assertThat(resultFqns(unscoped))
          .containsExactlyInAnyOrderElementsOf(fqns(Set.copyOf(candidates)));
      assertThat(unscoped.has("personaScopeApplied")).isFalse();
    } finally {
      deleteResponse("personas/" + owned.getId() + "?hardDelete=true", authToken);
      deleteResponse("users/" + member.getId() + "?hardDelete=true", authToken);
    }
  }

  /** A rule stored before its filter was validated, as a migrated rule may be, is skipped. */
  @Test
  void anInvalidStoredScopeIsSkippedWhileTheRestOfTheContextStaysAvailable() throws Exception {
    final String suffix = shortId();
    final Table excluded = createServiceDatabaseSchemaTable("outside_scope_" + suffix);
    final User member = createUser("stored_scope_member_" + suffix);
    final Persona owned = createPersona("stored_scope_" + suffix, List.of(member.getId()));
    final String token = tokenFor(member);
    final List<Table> candidates = List.of(table, excluded);
    try {
      publishRules(
          owned,
          List.of(scopedRule("Healthy scope", table), preloadedRule("Preloaded tables", table)));
      for (final String filter : List.of("{", "{\"regexp\":{\"name\":\"[shop\"}}")) {
        storeWithoutValidation(owned, scopedRule("Broken scope", table).withQueryFilter(filter));
        awaitScope(owned, token, candidates, Set.of(table));
        final HttpResponse<String> markdown =
            getResponse("personas/" + owned.getId() + "/context?refresh=true", authToken);
        assertThat(markdown.statusCode()).isEqualTo(200);
        assertThat(markdown.body()).contains(table.getFullyQualifiedName());
      }
    } finally {
      deleteResponse("personas/" + owned.getId() + "?hardDelete=true", authToken);
      deleteResponse("users/" + member.getId() + "?hardDelete=true", authToken);
    }
  }

  @Test
  void aPersonaEventBudgetCapsWhatSessionStartDelivers() throws Exception {
    final String suffix = shortId();
    final User member = createUser("budget_member_" + suffix);
    final Persona owned = createPersona("budget_" + suffix, List.of(member.getId()));
    final String prompt = "Answer for the budget persona. ".repeat(20).strip();
    try {
      patch(
          "users/" + member.getId(),
          "[{\"op\":\"add\",\"path\":\"/defaultPersona\",\"value\":{\"id\":\""
              + owned.getId()
              + "\",\"type\":\"persona\"}}]");
      publish(
          owned,
          spec -> {
            spec.getContext().withPrompt(prompt);
            return spec.withEvents(
                List.of(
                    new AgentHarnessEvent()
                        .withId(HookEvent.SESSION_START)
                        .withEnabled(true)
                        .withTokenBudget(20)));
          });
      final CompiledAgentContext compiled =
          read(
              send(
                  "POST",
                  HARNESS + "/compile",
                  new CompileAgentContext().withEvent(CompileEvent.SESSION_START),
                  tokenFor(member)),
              200,
              CompiledAgentContext.class);
      assertThat(compiled.getHarness().getPersona()).isNotNull();
      assertThat(compiled.getTokens()).isLessThanOrEqualTo(20);
      assertThat(compiled.getText()).doesNotContain(prompt);
    } finally {
      deleteResponse("personas/" + owned.getId() + "?hardDelete=true", authToken);
      deleteResponse("users/" + member.getId() + "?hardDelete=true", authToken);
    }
  }

  @Test
  void userCountIncludesTeamMembersOnceWithoutChangingDirectAssignments() throws Exception {
    final String suffix = shortId();
    final User direct = createUser("persona_count_direct_" + suffix);
    final User inherited = createUser("persona_count_inherited_" + suffix);
    final Persona owned = createPersona("count_" + suffix, List.of(direct.getId()));
    final Team first = team("count_first_" + suffix, owned, List.of(direct, inherited));
    final Team second = team("count_second_" + suffix, owned, List.of(inherited));
    try {
      final Persona counted =
          get("personas/" + owned.getId() + "?fields=users,userCount", Persona.class);
      assertThat(counted.getUserCount()).isEqualTo(2);
      assertThat(counted.getUsers())
          .extracting(EntityReference::getId)
          .containsExactly(direct.getId());
      final HttpResponse<String> list =
          getResponse("personas?fields=userCount&limit=1000", authToken);
      assertThat(OBJECT_MAPPER.readTree(list.body()).path("data"))
          .anySatisfy(
              row -> {
                assertThat(row.path("id").asText()).isEqualTo(owned.getId().toString());
                assertThat(row.path("userCount").asInt()).isEqualTo(2);
              });
      deleteResponse("users/" + inherited.getId(), authToken);
      assertThat(
              get("personas/" + owned.getId() + "?fields=userCount", Persona.class).getUserCount())
          .isEqualTo(1);
    } finally {
      deleteResponse("teams/" + first.getId() + "?hardDelete=true&recursive=true", authToken);
      deleteResponse("teams/" + second.getId() + "?hardDelete=true&recursive=true", authToken);
      deleteResponse("personas/" + owned.getId() + "?hardDelete=true", authToken);
      deleteResponse("users/" + direct.getId() + "?hardDelete=true", authToken);
      deleteResponse("users/" + inherited.getId() + "?hardDelete=true", authToken);
    }
  }

  private interface SpecChange {
    AgentHarnessSpec apply(AgentHarnessSpec spec);
  }

  private static void publishRules(final Persona owner, final List<ContextRule> rules) {
    publish(owner, spec -> spec.withContext(spec.getContext().withRules(new ArrayList<>(rules))));
  }

  /** Publishes the persona's harness, created on first use, with {@code change} applied. */
  private static void publish(final Persona owner, final SpecChange change) {
    final AgentHarnessState state = stateOf(owner);
    final AgentHarnessSpec current =
        state.getDraft() == null ? HarnessSpecs.specOf(state.getPublished()) : state.getDraft();
    final String id = "/" + state.getPublished().getId();
    final AgentHarnessState drafted =
        admin("PUT", id + "/draft", change.apply(current), state.getDraftRevision());
    admin("POST", id + "/publish", null, drafted.getDraftRevision());
  }

  private static AgentHarnessState stateOf(final Persona owner) {
    final HttpResponse<String> response =
        send(
            "GET",
            HARNESS + "/persona/" + encoded(owner.getFullyQualifiedName()),
            null,
            AgentHarnessApi.ADMIN_TOKEN);
    return response.statusCode() == 404
        ? createPersonaHarness(owner)
        : read(response, 200, AgentHarnessState.class);
  }

  /** Adds {@code rule} to the published harness the way a migrated rule arrives: unchecked. */
  private static void storeWithoutValidation(final Persona owner, final ContextRule rule) {
    final AgentHarness harness = stateOf(owner).getPublished();
    final List<ContextRule> rules =
        new ArrayList<>(
            harness.getContext().getRules().stream()
                .filter(existing -> !rule.getName().equals(existing.getName()))
                .toList());
    rules.add(rule.withId(UUID.randomUUID()));
    harness.getContext().setRules(rules);
    harness.setPersona(new EntityReference().withId(owner.getId()).withType(Entity.PERSONA));
    final String sql =
        TestSuiteBootstrap.getConnectionType() == ConnectionType.MYSQL
            ? "UPDATE agent_harness_entity SET json = :json WHERE id = :id"
            : "UPDATE agent_harness_entity SET json = CAST(:json AS jsonb) WHERE id = :id";
    TestSuiteBootstrap.getJdbi()
        .useHandle(
            handle ->
                handle
                    .createUpdate(sql)
                    .bind("json", JsonUtils.pojoToJson(harness))
                    .bind("id", harness.getId().toString())
                    .execute());
  }

  private void awaitScope(
      final Persona owner,
      final String token,
      final List<Table> candidates,
      final Set<Table> inScope) {
    Awaitility.await("persona search scope selects exactly " + fqns(inScope))
        .atMost(Duration.ofSeconds(60))
        .pollDelay(Duration.ofSeconds(1))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              final JsonNode scoped = searchWithPersona(owner, token, candidates, false);
              assertThat(resultFqns(scoped)).containsExactlyInAnyOrderElementsOf(fqns(inScope));
              assertThat(scoped.path("personaScopeApplied").asBoolean()).isTrue();
            });
  }

  private JsonNode searchWithPersona(
      final Persona activePersona,
      final String token,
      final List<Table> candidates,
      final boolean ignoreScope)
      throws Exception {
    final String queryFilter =
        OBJECT_MAPPER.writeValueAsString(
            Map.of(
                "bool",
                Map.of(
                    "filter",
                    List.of(
                        Map.of("term", Map.of("entityType", Entity.TABLE)),
                        Map.of(
                            "terms",
                            Map.of(
                                "fullyQualifiedName",
                                candidates.stream()
                                    .map(Table::getFullyQualifiedName)
                                    .toList()))))));
    final Map<String, Object> arguments =
        Map.of(
            "query",
            "*",
            "entityType",
            Entity.TABLE,
            "queryFilter",
            queryFilter,
            "size",
            10,
            "ignorePersonaScope",
            ignoreScope);
    return toolResult(
        executeMcp(
            McpTestUtils.createToolCallRequest("search_metadata", arguments),
            token,
            activePersona.getFullyQualifiedName()));
  }

  private static JsonNode toolResult(final JsonNode response) throws Exception {
    final JsonNode result = response.path("result");
    assertThat(result.path("isError").asBoolean(false)).as(result.toString()).isFalse();
    return OBJECT_MAPPER.readTree(
        McpTestUtils.toolBlock(result.path("content")).path("text").asText());
  }

  private static Set<String> resultFqns(final JsonNode response) {
    final Set<String> found = new HashSet<>();
    response
        .path("results")
        .forEach(result -> found.add(result.path("fullyQualifiedName").asText()));
    return found;
  }

  private static Set<String> fqns(final Set<Table> tables) {
    final Set<String> names = new HashSet<>();
    tables.forEach(each -> names.add(each.getFullyQualifiedName()));
    return names;
  }

  private JsonNode executeMcp(
      final Map<String, Object> requestBody, final String token, final String activePersona)
      throws Exception {
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(getMcpUrl("/mcp")))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", token)
            .header(ACTIVE_PERSONA_HEADER, activePersona)
            .POST(
                HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(requestBody)))
            .timeout(Duration.ofSeconds(30))
            .build();
    final HttpResponse<String> response =
        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return OBJECT_MAPPER.readTree(extractJsonFromResponse(response.body()));
  }

  private static ContextRule scopedRule(final String name, final Table selected) {
    return tableRule(name, selected).withFilteredInSearch(true);
  }

  private static ContextRule preloadedRule(final String name, final Table selected) {
    return tableRule(name, selected).withFilteredInSearch(false).withAlwaysInContext(true);
  }

  private static ContextRule tableRule(final String name, final Table selected) {
    return new ContextRule()
        .withName(name)
        .withEntityType(Entity.TABLE)
        .withQueryFilter(
            "{\"query\":{\"term\":{\"fullyQualifiedName\":\""
                + selected.getFullyQualifiedName()
                + "\"}}}")
        .withSections(Set.of())
        .withMaxAssets(10)
        .withEnabled(true);
  }

  private static Persona createPersona(final String name, final List<UUID> users) throws Exception {
    return post("personas", new CreatePersona().withName(name).withUsers(users), Persona.class);
  }

  private static Team team(final String name, final Persona defaultPersona, final List<User> users)
      throws Exception {
    return post(
        "teams",
        new CreateTeam()
            .withName(name)
            .withTeamType(CreateTeam.TeamType.GROUP)
            .withDefaultPersona(defaultPersona.getId())
            .withUsers(users.stream().map(User::getId).toList()),
        Team.class);
  }

  private static User createUser(final String name) throws Exception {
    return post(
        "users", new CreateUser().withName(name).withEmail(name + "@example.com"), User.class);
  }

  private static String tokenFor(final User user) {
    return "Bearer "
        + JwtAuthProvider.tokenFor(user.getEmail(), user.getEmail(), new String[] {}, 3_600);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
