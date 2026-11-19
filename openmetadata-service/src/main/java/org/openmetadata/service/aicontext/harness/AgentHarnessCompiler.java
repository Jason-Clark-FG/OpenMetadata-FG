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
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.AppliedHarness;
import org.openmetadata.schema.api.context.AppliedHarnesses;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.api.context.GuardrailAction;
import org.openmetadata.schema.api.context.GuardrailDecision;
import org.openmetadata.schema.api.context.SkillDefinition;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessEvent;
import org.openmetadata.schema.entity.context.AgentHarnessKind;
import org.openmetadata.schema.entity.context.AgentHarnessMemory;
import org.openmetadata.schema.entity.context.AgentHarnessSkill;
import org.openmetadata.schema.entity.context.AgentHarnessToolAccess;
import org.openmetadata.schema.entity.context.HookEvent;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.PersonaContext;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.PersonaContextAccess;
import org.openmetadata.service.aicontext.PersonaContextBuilder;
import org.openmetadata.service.aicontext.PersonaContextCache;
import org.openmetadata.service.aicontext.harness.CompileSelector.Document;
import org.openmetadata.service.aicontext.harness.CompileSelector.Packed;
import org.openmetadata.service.aicontext.harness.ContextItem.AssetRef;
import org.openmetadata.service.aicontext.harness.ContextItem.Origin;
import org.openmetadata.service.aicontext.harness.GuardrailEvaluators.Finding;
import org.openmetadata.service.aicontext.harness.GuardrailEvaluators.LiveAsset;
import org.openmetadata.service.jdbi3.AgentHarnessRepository;
import org.openmetadata.service.security.Authorizer;

/**
 * Compiles what a caller's agent receives at one hook event, from the published default and the
 * harness of the caller's persona. The persona comes from the caller's own sign-in and active
 * persona, never from the request.
 */
public final class AgentHarnessCompiler {
  // Which always-in-context items each agent session has received, so UserPromptSubmit does not
  // repeat them. Per server: a session served by two servers may receive an item twice.
  private static final Cache<String, Set<String>> SENT_PER_SESSION =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterAccess(Duration.ofHours(12)).build();

  // Sessions whose SessionStart content the agent holds. Claude Code and Codex skip an mcp_tool
  // SessionStart hook at launch, so the first prompt of an unseen session carries it instead.
  private static final Cache<String, Boolean> STARTED_SESSIONS =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterAccess(Duration.ofHours(24)).build();

  private final AgentHarnessRepository harnesses;
  private final HarnessContexts contexts;
  private final Authorizer authorizer;

  /** The harnesses one compile applies, and the persona whose context document it reads. */
  record Caller(
      AgentHarness defaultHarness,
      AgentHarness personaHarness,
      Persona persona,
      Persona documentPersona) {}

  private interface DocumentSource {
    PersonaContext load(Persona persona, SecurityContext securityContext);
  }

  public AgentHarnessCompiler(final AgentHarnessRepository harnesses, final Authorizer authorizer) {
    this.harnesses = harnesses;
    this.contexts = new HarnessContexts(harnesses);
    this.authorizer = authorizer;
  }

  /** What the caller's own agent receives, reading the persona context through its cache. */
  public CompiledAgentContext compile(
      final SecurityContext securityContext, final CompileAgentContext request) {
    final Caller caller = caller(securityContext);
    final PersonaContextDefinition definition =
        PersonaContextCache.definitionOf(caller.documentPersona());
    return compile(caller, definition, request, securityContext, this::cachedDocument);
  }

  /**
   * What the caller's agent receives at SessionStart with {@code persona} as its active persona.
   * Only the persona's members, administrators and bots can ask; only the last two can rebuild the
   * persona's cached context first.
   */
  public CompiledAgentContext sessionStartAs(
      final SecurityContext securityContext, final Persona persona, final boolean refresh) {
    PersonaContextAccess.authorize(securityContext, persona);
    if (refresh) {
      PersonaContextAccess.authorizeRefresh(securityContext);
      PersonaContextCache.getInstance().refresh(persona);
    }
    final Caller caller = callerAs(persona);
    return compile(
        caller,
        PersonaContextCache.definitionOf(caller.documentPersona()),
        new CompileAgentContext().withEvent(CompileEvent.SESSION_START),
        securityContext,
        this::cachedDocument);
  }

  /** The harnesses that apply to the caller, without compiling any context. */
  public AppliedHarnesses appliedTo(final SecurityContext securityContext) {
    return applied(caller(securityContext));
  }

