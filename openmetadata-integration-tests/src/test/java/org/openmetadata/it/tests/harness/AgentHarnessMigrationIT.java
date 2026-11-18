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
import static org.assertj.core.api.Assertions.tuple;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.ADMIN_TOKEN;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.HARNESS;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersona;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersonaHarness;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.encoded;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.read;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.send;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.uniqueName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.openmetadata.it.bootstrap.TestSuiteBootstrap;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.api.teams.CreateUser;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessKind;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.EntityHistory;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.type.personaContext.ContextSection;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.locator.ConnectionType;
import org.openmetadata.service.migration.utils.v210.PersonaAgentHarnessMigration;

/**
 * Runs the 2.1.0 persona AI context migration against the real database. Personas are seeded in
 * the shape the previous release stored: {@code contextDefinition} on the persona row and on its
 * versions.
 */
@Isolated("seeds persona rows the current Persona schema cannot read, until the migration runs")
class AgentHarnessMigrationIT {
  private static final String CONTEXT_FIELD = "contextDefinition";
  private static final String VERSION_EXTENSION = "persona.version.0.1";

  @Test
  void personaContextsBecomePublishedHarnessesAndLeavePersonaRowsAndVersions() {
    final Persona enabled = createPersona(uniqueName("migrated_on"), List.of());
    final Persona disabled = createPersona(uniqueName("migrated_off"), List.of());
    final Persona empty = createPersona(uniqueName("migrated_empty"), List.of());
    final Persona alreadyHarnessed = createPersona(uniqueName("migrated_kept"), List.of());
    final User member = createMember(uniqueName("migrated_member"), enabled);
    final AgentHarnessState existing = createPersonaHarness(alreadyHarnessed);
    final PersonaContextDefinition enabledContext = context(true, "Answer for finance.");
    seedPreviousReleaseShape(enabled, enabledContext);
    seedPreviousReleaseShape(disabled, context(false, "Answer for marketing."));
    seedPreviousReleaseShape(empty, new PersonaContextDefinition().withRules(List.of()));
    seedPreviousReleaseShape(alreadyHarnessed, context(true, "Answer for sales."));

    runMigration();
    final AgentHarness first = harnessOf(enabled);
    runMigration();

    final AgentHarness migrated = harnessOf(enabled);
    assertThat(migrated.getId()).as("a rerun creates nothing").isEqualTo(first.getId());
    assertThat(migrated.getKind()).isEqualTo(AgentHarnessKind.PERSONA);
    assertThat(migrated.getName()).isEqualTo(enabled.getId().toString());
    assertThat(migrated.getEnabled()).isTrue();
    assertThat(migrated.getPublishedAt()).isNotNull();
    assertThat(migrated.getContext().getPrompt()).isEqualTo(enabledContext.getPrompt());
    assertThat(migrated.getContext().getRules())
        .extracting(ContextRule::getId, ContextRule::getName, ContextRule::getQueryFilter)
        .containsExactly(
            tuple(
                enabledContext.getRules().getFirst().getId(),
                "Finance tables",
                enabledContext.getRules().getFirst().getQueryFilter()));
    assertThat(harnessOf(disabled).getEnabled()).as("a disabled context stays off").isFalse();
    assertThat(personaState(empty).statusCode())
        .as("an empty context needs no harness")
        .isEqualTo(404);
    final AgentHarness kept = harnessOf(alreadyHarnessed);
    assertThat(kept.getId()).isEqualTo(existing.getPublished().getId());
    assertThat(kept.getPublishedAt()).as("an existing harness is left as it was").isNull();

    for (final Persona persona : List.of(enabled, disabled, empty, alreadyHarnessed)) {
      assertThat(storedRow(persona).has(CONTEXT_FIELD)).isFalse();
      assertThat(storedVersion(persona).has(CONTEXT_FIELD)).isFalse();
      assertThat(versions(persona).getVersions()).isNotEmpty();
      assertThat(
              read(
                      send("GET", "personas/" + persona.getId(), null, ADMIN_TOKEN),
                      200,
                      Persona.class)
                  .getId())
          .isEqualTo(persona.getId());
    }

    final CompiledAgentContext compiled = compileFor(member);
    assertThat(compiled.getHarness().getPersona().getId()).isEqualTo(migrated.getId());
    assertThat(compiled.getText()).contains(enabledContext.getPrompt());
  }

