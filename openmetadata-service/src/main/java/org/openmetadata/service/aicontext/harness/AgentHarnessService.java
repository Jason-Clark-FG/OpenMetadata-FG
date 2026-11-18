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
package org.openmetadata.service.aicontext.harness;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.openmetadata.schema.api.context.AgentHarnessManagedSettings;
import org.openmetadata.schema.api.context.AgentHarnessPersonaSummary;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.api.context.AgentHarnessStatus;
import org.openmetadata.schema.api.context.AgentHarnessSurfaces;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.api.context.PreviewAgentHarness;
import org.openmetadata.schema.api.context.ResolvedAgentHarness;
import org.openmetadata.schema.entity.app.AppExtension;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessEvent;
import org.openmetadata.schema.entity.context.AgentHarnessKind;
import org.openmetadata.schema.entity.context.HookEvent;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.type.EntityHistory;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.PersonaContext;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.type.personaContext.PersonaContextCacheState;
import org.openmetadata.schema.utils.ResultList;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.PersonaContextBuilder;
import org.openmetadata.service.aicontext.PersonaContextCache;
import org.openmetadata.service.apps.bundles.mcp.McpAppConstants;
import org.openmetadata.service.exception.PreconditionFailedException;
import org.openmetadata.service.jdbi3.AgentHarnessRepository;
import org.openmetadata.service.jdbi3.AgentHarnessRepository.DraftSlot;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.jdbi3.ListFilter;
import org.openmetadata.service.jdbi3.PersonaRepository;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.ListCursors;

/**
 * The agent harness editor's operations: read a harness with its draft, write and discard the
 * draft, publish it, switch a harness on or off, and list personas with their harness. Every write
 * a persona harness makes is checked against the published default.
 */
public final class AgentHarnessService {
  private static final String PERSONA_LIST_FIELDS = "userCount";

  private final AgentHarnessRepository harnesses;
  private final PersonaRepository personas;
  private final AgentHarnessCompiler compiler;

  public AgentHarnessService(
      final AgentHarnessRepository harnesses,
      final PersonaRepository personas,
      final AgentHarnessCompiler compiler) {
    this.harnesses = harnesses;
    this.personas = personas;
    this.compiler = compiler;
  }

  /** Creates the company default once. A deployment that has it keeps its settings. */
  public void seedDefault() {
    harnesses.initializeEntity(HarnessSpecs.seedDefault(System.currentTimeMillis()));
  }

  /** What the caller's own agent receives at the request's event. */
  public CompiledAgentContext compile(
      final SecurityContext securityContext, final CompileAgentContext request) {
    if (request == null || request.getEvent() == null) {
      throw new IllegalArgumentException("A compile request needs an event");
    }
    return compiler.compile(securityContext, request);
  }

  /**
   * What the calling administrator's agent would receive at {@code event} if the harness had the
   * request's settings, or its draft, or its published settings, in that order.
   */
  public CompiledAgentContext preview(
      final UUID harnessId,
      final CompileEvent event,
      final PreviewAgentHarness request,
      final SecurityContext securityContext) {
    final AgentHarness harness = harnesses.getById(harnessId);
    final PreviewAgentHarness body = request == null ? new PreviewAgentHarness() : request;
    final AgentHarnessSpec spec =
        body.getSpec() == null ? shownSpec(harness) : validated(harness, body.getSpec());
    return compiler.preview(
        securityContext,
        harness,
        spec,
        new CompileAgentContext()
            .withEvent(event)
            .withPrompt(body.getPrompt())
            .withToolName(body.getToolName())
            .withToolInput(body.getToolInput())
            .withToolOutput(body.getToolOutput()));
  }

  /** The entities a rule would select, for the rule editor of a harness. */
  public PersonaContextBuilder.RulePreview previewRule(
      final UUID harnessId, final ContextRule rule) {
    harnesses.getById(harnessId);
    return PersonaContextBuilder.preview(rule);
  }