  /** The skills the caller's harness offers, in registry order. */
  public List<SkillDefinition> skills(final SecurityContext securityContext) {
    final Caller caller = caller(securityContext);
    final Set<String> offered =
        effective(caller).map(spec -> listOrEmpty(spec.getSkills())).orElse(List.of()).stream()
            .filter(skill -> Boolean.TRUE.equals(skill.getEnabled()))
            .map(AgentHarnessSkill::getName)
            .collect(Collectors.toSet());
    return SkillRegistry.definitions().stream()
        .filter(skill -> offered.contains(skill.getName()))
        .toList();
  }

  /** The memory settings that apply to the caller and the persona they are served under, if any. */
  public Optional<AppliedMemory> memory(final SecurityContext securityContext) {
    final Caller caller = caller(securityContext);
    return effective(caller)
        .map(AgentHarnessSpec::getMemory)
        .map(settings -> new AppliedMemory(settings, caller.persona()));
  }

  /** The memory settings in force for a caller; {@code persona} is null when none applies. */
  public record AppliedMemory(AgentHarnessMemory settings, Persona persona) {}

  /**
   * What the calling administrator's agent would receive if {@code harness} had {@code spec},
   * built without the cache.
   */
  public CompiledAgentContext preview(
      final SecurityContext securityContext,
      final AgentHarness harness,
      final AgentHarnessSpec spec,
      final CompileAgentContext request) {
    final Caller caller = previewCaller(harness, spec);
    final PersonaContextDefinition definition =
        HarnessContexts.merged(caller.defaultHarness(), caller.personaHarness());
    return compile(
        caller,
        definition,
        request,
        securityContext,
        (persona, context) ->
            new PersonaContextBuilder(persona, definition)
                .withSecurity(authorizer, context)
                .build()
                .context());
  }

  private CompiledAgentContext compile(
      final Caller caller,
      final PersonaContextDefinition definition,
      final CompileAgentContext request,
      final SecurityContext securityContext,
      final DocumentSource documents) {
    final CompiledAgentContext compiled =
        new CompiledAgentContext()
            .withHarness(applied(caller))
            .withText("")
            .withTokens(0)
            .withSegments(List.of());
    effective(caller)
        .ifPresent(
            effective ->
                fill(
                    compiled,
                    new Turn(caller, effective, definition, request, securityContext),
                    documents));
    return compiled;
  }

  /** The settings that apply to the caller; empty when the default harness is disabled. */
  private static Optional<AgentHarnessSpec> effective(final Caller caller) {
    return HarnessResolver.effective(caller.defaultHarness(), caller.personaHarness());
  }

  /** Everything one compile reads. */
  private record Turn(
      Caller caller,
      AgentHarnessSpec effective,
      PersonaContextDefinition definition,
      CompileAgentContext request,
      SecurityContext securityContext) {
    HookEvent event() {
      return HookEvent.fromValue(request.getEvent().value());
    }
  }

  private void fill(
      final CompiledAgentContext compiled, final Turn turn, final DocumentSource documents) {
    final Document document = document(turn, documents);
    final Optional<GuardrailDecision> decision = decision(turn, document);
    final boolean opening = opensSession(turn);
    final List<ContextItem> candidates =
        withAssetUses(turn, candidates(turn, document, decision, opening));
    final Packed packed = CompileSelector.pack(candidates, budget(turn, opening));
    remember(turn, packed);
    markStarted(turn);
    compiled
        .withText(packed.text())
        .withTokens(packed.tokens())
        .withSegments(packed.segments())
        .withDecision(decision.orElse(null));
  }

  /**
   * Names the assets that answer each selected glossary term or metric, so the agent can go from
   * the business term to a table. Done after selection and before packing so the added text counts
   * against the budget.
   */
  private static List<ContextItem> withAssetUses(
      final Turn turn, final List<ContextItem> candidates) {
    final List<AssetRef> subjects =
        candidates.stream().map(ContextItem::knowledge).filter(Objects::nonNull).toList();
    List<ContextItem> enriched = candidates;
    if (!subjects.isEmpty()) {
      final Map<AssetRef, List<AssetUse>> uses =
          new KnowledgeAssetLookup(Entity.getSearchRepository(), turn.securityContext())
              .usesOf(subjects);
      enriched = candidates.stream().map(item -> withUses(item, uses)).toList();
    }
    return enriched;
  }

  private static ContextItem withUses(
      final ContextItem item, final Map<AssetRef, List<AssetUse>> uses) {
    final String line = AssetUse.render(uses.getOrDefault(item.knowledge(), List.of()));
    return line.isEmpty() ? item : item.withText(item.text().stripTrailing() + "\n" + line);
  }

