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
package org.openmetadata.service.migration.utils.v210;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessKind;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.locator.ConnectionType;
import org.openmetadata.service.migration.utils.IdBatches;
import org.openmetadata.service.util.FullyQualifiedName;

/**
 * Moves each persona's AI context into a published persona agent harness, then removes {@code
 * contextDefinition} from persona rows and persona versions: persona.json no longer has the field
 * and rejects unknown ones, so a version that kept it could not be read.
 * (ADR:2026-11-18-agent-harness-is-an-entity-and-persona-harnesses-extend-the-default)
 *
 * <p>A persona whose context has a rule or a prompt gets a harness named by its id, with that
 * context, enabled as the context was. Its members then receive what they received before: the
 * default's rules, which the upgrade seeds empty, then the persona's. A persona that already has a
 * harness is skipped, so a rerun creates nothing. Both passes walk the persona ids in batches
 * (ADR:2026-10-09-data-migration-backfills-walk-ids-in-batches), and the field is removed only
 * after every harness exists, so an interrupted run never loses a context.
 */
@Slf4j
public final class PersonaAgentHarnessMigration {

  /** Run once per version through {@code DataMigrationStep}. */
  public static final String STEP_NAME = "persona-context-to-agent-harness";

  private static final String PERSONA_TABLE = "persona_entity";
  private static final String CONTEXT_FIELD = "contextDefinition";
  private static final String IDS_BIND = "ids";
  private static final String FALLBACK_AUTHOR = "admin";
  private static final int PER_TURN_TOKEN_BUDGET = 20000;
  private static final String PERSONAS_SQL = "SELECT json FROM persona_entity WHERE id IN (<ids>)";
  private static final String HARNESS_EXISTS_SQL =
      "SELECT COUNT(*) FROM agent_harness_entity WHERE personaId = :personaId";
  private static final String INSERT_DRAFT_SQL =
      "INSERT INTO agent_harness_draft (harnessId, json, revision, updatedBy, updatedAt) "
          + "VALUES (:harnessId, NULL, 0, :updatedBy, :updatedAt)";

  private PersonaAgentHarnessMigration() {}

  public static void migrate(final Handle handle, final ConnectionType connectionType) {
    final int created =
        IdBatches.fold(
            handle,
            PERSONA_TABLE,
            0,
            (count, ids) -> count + createHarnesses(handle, connectionType, ids));
    final int stripped =
        IdBatches.fold(
            handle,
            PERSONA_TABLE,
            0,
            (count, ids) -> count + stripContextDefinitions(handle, connectionType, ids));
    LOG.info(
        "Moved {} persona AI contexts into agent harnesses; removed contextDefinition from {} "
            + "persona rows and versions",
        created,
        stripped);
  }

  private static int createHarnesses(
      final Handle handle, final ConnectionType connectionType, final List<String> ids) {
    int created = 0;
    final List<String> personas =
        handle.createQuery(PERSONAS_SQL).bindList(IDS_BIND, ids).mapTo(String.class).list();
    for (final String json : personas) {
      final JsonNode persona = JsonUtils.readTree(json);
      if (hasContext(persona.get(CONTEXT_FIELD)) && !hasHarness(handle, persona)) {
        handle.useTransaction(
            transaction -> insertHarness(transaction, connectionType, harnessFor(persona)));
        created++;
      }
    }
    return created;
  }

  /** A context with a rule or a prompt; one with neither selects and says nothing. */
  static boolean hasContext(final JsonNode context) {
    return context != null
        && context.isObject()
        && (hasRule(context) || !nullOrEmpty(context.path("prompt").asText("").strip()));
  }

  private static boolean hasRule(final JsonNode context) {
    final JsonNode rules = context.get("rules");
    return rules != null && rules.isArray() && !rules.isEmpty();
  }

  private static boolean hasHarness(final Handle handle, final JsonNode persona) {
    return handle
            .createQuery(HARNESS_EXISTS_SQL)
            .bind("personaId", persona.get("id").asText())
            .mapTo(Integer.class)
            .one()
        > 0;
  }