  /** Agent host settings that enforce the published default's MCP access. */
  public AgentHarnessManagedSettings managedSettings(final String serverUrl) {
    return ManagedSettingsBuilder.build(harnesses.getDefault().getToolAccess(), serverUrl);
  }

  /** Which AI surfaces called an MCP tool in the last 7 days. */
  public AgentHarnessSurfaces surfaces() {
    final CollectionDAO.AppExtensionTimeSeries usage =
        Entity.getCollectionDAO().appExtensionTimeSeriesDao();
    return SurfaceStatusReader.read(
        System.currentTimeMillis(),
        (pattern, since) -> {
          final Long latest =
              usage.latestTimestampByClientName(
                  McpAppConstants.MCP_APP_NAME,
                  AppExtension.ExtensionType.LIMITS.toString(),
                  since,
                  pattern);
          return latest == null ? -1L : latest;
        });
  }

  public AgentHarnessState defaultState() {
    return state(harnesses.getDefault());
  }

  public AgentHarnessState personaState(final String personaFqn) {
    final EntityReference persona = personaByName(personaFqn);
    return state(
        harnesses
            .findByPersona(persona.getId())
            .orElseThrow(
                () ->
                    new ClientErrorException(
                        "Persona " + personaFqn + " has no agent harness",
                        Response.Status.NOT_FOUND)));
  }

  /** A new, unpublished persona harness whose draft starts from the default's budgets. */
  public AgentHarnessState createForPersona(final String personaFqn, final String createdBy) {
    final EntityReference persona = personaByName(personaFqn);
    if (harnesses.findByPersona(persona.getId()).isPresent()) {
      throw new ClientErrorException(
          "Persona " + personaFqn + " already has an agent harness", Response.Status.CONFLICT);
    }
    final AgentHarness created =
        harnesses.create(null, newPersonaHarness(persona, createdBy), createdBy, null);
    final AgentHarnessSpec initial = HarnessSpecs.initialPersonaSpec(publishedDefaultSpec());
    harnesses.writeDraft(
        created.getId(), harnesses.readDraft(created.getId()).revision(), initial, createdBy);
    return state(harnesses.getById(created.getId()));
  }

  public AgentHarnessState writeDraft(
      final UUID harnessId,
      final int expectedRevision,
      final AgentHarnessSpec requested,
      final String updatedBy) {
    final AgentHarness harness = harnesses.getById(harnessId);
    final AgentHarnessSpec spec = validated(harness, requested);
    AgentHarnessRepository.validateChangedRuleFilters(spec, knownRules(harness));
    harnesses.writeDraft(harnessId, expectedRevision, spec, updatedBy);
    return state(harness);
  }

  public AgentHarnessState discardDraft(
      final UUID harnessId, final Integer expectedRevision, final String updatedBy) {
    final AgentHarness harness = harnesses.getById(harnessId);
    final DraftSlot slot = harnesses.readDraft(harnessId);
    harnesses.clearDraft(harnessId, revisionToReplace(slot, expectedRevision), updatedBy);
    return state(harness);
  }

  /** Publishes the draft as one version of the harness. */
  public AgentHarnessState publish(
      final UUID harnessId, final Integer expectedRevision, final String publishedBy) {
    final AgentHarness harness = harnesses.getById(harnessId);
    final DraftSlot slot = harnesses.readDraft(harnessId);
    final int revision = revisionToReplace(slot, expectedRevision);
    if (!slot.hasDraft()) {
      throw new IllegalArgumentException("Agent harness " + harnessId + " has no draft to publish");
    }
    final AgentHarnessSpec spec = validated(harness, slot.draft());
    harnesses.publish(harness, spec, revision, publishedBy);
    rebuildContext(harness);
    return state(harnesses.getById(harnessId));
  }

  public AgentHarnessState setEnabled(
      final UUID harnessId, final boolean enabled, final String updatedBy) {
    final AgentHarness harness = harnesses.getById(harnessId);
    harnesses.setEnabled(harness, enabled, updatedBy);
    rebuildContext(harness);
    return state(harnesses.getById(harnessId));
  }