  private Document document(final Turn turn, final DocumentSource documents) {
    final PersonaContext context =
        listOrEmpty(turn.definition().getRules()).isEmpty()
            ? new PersonaContext()
            : documents.load(turn.caller().documentPersona(), turn.securityContext());
    return HarnessDocuments.from(
        new HarnessDocuments.Sources(
            turn.effective(),
            HarnessSpecs.specOf(turn.caller().defaultHarness()),
            turn.definition(),
            context));
  }

  private List<ContextItem> candidates(
      final Turn turn,
      final Document document,
      final Optional<GuardrailDecision> decision,
      final boolean opening) {
    final CompileAgentContext request = turn.request();
    return switch (turn.event()) {
      case SESSION_START -> CompileSelector.sessionStart(document);
      case USER_PROMPT_SUBMIT -> Stream.concat(
              opening ? CompileSelector.sessionStart(document).stream() : Stream.empty(),
              CompileSelector.userPromptSubmit(document, request.getPrompt(), sentKeys(turn))
                  .stream())
          .toList();
      case PRE_TOOL_USE -> CompileSelector.preToolUse(
          document, ToolCallText.of(request.getToolInput()), guardrailNotes(decision));
      case POST_TOOL_USE -> incidentNotes(turn, document);
      case STOP -> List.of();
    };
  }

  private Optional<GuardrailDecision> decision(final Turn turn, final Document document) {
    Optional<GuardrailDecision> decision = Optional.empty();
    if (turn.event() == HookEvent.PRE_TOOL_USE) {
      final List<Finding> findings = new ArrayList<>();
      ToolAccessPolicy.parse(turn.request().getToolName())
          .flatMap(
              call ->
                  GuardrailEvaluators.toolAccess(
                      ToolAccessPolicy.decide(toolAccessLayers(turn.caller()), call), call))
          .ifPresent(findings::add);
      findings.addAll(
          GuardrailEvaluators.evaluate(
              liveAssets(turn, document, ToolCallText.of(turn.request().getToolInput())),
              turn.effective().getGuardrails()));
      decision = Optional.of(GuardrailEvaluators.decision(findings));
    }
    return decision;
  }

  private List<LiveAsset> liveAssets(final Turn turn, final Document document, final String text) {
    return new LiveAssetReader(authorizer, turn.securityContext())
        .read(ToolCallText.assets(text, document.knowledge()));
  }

  private static List<ContextItem> guardrailNotes(final Optional<GuardrailDecision> decision) {
    return decision
        .filter(value -> value.getAction() == GuardrailAction.WARN)
        .map(
            value ->
                List.of(
                    ContextItem.fixed(
                        Origin.DEFAULT,
                        ContextItem.GUARDRAILS,
                        "guardrails",
                        "## OpenMetadata guardrails\n\n- "
                            + String.join("\n- ", value.getReasons()))))
        .orElse(List.of());
  }

  private List<ContextItem> incidentNotes(final Turn turn, final Document document) {
    return liveAssets(turn, document, ToolCallText.of(turn.request().getToolOutput())).stream()
        .filter(asset -> asset.openIncidents() > 0 || asset.failedTests() > 0)
        .map(AgentHarnessCompiler::incidentNote)
        .toList();
  }

  private static ContextItem incidentNote(final LiveAsset asset) {
    final String fqn = asset.ref().fullyQualifiedName();
    return ContextItem.fixed(
        Origin.DEFAULT,
        ContextItem.INCIDENTS,
        "incidents:" + fqn,
        String.format(
            "Data quality: %s %s has %d open incident(s) and %d failing test(s). Say so when you"
                + " use its data.",
            asset.ref().entityType(), fqn, asset.openIncidents(), asset.failedTests()));
  }

  private static int budget(final Turn turn, final boolean opening) {
    final AgentHarnessEvent event = eventSettings(turn, turn.event());
    final int opened =
        opening && Boolean.TRUE.equals(eventSettings(turn, HookEvent.SESSION_START).getEnabled())
            ? eventSettings(turn, HookEvent.SESSION_START).getTokenBudget()
            : 0;
    final int configured =
        Math.min(event.getTokenBudget() + opened, turn.effective().getPerTurnTokenBudget());
    final Integer hostCap = turn.request().getMaxTokens();
    return Boolean.TRUE.equals(event.getEnabled())
        ? (hostCap == null ? configured : Math.min(configured, hostCap))
        : 0;
  }

