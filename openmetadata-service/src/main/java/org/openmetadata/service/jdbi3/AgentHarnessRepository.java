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
package org.openmetadata.service.jdbi3;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessKind;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.change.ChangeSource;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.PersonaContextRuleValidation;
import org.openmetadata.service.aicontext.harness.HarnessSpecs;
import org.openmetadata.service.exception.PreconditionFailedException;
import org.openmetadata.service.governance.approval.ApprovalGate.Edit;
import org.openmetadata.service.jdbi3.AgentHarnessDAOs.AgentHarnessDAO;
import org.openmetadata.service.jdbi3.AgentHarnessDAOs.AgentHarnessDraftDAO;
import org.openmetadata.service.jdbi3.AgentHarnessDAOs.DraftRow;
import org.openmetadata.service.resources.context.AgentHarnessResource;
import org.openmetadata.service.security.ChangeActor;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.EntityUtil.RelationIncludes;

/**
 * Stores agent harnesses and their drafts. The entity is the published harness, written only by a
 * publish and by the enabled switch, so its versions are that history. The draft is one row beside
 * it that entity versioning never sees.
 */
public class AgentHarnessRepository extends EntityRepository<AgentHarness> {
  private static final String FIELD_ENABLED = "enabled";
  private static final String FIELD_PUBLISHED_AT = "publishedAt";
  private static final String FIELD_CONTEXT = "context";
  private static final String FIELD_COMPANY_INSTRUCTIONS = "companyInstructions";
  private static final String FIELD_PER_TURN_TOKEN_BUDGET = "perTurnTokenBudget";
  private static final String FIELD_EVENTS = "events";
  private static final String FIELD_SKILLS = "skills";
  private static final String FIELD_GUARDRAILS = "guardrails";
  private static final String FIELD_MEMORY = "memory";

  /** The draft of one harness and the revision a writer must name to replace it. */
  public record DraftSlot(int revision, AgentHarnessSpec draft) {
    public boolean hasDraft() {
      return draft != null;
    }
  }

  private final AgentHarnessDAO harnessDAO;
  private final AgentHarnessDraftDAO draftDAO;

  public AgentHarnessRepository() {
    super(
        AgentHarnessResource.COLLECTION_PATH,
        Entity.AGENT_HARNESS,
        AgentHarness.class,
        Entity.getCollectionDAO().agentHarnessDAO(),
        "",
        "");
    // Search results are not limited to administrators, and a harness is.
    supportsSearch = false;
    harnessDAO = Entity.getCollectionDAO().agentHarnessDAO();
    draftDAO = Entity.getCollectionDAO().agentHarnessDraftDAO();
  }

  // The editors read the database, because a publish made on another server must show at once.
  public AgentHarness getDefault() {
    return findByName(HarnessSpecs.DEFAULT_NAME, Include.ALL, false);
  }

  public AgentHarness getById(final UUID id) {
    return withPersonaReference(find(id, Include.ALL, false));
  }

  public Optional<AgentHarness> findByPersona(final UUID personaId) {
    return Optional.ofNullable(harnessDAO.findByPersonaId(personaId))
        .map(json -> withPersonaReference(JsonUtils.readValue(json, AgentHarness.class)));
  }

  /** The harnesses of the given personas, by persona id. A persona without one has no entry. */
  public Map<UUID, AgentHarness> findByPersonas(final Collection<UUID> personaIds) {
    final Map<UUID, AgentHarness> harnesses = new HashMap<>();
    if (!personaIds.isEmpty()) {
      final List<String> ids = personaIds.stream().map(UUID::toString).toList();
      for (final String json : harnessDAO.findByPersonaIds(ids)) {
        final AgentHarness harness = JsonUtils.readValue(json, AgentHarness.class);
        harnesses.put(harness.getPersona().getId(), harness);
      }
    }
    return harnesses;
  }

  public Set<UUID> idsWithDraft(final Collection<UUID> harnessIds) {
    Set<UUID> ids = Set.of();
    if (!harnessIds.isEmpty()) {
      ids =
          draftDAO.findWithDraft(harnessIds.stream().map(UUID::toString).toList()).stream()
              .map(UUID::fromString)
              .collect(Collectors.toSet());
    }
    return ids;
  }