  /**
   * The context cache is keyed by the merged definition, so a publish already misses every entry
   * it changes. A persona's document is rebuilt in the background so its list figures stay
   * current; a default publish changes every persona, and each one is rebuilt when next read.
   */
  private void rebuildContext(final AgentHarness harness) {
    if (!isDefault(harness)) {
      PersonaContextCache.getInstance()
          .refreshAsync(
              personas.get(
                  null,
                  harness.getPersona().getId(),
                  personas.getFields(""),
                  Include.NON_DELETED,
                  false));
    }
  }

  public EntityHistory versions(final UUID harnessId) {
    return harnesses.listVersions(harnesses.getById(harnessId).getId());
  }

  public AgentHarness version(final UUID harnessId, final String version) {
    return harnesses.getVersion(harnesses.getById(harnessId).getId(), version);
  }

  /** One page of personas, paged as `GET /v1/personas` pages them, each with its harness. */
  public ResultList<AgentHarnessPersonaSummary> personaSummaries(
      final UriInfo uriInfo, final int limit, final String before, final String after) {
    final ResultList<Persona> page = personaPage(uriInfo, limit, before, after);
    final Map<UUID, AgentHarness> byPersona =
        harnesses.findByPersonas(page.getData().stream().map(Persona::getId).toList());
    final Set<UUID> withDraft =
        harnesses.idsWithDraft(byPersona.values().stream().map(AgentHarness::getId).toList());
    final AgentHarness defaultHarness = harnesses.getDefault();
    final List<AgentHarnessPersonaSummary> summaries =
        page.getData().stream()
            .map(
                persona ->
                    summary(persona, defaultHarness, byPersona.get(persona.getId()), withDraft))
            .toList();
    // The persona page's cursors are already encoded, so they are passed on as they are.
    return new ResultList<>(summaries).setPaging(page.getPaging());
  }

  private ResultList<Persona> personaPage(
      final UriInfo uriInfo, final int limit, final String before, final String after) {
    final Fields fields = personas.getFields(PERSONA_LIST_FIELDS);
    final ListFilter filter = new ListFilter(null);
    return before != null
        ? personas.listBefore(uriInfo, fields, filter, limit, ListCursors.unseal(before))
        : personas.listAfter(uriInfo, fields, filter, limit, ListCursors.unseal(after));
  }

  private AgentHarnessSpec shownSpec(final AgentHarness harness) {
    final DraftSlot slot = harnesses.readDraft(harness.getId());
    return slot.hasDraft() ? slot.draft() : HarnessSpecs.specOf(harness);
  }

  private AgentHarnessState state(final AgentHarness harness) {
    final DraftSlot slot = harnesses.readDraft(harness.getId());
    final AgentHarnessSpec shown = slot.hasDraft() ? slot.draft() : HarnessSpecs.specOf(harness);
    return new AgentHarnessState()
        .withPublished(harness)
        .withDraft(slot.draft())
        .withDraftRevision(slot.revision())
        .withStatus(status(harness, slot.hasDraft()))
        .withResolved(resolved(harness, shown));
  }

  private ResolvedAgentHarness resolved(final AgentHarness harness, final AgentHarnessSpec shown) {
    return isDefault(harness)
        ? HarnessResolver.display(shown)
        : HarnessResolver.display(publishedDefaultSpec(), shown);
  }

  private AgentHarnessSpec validated(final AgentHarness harness, final AgentHarnessSpec requested) {
    return isDefault(harness)
        ? HarnessDraftValidator.forDefault(requested)
        : HarnessDraftValidator.forPersona(requested, publishedDefaultSpec());
  }

  private List<ContextRule> knownRules(final AgentHarness harness) {
    final List<ContextRule> rules =
        new ArrayList<>(
            listOrEmpty(HarnessSpecs.contextOf(HarnessSpecs.specOf(harness)).getRules()));
    final DraftSlot slot = harnesses.readDraft(harness.getId());
    if (slot.hasDraft()) {
      rules.addAll(listOrEmpty(HarnessSpecs.contextOf(slot.draft()).getRules()));
    }
    return rules;
  }