  private static AgentHarnessEvent eventSettings(final Turn turn, final HookEvent event) {
    return HarnessEvents.find(turn.effective().getEvents(), event)
        .orElseGet(() -> new AgentHarnessEvent().withEnabled(false).withTokenBudget(0));
  }

  /**
   * True for the first prompt this server sees in a session. A request without a session id never
   * opens one, so it cannot repeat the SessionStart content on every prompt.
   */
  private static boolean opensSession(final Turn turn) {
    return turn.event() == HookEvent.USER_PROMPT_SUBMIT
        && Boolean.TRUE.equals(eventSettings(turn, HookEvent.SESSION_START).getEnabled())
        && sessionKey(turn).filter(key -> STARTED_SESSIONS.getIfPresent(key) == null).isPresent();
  }

  private static void markStarted(final Turn turn) {
    if (turn.event() == HookEvent.SESSION_START || turn.event() == HookEvent.USER_PROMPT_SUBMIT) {
      sessionKey(turn).ifPresent(key -> STARTED_SESSIONS.put(key, Boolean.TRUE));
    }
  }

  private static Set<String> sentKeys(final Turn turn) {
    return sessionKey(turn)
        .map(key -> SENT_PER_SESSION.get(key, ignored -> ConcurrentHashMap.newKeySet()))
        .orElse(Set.of());
  }

  private static void remember(final Turn turn, final Packed packed) {
    sessionKey(turn)
        .ifPresent(
            key ->
                SENT_PER_SESSION
                    .get(key, ignored -> ConcurrentHashMap.newKeySet())
                    .addAll(packed.items().stream().map(ContextItem::key).toList()));
  }

  private static Optional<String> sessionKey(final Turn turn) {
    final String sessionId = turn.request().getSessionId();
    return nullOrEmpty(sessionId)
        ? Optional.empty()
        : Optional.of(turn.securityContext().getUserPrincipal().getName() + '\n' + sessionId);
  }

  private static List<AgentHarnessToolAccess> toolAccessLayers(final Caller caller) {
    return HarnessResolver.toolAccessLayers(caller.defaultHarness(), caller.personaHarness());
  }

  private PersonaContext cachedDocument(
      final Persona persona, final SecurityContext securityContext) {
    final PersonaContextCache cache = PersonaContextCache.getInstance();
    return (PersonaContextAccess.isAdminOrBot(securityContext)
            ? cache.get(persona, false)
            : cache.getForCaller(persona, authorizer, securityContext))
        .value()
        .context();
  }

  private Caller caller(final SecurityContext securityContext) {
    return HarnessContexts.activePersona(securityContext)
        .map(this::callerAs)
        .orElseGet(() -> new Caller(harnesses.getDefault(), null, null, contexts.defaultStandIn()));
  }

  private Caller callerAs(final Persona persona) {
    final AgentHarness personaHarness =
        harnesses.findByPersona(persona.getId()).filter(HarnessResolver::applies).orElse(null);
    return new Caller(harnesses.getDefault(), personaHarness, persona, persona);
  }

  private Caller previewCaller(final AgentHarness harness, final AgentHarnessSpec spec) {
    final AgentHarness previewed = HarnessSpecs.asPublished(harness, spec);
    final Caller caller;
    if (harness.getKind() == AgentHarnessKind.DEFAULT) {
      caller = new Caller(previewed, null, null, contexts.defaultStandIn());
    } else {
      final Persona persona =
          Entity.getEntity(Entity.PERSONA, harness.getPersona().getId(), "", Include.ALL);
      caller = new Caller(harnesses.getDefault(), previewed, persona, persona);
    }
    return caller;
  }

  private static AppliedHarnesses applied(final Caller caller) {
    final AppliedHarnesses applied =
        new AppliedHarnesses()
            .withDefault(
                appliedHarness(caller.defaultHarness(), caller.defaultHarness().getDisplayName()));
    if (caller.personaHarness() != null) {
      applied.withPersona(appliedHarness(caller.personaHarness(), personaLabel(caller.persona())));
    }
    return applied;
  }

  private static String personaLabel(final Persona persona) {
    return persona == null || nullOrEmpty(persona.getDisplayName())
        ? persona == null ? null : persona.getName()
        : persona.getDisplayName();
  }

  private static AppliedHarness appliedHarness(final AgentHarness harness, final String label) {
    return new AppliedHarness()
        .withId(harness.getId())
        .withName(harness.getName())
        .withDisplayName(nullOrEmpty(label) ? harness.getName() : label)
        .withVersion(harness.getVersion());
  }
}