  public DraftSlot readDraft(final UUID harnessId) {
    final DraftRow row = draftDAO.find(harnessId);
    if (row == null) {
      throw new IllegalStateException("Agent harness " + harnessId + " has no draft row");
    }
    return new DraftSlot(
        row.revision(),
        row.json() == null ? null : JsonUtils.readValue(row.json(), AgentHarnessSpec.class));
  }

  /** Replaces the draft, provided it still has the revision the caller read. */
  public void writeDraft(
      final UUID harnessId,
      final int expectedRevision,
      final AgentHarnessSpec draft,
      final String updatedBy) {
    requireCurrentRevision(
        draftDAO.write(
            harnessId,
            expectedRevision,
            JsonUtils.pojoToJson(draft),
            updatedBy,
            System.currentTimeMillis()));
  }

  /** Removes the draft, provided it still has the revision the caller read. */
  public void clearDraft(final UUID harnessId, final int expectedRevision, final String updatedBy) {
    requireCurrentRevision(clearDraftIfCurrent(harnessId, expectedRevision, updatedBy));
  }

  /**
   * Makes {@code spec} the published harness as one entity version, then removes the draft it came
   * from. A draft written after {@code draftRevision} was read is newer than this publish, so it is
   * kept.
   */
  public AgentHarness publish(
      final AgentHarness harness,
      final AgentHarnessSpec spec,
      final int draftRevision,
      final String publishedBy) {
    final AgentHarness published = JsonUtils.deepCopy(harness, AgentHarness.class);
    HarnessSpecs.apply(spec, published);
    published.setPublishedAt(System.currentTimeMillis());
    final AgentHarness stored = updateIfCurrent(null, harness, published, publishedBy).getEntity();
    clearDraftIfCurrent(harness.getId(), draftRevision, publishedBy);
    return stored;
  }

  public AgentHarness setEnabled(
      final AgentHarness harness, final boolean enabled, final String updatedBy) {
    final AgentHarness updated =
        JsonUtils.deepCopy(harness, AgentHarness.class).withEnabled(enabled);
    return updateIfCurrent(null, harness, updated, updatedBy).getEntity();
  }

  public void deleteForPersona(final UUID personaId, final String deletedBy) {
    final String json = harnessDAO.findByPersonaId(personaId);
    if (json != null) {
      delete(deletedBy, JsonUtils.readValue(json, AgentHarness.class).getId(), false, true);
    }
  }

  /**
   * Checks the filters of {@code requested}'s rules with the search engine, skipping a rule whose
   * entity type and filter are unchanged from one of {@code knownRules}, as persona rules are.
   */
  public static void validateChangedRuleFilters(
      final AgentHarnessSpec requested, final List<ContextRule> knownRules) {
    final List<ContextRule> rules =
        requested.getContext() == null ? List.of() : listOrEmpty(requested.getContext().getRules());
    for (final ContextRule rule : rules) {
      if (!nullOrEmpty(rule.getQueryFilter())
          && PersonaRepository.requiresRuleValidation(rule, knownRules, false)) {
        PersonaContextRuleValidation.validate(rule, Entity.getSearchRepository());
      }
    }
  }

  @Override
  protected void setFields(
      final AgentHarness harness, final Fields fields, final RelationIncludes relationIncludes) {
    withPersonaReference(harness);
  }

  @Override
  protected void clearFields(final AgentHarness harness, final Fields fields) {
    // Every field is part of the entity JSON.
  }

  @Override
  protected void prepare(final AgentHarness harness, final boolean update) {
    if (harness.getKind() == AgentHarnessKind.PERSONA) {
      preparePersonaHarness(harness);
    } else {
      prepareDefaultHarness(harness);
    }
    if (harness.getContext() != null && Boolean.FALSE.equals(harness.getContext().getEnabled())) {
      throw new IllegalArgumentException(
          "context.enabled cannot be false. The harness's own enabled flag turns it off");
    }
  }

  private static void prepareDefaultHarness(final AgentHarness harness) {
    final boolean isTheDefault =
        HarnessSpecs.DEFAULT_NAME.equals(harness.getName()) && harness.getPersona() == null;
    if (!isTheDefault) {
      throw new IllegalArgumentException(
          "An agent harness is the default, named "
              + HarnessSpecs.DEFAULT_NAME
              + " and without a persona, or it is a persona harness");
    }
  }