  private AgentHarnessSpec publishedDefaultSpec() {
    return HarnessSpecs.specOf(harnesses.getDefault());
  }

  private static AgentHarnessPersonaSummary summary(
      final Persona persona,
      final AgentHarness defaultHarness,
      final AgentHarness harness,
      final Set<UUID> withDraft) {
    final Optional<AgentHarnessSpec> effective = HarnessResolver.effective(defaultHarness, harness);
    final AgentHarnessPersonaSummary summary =
        new AgentHarnessPersonaSummary()
            .withPersona(persona.getEntityReference())
            .withUserCount(persona.getUserCount() == null ? 0 : persona.getUserCount())
            .withStatus(status(harness, harness != null && withDraft.contains(harness.getId())))
            .withEnabledEvents(effective.map(AgentHarnessService::enabledEvents).orElse(List.of()))
            .withCharacterBudget(
                effective.map(spec -> spec.getContext().getCharacterBudget()).orElse(0));
    if (harness != null) {
      summary
          .withHarnessId(harness.getId())
          .withEnabled(harness.getEnabled())
          .withAdditions(HarnessAdditionCounts.of(harness));
    }
    return withCacheFigures(summary, persona, HarnessContexts.merged(defaultHarness, harness));
  }

  // The list reads the context cache and never compiles, so a long list stays cheap.
  private static AgentHarnessPersonaSummary withCacheFigures(
      final AgentHarnessPersonaSummary summary,
      final Persona persona,
      final PersonaContextDefinition definition) {
    final PersonaContextCache.CacheSnapshot snapshot =
        PersonaContextCache.getInstance().snapshot(persona, definition);
    summary
        .withCacheState(
            snapshot.state() == null ? PersonaContextCacheState.STALE : snapshot.state())
        .withCharactersUsed(0);
    if (snapshot.value() != null) {
      final PersonaContext context = snapshot.value().context();
      summary
          .withCharactersUsed(snapshot.value().markdown().length())
          .withLastGeneratedAt(context.getGeneratedAt());
    }
    return summary;
  }

  private static List<HookEvent> enabledEvents(final AgentHarnessSpec spec) {
    return listOrEmpty(spec.getEvents()).stream()
        .filter(event -> Boolean.TRUE.equals(event.getEnabled()))
        .map(AgentHarnessEvent::getId)
        .toList();
  }

  static AgentHarnessStatus status(final AgentHarness harness, final boolean hasDraft) {
    final AgentHarnessStatus status;
    if (harness == null) {
      status = AgentHarnessStatus.INHERITED;
    } else if (hasDraft) {
      status = AgentHarnessStatus.DRAFT;
    } else if (harness.getPublishedAt() == null) {
      status = AgentHarnessStatus.NOT_CONFIGURED;
    } else {
      status = AgentHarnessStatus.PUBLISHED;
    }
    return status;
  }

  private static int revisionToReplace(final DraftSlot slot, final Integer expectedRevision) {
    if (expectedRevision != null && expectedRevision != slot.revision()) {
      throw new PreconditionFailedException(
          "The draft has changed since it was read. Reload the harness and retry.");
    }
    return slot.revision();
  }

  private static boolean isDefault(final AgentHarness harness) {
    return harness.getKind() == AgentHarnessKind.DEFAULT;
  }

  private static EntityReference personaByName(final String personaFqn) {
    return Entity.getEntityReferenceByName(Entity.PERSONA, personaFqn, Include.NON_DELETED);
  }

  private static AgentHarness newPersonaHarness(
      final EntityReference persona, final String createdBy) {
    final String name = persona.getId().toString();
    return new AgentHarness()
        .withId(UUID.randomUUID())
        .withName(name)
        .withFullyQualifiedName(name)
        .withDisplayName(
            persona.getDisplayName() == null ? persona.getName() : persona.getDisplayName())
        .withKind(AgentHarnessKind.PERSONA)
        .withPersona(persona)
        .withEnabled(true)
        .withUpdatedBy(createdBy)
        .withUpdatedAt(System.currentTimeMillis());
  }
}