  /**
   * The persona's context as a published persona harness. The context's own switch becomes the
   * harness's, because a harness context is always enabled and the harness is switched instead.
   */
  static AgentHarness harnessFor(final JsonNode persona) {
    final PersonaContextDefinition stored =
        JsonUtils.readValue(persona.get(CONTEXT_FIELD).toString(), PersonaContextDefinition.class);
    final String personaId = persona.get("id").asText();
    final long now = System.currentTimeMillis();
    return new AgentHarness()
        .withId(UUID.randomUUID())
        .withName(personaId)
        .withFullyQualifiedName(personaId)
        .withDisplayName(persona.path("displayName").asText(persona.path("name").asText()))
        .withKind(AgentHarnessKind.PERSONA)
        .withPersona(
            new EntityReference().withId(UUID.fromString(personaId)).withType(Entity.PERSONA))
        .withEnabled(!Boolean.FALSE.equals(stored.getEnabled()))
        .withPublishedAt(now)
        .withPerTurnTokenBudget(PER_TURN_TOKEN_BUDGET)
        .withContext(
            stored
                .withEnabled(true)
                .withCacheState(null)
                .withLastError(null)
                .withLastGeneratedAt(null))
        .withVersion(0.1)
        .withUpdatedAt(now)
        .withUpdatedBy(persona.path("updatedBy").asText(FALLBACK_AUTHOR))
        .withDeleted(false);
  }

  private static void insertHarness(
      final Handle handle, final ConnectionType connectionType, final AgentHarness harness) {
    handle
        .createUpdate(insertHarnessSql(connectionType))
        .bind("fqnHash", FullyQualifiedName.buildHash(harness.getFullyQualifiedName()))
        .bind("json", JsonUtils.pojoToJson(harness))
        .execute();
    handle
        .createUpdate(INSERT_DRAFT_SQL)
        .bind("harnessId", harness.getId().toString())
        .bind("updatedBy", harness.getUpdatedBy())
        .bind("updatedAt", harness.getUpdatedAt())
        .execute();
  }

  private static String insertHarnessSql(final ConnectionType connectionType) {
    return switch (connectionType) {
      case MYSQL -> "INSERT INTO agent_harness_entity (fqnHash, json) VALUES (:fqnHash, :json)";
      case POSTGRES -> "INSERT INTO agent_harness_entity (fqnHash, json) "
          + "VALUES (:fqnHash, (:json :: jsonb))";
    };
  }

  private static int stripContextDefinitions(
      final Handle handle, final ConnectionType connectionType, final List<String> ids) {
    final int rows =
        handle.createUpdate(stripPersonasSql(connectionType)).bindList(IDS_BIND, ids).execute();
    final int versions =
        handle.createUpdate(stripVersionsSql(connectionType)).bindList(IDS_BIND, ids).execute();
    return rows + versions;
  }

  private static String stripPersonasSql(final ConnectionType connectionType) {
    return switch (connectionType) {
      case MYSQL -> "UPDATE persona_entity SET json = JSON_REMOVE(json, '$.contextDefinition')"
          + " WHERE id IN (<ids>) AND JSON_CONTAINS_PATH(json, 'one', '$.contextDefinition')";
      case POSTGRES -> "UPDATE persona_entity SET json = json - 'contextDefinition'"
          + " WHERE id IN (<ids>) AND json -> 'contextDefinition' IS NOT NULL";
    };
  }

  private static String stripVersionsSql(final ConnectionType connectionType) {
    return switch (connectionType) {
      case MYSQL -> "UPDATE entity_extension SET json = JSON_REMOVE(json, '$.contextDefinition')"
          + " WHERE id IN (<ids>) AND extension LIKE 'persona.version.%'"
          + " AND JSON_CONTAINS_PATH(json, 'one', '$.contextDefinition')";
      case POSTGRES -> "UPDATE entity_extension SET json = json - 'contextDefinition'"
          + " WHERE id IN (<ids>) AND extension LIKE 'persona.version.%'"
          + " AND json -> 'contextDefinition' IS NOT NULL";
    };
  }
}