  private static void preparePersonaHarness(final AgentHarness harness) {
    if (harness.getPersona() == null || harness.getPersona().getId() == null) {
      throw new IllegalArgumentException("A persona agent harness needs its persona");
    }
    final EntityReference persona =
        Entity.getEntityReferenceById(Entity.PERSONA, harness.getPersona().getId(), Include.ALL);
    if (!persona.getId().toString().equals(harness.getName())) {
      throw new IllegalArgumentException("A persona agent harness is named by its persona's id");
    }
    if (harness.getCompanyInstructions() != null) {
      throw new IllegalArgumentException("companyInstructions belong to the default harness");
    }
    harness.setPersona(persona);
  }

  @Override
  protected void storeEntity(final AgentHarness harness, final boolean update) {
    // Only the id is stored, so a version never shows a persona name that has since changed.
    final EntityReference persona = harness.getPersona();
    harness.setPersona(
        persona == null
            ? null
            : new EntityReference().withId(persona.getId()).withType(Entity.PERSONA));
    store(harness, update);
    harness.setPersona(persona);
    if (!update) {
      draftDAO.insertEmpty(harness.getId(), harness.getUpdatedBy(), harness.getUpdatedAt());
    }
  }

  @Override
  protected void storeRelationships(final AgentHarness harness) {
    // The persona is referred to from the entity JSON, which the personaId column indexes.
  }

  @Override
  protected void entitySpecificCleanup(final AgentHarness harness) {
    draftDAO.delete(harness.getId());
  }

  // The seeded organization policies grant edit on every resource to bots and to owners, so the
  // policy check alone would let them write a harness through any generic path.
  @Override
  protected void authorizeWrite(
      final AgentHarness original,
      final AgentHarness updated,
      final ChangeActor actor,
      final Edit edit) {
    Entity.getAuthorizer().authorizeAdmin(actor.userName());
  }

  @Override
  public EntityRepository<AgentHarness>.EntityUpdater getUpdater(
      final AgentHarness original,
      final AgentHarness updated,
      final Operation operation,
      final ChangeSource changeSource) {
    return new AgentHarnessUpdater(original, updated, operation, changeSource);
  }

  private int clearDraftIfCurrent(
      final UUID harnessId, final int expectedRevision, final String updatedBy) {
    return draftDAO.clear(harnessId, expectedRevision, updatedBy, System.currentTimeMillis());
  }

  private AgentHarness withPersonaReference(final AgentHarness harness) {
    if (harness.getPersona() != null) {
      harness.setPersona(
          Entity.getEntityReferenceById(Entity.PERSONA, harness.getPersona().getId(), Include.ALL));
    }
    return harness;
  }

  private static void requireCurrentRevision(final int rowsChanged) {
    if (rowsChanged == 0) {
      throw new PreconditionFailedException(
          "The draft has changed since it was read. Reload the harness and retry.");
    }
  }

  public class AgentHarnessUpdater extends EntityUpdater {
    public AgentHarnessUpdater(
        final AgentHarness original,
        final AgentHarness updated,
        final Operation operation,
        final ChangeSource changeSource) {
      super(original, updated, operation, changeSource);
    }

    @Transaction
    @Override
    public void entitySpecificUpdate(final boolean consolidatingChanges) {
      recordChange(FIELD_ENABLED, original.getEnabled(), updated.getEnabled());
      recordChange(FIELD_PUBLISHED_AT, original.getPublishedAt(), updated.getPublishedAt());
      recordChange(FIELD_CONTEXT, original.getContext(), updated.getContext(), true);
      recordChange(
          FIELD_COMPANY_INSTRUCTIONS,
          original.getCompanyInstructions(),
          updated.getCompanyInstructions());
      recordChange(
          FIELD_PER_TURN_TOKEN_BUDGET,
          original.getPerTurnTokenBudget(),
          updated.getPerTurnTokenBudget());
      recordChange(FIELD_EVENTS, original.getEvents(), updated.getEvents(), true);
      recordChange(FIELD_SKILLS, original.getSkills(), updated.getSkills(), true);
      recordChange(FIELD_GUARDRAILS, original.getGuardrails(), updated.getGuardrails(), true);
      recordChange(FIELD_MEMORY, original.getMemory(), updated.getMemory(), true);
    }
  }
}