  private static PersonaContextDefinition context(final boolean enabled, final String prompt) {
    return new PersonaContextDefinition()
        .withEnabled(enabled)
        .withPrompt(prompt)
        .withRules(
            List.of(
                new ContextRule()
                    .withId(UUID.randomUUID())
                    .withName("Finance tables")
                    .withEntityType(Entity.TABLE)
                    .withQueryFilter("{\"query\":{\"term\":{\"tier.tagFQN\":\"Tier.Tier1\"}}}")
                    .withSections(Set.of(ContextSection.DESCRIPTION))
                    .withMaxAssets(5)
                    .withEnabled(true)
                    .withFilteredInSearch(true)));
  }

  /** The persona row and one version row as the previous release stored them. */
  private static void seedPreviousReleaseShape(
      final Persona persona, final PersonaContextDefinition context) {
    final String value = JsonUtils.pojoToJson(context);
    final String personaJson = storedRow(persona).toString();
    jdbi()
        .useHandle(
            handle -> {
              handle
                  .createUpdate(setContextSql())
                  .bind("id", persona.getId().toString())
                  .bind("value", value)
                  .execute();
              handle
                  .createUpdate(insertVersionSql())
                  .bind("id", persona.getId().toString())
                  .bind("extension", VERSION_EXTENSION)
                  .bind("json", withContext(personaJson, value))
                  .execute();
            });
  }

  private static String withContext(final String personaJson, final String context) {
    final ObjectNode node = (ObjectNode) JsonUtils.readTree(personaJson);
    node.set(CONTEXT_FIELD, JsonUtils.readTree(context));
    node.put("version", 0.1);
    return node.toString();
  }

  private static String setContextSql() {
    return switch (dialect()) {
      case MYSQL -> "UPDATE persona_entity SET json = "
          + "JSON_SET(json, '$.contextDefinition', CAST(:value AS JSON)) WHERE id = :id";
      case POSTGRES -> "UPDATE persona_entity SET json = "
          + "jsonb_set(json, '{contextDefinition}', CAST(:value AS jsonb)) WHERE id = :id";
    };
  }

  private static String insertVersionSql() {
    return switch (dialect()) {
      case MYSQL -> "INSERT INTO entity_extension (id, extension, jsonSchema, json) "
          + "VALUES (:id, :extension, 'persona', CAST(:json AS JSON))";
      case POSTGRES -> "INSERT INTO entity_extension (id, extension, jsonSchema, json) "
          + "VALUES (:id, :extension, 'persona', CAST(:json AS jsonb))";
    };
  }

  private static void runMigration() {
    jdbi().useHandle(handle -> PersonaAgentHarnessMigration.migrate(handle, dialect()));
  }

  private static JsonNode storedRow(final Persona persona) {
    return JsonUtils.readTree(
        jdbi()
            .withHandle(
                handle ->
                    handle
                        .createQuery("SELECT json FROM persona_entity WHERE id = :id")
                        .bind("id", persona.getId().toString())
                        .mapTo(String.class)
                        .one()));
  }

  private static JsonNode storedVersion(final Persona persona) {
    return JsonUtils.readTree(
        jdbi()
            .withHandle(
                handle ->
                    handle
                        .createQuery(
                            "SELECT json FROM entity_extension WHERE id = :id AND extension = :extension")
                        .bind("id", persona.getId().toString())
                        .bind("extension", VERSION_EXTENSION)
                        .mapTo(String.class)
                        .one()));
  }

  private static EntityHistory versions(final Persona persona) {
    return read(
        send("GET", "personas/" + persona.getId() + "/versions", null, ADMIN_TOKEN),
        200,
        EntityHistory.class);
  }

  private static HttpResponse<String> personaState(final Persona persona) {
    return send(
        "GET", HARNESS + "/persona/" + encoded(persona.getFullyQualifiedName()), null, ADMIN_TOKEN);
  }

  private static AgentHarness harnessOf(final Persona persona) {
    return read(personaState(persona), 200, AgentHarnessState.class).getPublished();
  }

  private static User createMember(final String name, final Persona persona) {
    return read(
        send(
            "POST",
            "users",
            new CreateUser()
                .withName(name)
                .withEmail(name + "@example.com")
                .withPersonas(List.of(persona.getEntityReference()))
                .withDefaultPersona(persona.getEntityReference()),
            ADMIN_TOKEN),
        201,
        User.class);
  }

  private static CompiledAgentContext compileFor(final User user) {
    return read(
        send(
            "POST",
            HARNESS + "/compile",
            new CompileAgentContext().withEvent(CompileEvent.SESSION_START),
            AgentHarnessApi.tokenFor(user)),
        200,
        CompiledAgentContext.class);
  }

  private static Jdbi jdbi() {
    return TestSuiteBootstrap.getJdbi();
  }

  private static ConnectionType dialect() {
    return TestSuiteBootstrap.getConnectionType();
  }
}
