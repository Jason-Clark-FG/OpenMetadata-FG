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
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersona;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersonaHarness;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.read;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.send;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.uniqueName;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openmetadata.it.tests.mcp.McpTestBase;
import org.openmetadata.it.util.SdkClients;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.api.teams.CreateTeam;
import org.openmetadata.schema.api.teams.CreateTeam.TeamType;
import org.openmetadata.schema.api.teams.CreateUser;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.Team;
import org.openmetadata.schema.entity.teams.User;

/**
 * A group team's default persona reaches its members as their default persona, for the user API
 * and for the harness compile alike, without being stored on the user.
 */
@Execution(ExecutionMode.CONCURRENT)
class TeamDefaultPersonaHarnessIT extends McpTestBase {

  @BeforeAll
  static void setup() throws Exception {
    initAuth();
  }

  @Test
  void aMemberWithoutADefaultGetsTheirTeamsDefaultPersonaAndItsHarness() throws Exception {
    final PersonaWithHarness finance = personaWithHarness("finance");
    final User bob = userIn("bob", groupTeam("finance_team", finance.persona()));

    assertThat(defaultPersonaOf(bob)).isEqualTo(finance.persona().getId());
    final CompiledAgentContext compiled = compile(bob);
    assertThat(compiled.getHarness().getPersona().getId()).isEqualTo(finance.harnessId());
    assertThat(compiled.getText()).contains(finance.prompt());
  }

  @Test
  void theUsersOwnDefaultBeatsTheirTeams() throws Exception {
    final PersonaWithHarness team = personaWithHarness("team");
    final PersonaWithHarness own = personaWithHarness("own");
    final User user = userIn("own_default", groupTeam("own_team", team.persona()));
    setOwnDefault(user, own.persona());

    assertThat(defaultPersonaOf(user)).isEqualTo(own.persona().getId());
    assertThat(compile(user).getHarness().getPersona().getId()).isEqualTo(own.harnessId());
  }

  @Test
  void teamsThatDisagreeGiveNoInheritedDefault() throws Exception {
    final PersonaWithHarness first = personaWithHarness("first");
    final PersonaWithHarness second = personaWithHarness("second");
    final User user =
        userIn(
            "two_teams",
            groupTeam("first_team", first.persona()),
            groupTeam("second_team", second.persona()));

    assertThat(defaultPersonaOf(user)).isNotIn(first.persona().getId(), second.persona().getId());
    final CompiledAgentContext compiled = compile(user);
    assertThat(compiled.getText()).doesNotContain(first.prompt()).doesNotContain(second.prompt());
  }

  @Test
  void leavingTheTeamDropsTheInheritedDefault() throws Exception {
    final PersonaWithHarness finance = personaWithHarness("leaving");
    final User user = userIn("leaver", groupTeam("leaver_team", finance.persona()));
    assertThat(defaultPersonaOf(user)).isEqualTo(finance.persona().getId());

    patch("users/" + user.getId(), "[{\"op\":\"remove\",\"path\":\"/teams/0\"}]");

    assertThat(defaultPersonaOf(user)).isNotEqualTo(finance.persona().getId());
    assertThat(compile(user).getText()).doesNotContain(finance.prompt());
  }

  @Test
  void rewritingAUserWithTheInheritedDefaultDoesNotStoreIt() throws Exception {
    final PersonaWithHarness finance = personaWithHarness("rewrite");
    final User user = userIn("rewriter", groupTeam("rewrite_team", finance.persona()));

    final User loaded =
        SdkClients.adminClient()
            .users()
            .get(user.getId().toString(), "teams,personas,defaultPersona");
    assertThat(loaded.getDefaultPersona().getId()).isEqualTo(finance.persona().getId());
    SdkClients.adminClient().users().update(user.getId().toString(), loaded);
    patch("users/" + user.getId(), "[{\"op\":\"remove\",\"path\":\"/teams/0\"}]");

    assertThat(defaultPersonaOf(user)).isNotEqualTo(finance.persona().getId());
  }

  private static UUID defaultPersonaOf(final User user) {
    final User fetched =
        SdkClients.adminClient().users().get(user.getId().toString(), "defaultPersona");
    return fetched.getDefaultPersona() == null ? null : fetched.getDefaultPersona().getId();
  }

  private static void setOwnDefault(final User user, final Persona persona) throws Exception {
    patch(
        "users/" + user.getId(),
        "[{\"op\":\"add\",\"path\":\"/defaultPersona\",\"value\":{\"id\":\""
            + persona.getId()
            + "\",\"type\":\"persona\"}}]");
  }

  private static Team groupTeam(final String prefix, final Persona defaultPersona) {
    return SdkClients.adminClient()
        .teams()
        .create(
            new CreateTeam()
                .withName(uniqueName(prefix))
                .withTeamType(TeamType.GROUP)
                .withDefaultPersona(defaultPersona.getId())
                .withDescription("Default persona harness test"));
  }

  private static User userIn(final String prefix, final Team... teams) {
    final String name = uniqueName(prefix);
    return SdkClients.adminClient()
        .users()
        .create(
            new CreateUser()
                .withName(name)
                .withEmail(name + "@example.com")
                .withTeams(Arrays.stream(teams).map(Team::getId).toList()));
  }

  private static CompiledAgentContext compile(final User user) {
    return read(
        send(
            "POST",
            HARNESS + "/compile",
            new CompileAgentContext().withEvent(CompileEvent.SESSION_START),
            AgentHarnessApi.tokenFor(user)),
        200,
        CompiledAgentContext.class);
  }

  private record PersonaWithHarness(Persona persona, UUID harnessId, String prompt) {}

  private static PersonaWithHarness personaWithHarness(final String prefix) {
    final Persona persona = createPersona(uniqueName(prefix), List.of());
    final AgentHarnessState created = createPersonaHarness(persona);
    final String prompt = "Answer for " + persona.getName() + ".";
    final AgentHarnessSpec spec = created.getDraft();
    spec.getContext().withPrompt(prompt);
    final UUID id = created.getPublished().getId();
    final AgentHarnessState drafted =
        admin("PUT", "/" + id + "/draft", spec, created.getDraftRevision());
    admin("POST", "/" + id + "/publish", null, drafted.getDraftRevision());
    return new PersonaWithHarness(persona, id, prompt);
  }
}
