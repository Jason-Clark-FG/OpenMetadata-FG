/*
 *  Copyright 2026 Collate.
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
import { TFunction } from 'i18next';
import {
  CACHE_TTL_MINUTES_RANGE,
  CHARACTER_BUDGET_RANGE,
  DELIVERY_GROUP_OF_EVENT,
  DELIVERY_GROUP_ORDER,
  GUARDRAIL_LEVEL_ORDER,
  HOOK_EVENT_ORDER,
  PICKABLE_SOURCE_ENTITY_TYPE,
  PICKED_RULE_NAMES,
  PLUGIN_DIR,
  PLUGIN_ZIP_NAME,
  TOOL_ACCESS_LEVEL_ORDER,
} from '../../../constants/discovery/agentHarness.constants';
import { EntityType } from '../../../enums/entity.enum';
import { AgentHarnessManagedSettings } from '../../../generated/api/context/agentHarnessManagedSettings';
import { AgentHarnessPersonaSummary } from '../../../generated/api/context/agentHarnessPersonaSummary';
import {
  AgentHarness,
  AgentHarnessSpec,
  AgentHarnessState,
  ContextRule,
  EventDelivery,
  GuardrailLevel,
  HarnessKind,
  HookEvent,
  MemoryReview,
  MemorySettings,
  MemoryVisibility,
  ResolvedAgentHarness,
  ResolvedRule,
  ToolAccess,
  ToolAccessDefault,
  ToolAccessLevel,
  ToolAccessServer,
} from '../../../generated/api/context/agentHarnessState';
import {
  Surface,
  SurfaceID,
  SurfaceStatus,
} from '../../../generated/api/context/agentHarnessSurfaces';
import { Segment } from '../../../generated/api/context/compiledAgentContext';
import { GuardrailDefinition } from '../../../generated/api/context/guardrailDefinition';
import { SkillDefinition } from '../../../generated/api/context/skillDefinition';
import {
  BudgetError,
  DeliveryGroup,
  DeliveryRow,
  GuardrailRow,
  HarnessBudgetErrors,
  HarnessSource,
  PickableSource,
  SkillRow,
  SourceCount,
  ToolAccessErrors,
} from '../../../interface/discovery/agentHarness.interface';

/** The editable fields of a published harness, as a draft would hold them. */
export const ownSpecOf = (harness: AgentHarness): AgentHarnessSpec => {
  const {
    companyInstructions,
    context,
    events,
    guardrails,
    knowledgeGraph,
    memory,
    perTurnTokenBudget,
    skills,
    toolAccess,
  } = harness;
  const editableContext = context && {
    cacheTtlMinutes: context.cacheTtlMinutes,
    characterBudget: context.characterBudget,
    prompt: context.prompt,
    rules: context.rules,
  };

  return {
    companyInstructions,
    context: editableContext,
    events,
    guardrails,
    knowledgeGraph,
    memory,
    perTurnTokenBudget,
    skills,
    toolAccess,
  };
};

/** What the editor starts from: the draft when there is one, else the published settings. */
export const editableSpec = (state: AgentHarnessState): AgentHarnessSpec =>
  state.draft ?? ownSpecOf(state.published);

export const sourceOfEntityType = (entityType: string): HarnessSource => {
  switch (entityType) {
    case EntityType.GLOSSARY_TERM:
      return HarnessSource.GlossaryTerms;
    case EntityType.METRIC:
      return HarnessSource.Metrics;
    case EntityType.KNOWLEDGE_PAGE:
      return HarnessSource.Articles;
    default:
      return HarnessSource.Assets;
  }
};

const findIdTerms = (node: unknown): string[] | undefined => {
  if (!node || typeof node !== 'object') {
    return undefined;
  }
  const record = node as Record<string, unknown>;
  const terms = record.terms as Record<string, unknown> | undefined;
  if (terms && Array.isArray(terms.id)) {
    return terms.id.filter((id): id is string => typeof id === 'string');
  }
  for (const child of Object.values(record)) {
    const found = findIdTerms(child);
    if (found) {
      return found;
    }
  }

  return undefined;
};

/** The entity ids a picked rule selects, read back from its id filter. */
export const pickedIds = (rule: ContextRule): string[] => {
  if (!rule.picked || !rule.queryFilter) {
    return [];
  }
  try {
    return findIdTerms(JSON.parse(rule.queryFilter)) ?? [];
  } catch {
    return [];
  }
};

export const pickedQueryFilter = (ids: string[]): string =>
  JSON.stringify({ query: { bool: { must: [{ terms: { id: ids } }] } } });

export const emptySourceCounts = (): Record<HarnessSource, SourceCount> => ({
  [HarnessSource.GlossaryTerms]: { pickedIds: [], ruleCount: 0 },
  [HarnessSource.Metrics]: { pickedIds: [], ruleCount: 0 },
  [HarnessSource.Assets]: { pickedIds: [], ruleCount: 0 },
  [HarnessSource.Articles]: { pickedIds: [], ruleCount: 0 },
});

/** Picked items and filter rules per source card. Every rule lands on one card. */
export const sourceCounts = (
  rules: ContextRule[]
): Record<HarnessSource, SourceCount> =>
  rules.reduce((counts, rule) => {
    const source = sourceOfEntityType(rule.entityType);
    const current = counts[source];
    counts[source] = rule.picked
      ? { ...current, pickedIds: [...current.pickedIds, ...pickedIds(rule)] }
      : { ...current, ruleCount: current.ruleCount + 1 };

    return counts;
  }, emptySourceCounts());

export const ownRules = (spec: AgentHarnessSpec): ContextRule[] =>
  spec.context?.rules ?? [];

/** The default's rules, read-only on a persona harness. */
export const inheritedRules = (resolved: ResolvedAgentHarness): ContextRule[] =>
  resolved.rules.filter(({ inherited }) => inherited).map(({ rule }) => rule);

/**
 * Rules in compile order for display: the default's (when shown), then this harness's own from
 * the local spec, so unsaved edits show at once and inherited ones can never be edited.
 */
export const visibleRules = (
  resolved: ResolvedAgentHarness,
  spec: AgentHarnessSpec,
  showInherited: boolean
): ResolvedRule[] => [
  ...(showInherited
    ? inheritedRules(resolved).map((rule) => ({ inherited: true, rule }))
    : []),
  ...ownRules(spec).map((rule) => ({ inherited: false, rule })),
];

const withRules = (
  spec: AgentHarnessSpec,
  rules: ContextRule[]
): AgentHarnessSpec => ({ ...spec, context: { ...spec.context, rules } });

/** Adds a rule, or replaces the one with the same id. */
export const withRule = (
  spec: AgentHarnessSpec,
  rule: ContextRule
): AgentHarnessSpec => {
  const rules = ownRules(spec);
  const exists = rules.some(({ id }) => id === rule.id);

  return withRules(
    spec,
    exists
      ? rules.map((current) => (current.id === rule.id ? rule : current))
      : [...rules, rule]
  );
};

export const withoutRule = (
  spec: AgentHarnessSpec,
  ruleId?: string
): AgentHarnessSpec =>
  withRules(
    spec,
    ownRules(spec).filter(({ id }) => id !== ruleId)
  );

/**
 * Replaces a source's picked items. Picks live in one ordinary rule per source with an id
 * filter, kept in every compiled context; no picks removes the rule.
 */
export const withPickedIds = (
  spec: AgentHarnessSpec,
  source: PickableSource,
  ids: string[],
  newRuleId: () => string
): AgentHarnessSpec => {
  const entityType = PICKABLE_SOURCE_ENTITY_TYPE[source];
  const existing = ownRules(spec).find(
    (rule) => rule.picked && rule.entityType === entityType
  );
  if (ids.length === 0) {
    return existing ? withoutRule(spec, existing.id) : spec;
  }

  return withRule(spec, {
    alwaysInContext: true,
    enabled: true,
    entityType,
    filteredInSearch: false,
    fullyRendered: true,
    id: existing?.id ?? newRuleId(),
    maxAssets: ids.length,
    name: PICKED_RULE_NAMES[source],
    picked: true,
    queryFilter: pickedQueryFilter(ids),
  });
};

/** Every event in delivery order as it applies to this harness, with local edits. */
export const deliveryRows = (
  spec: AgentHarnessSpec,
  resolved: ResolvedAgentHarness
): DeliveryRow[] =>
  HOOK_EVENT_ORDER.map((id) => {
    const base = resolved.events.find((event) => event.id === id);
    const own = spec.events?.find((event) => event.id === id);
    const inherited = base?.inherited ?? false;
    const maxTokenBudget = base?.maxTokenBudget;

    return {
      id,
      enabled: inherited || (own?.enabled ?? base?.enabled ?? false),
      inherited,
      maxTokenBudget,
      tokenBudget: own?.tokenBudget ?? maxTokenBudget ?? base?.tokenBudget ?? 0,
    };
  });

/**
 * Sets one event's delivery. A persona harness lists only the events it changes, so a row set
 * back to what the default gives is dropped from its spec.
 */
export const withEventDelivery = (
  spec: AgentHarnessSpec,
  kind: HarnessKind,
  row: DeliveryRow
): AgentHarnessSpec => {
  const others = (spec.events ?? []).filter(({ id }) => id !== row.id);
  const entry: EventDelivery = {
    enabled: row.enabled,
    id: row.id,
    tokenBudget: row.tokenBudget,
  };
  const matchesDefault =
    kind === HarnessKind.Persona &&
    row.enabled === row.inherited &&
    row.tokenBudget === row.maxTokenBudget;
  const events = matchesDefault ? others : [...others, entry];

  return {
    ...spec,
    events: events.sort(
      (a, b) => HOOK_EVENT_ORDER.indexOf(a.id) - HOOK_EVENT_ORDER.indexOf(b.id)
    ),
  };
};

const isPositiveInteger = (value?: number): value is number =>
  value !== undefined && Number.isInteger(value) && value > 0;

const rangeError = (
  value: number | undefined,
  range: { min: number; max: number }
): BudgetError | undefined => {
  if (!isPositiveInteger(value)) {
    return 'notPositive';
  }

  return value < range.min || value > range.max ? 'outOfRange' : undefined;
};

export const enabledEventTokens = (rows: DeliveryRow[]): number =>
  rows
    .filter(({ enabled }) => enabled)
    .reduce((total, { tokenBudget }) => total + tokenBudget, 0);

/** The checks the server makes on a draft, so the editor can show them inline before saving. */
export const budgetErrors = (
  spec: AgentHarnessSpec,
  rows: DeliveryRow[]
): HarnessBudgetErrors => {
  const events: HarnessBudgetErrors['events'] = {};
  const written = new Set(spec.events?.map(({ id }) => id));
  rows.forEach(({ id, enabled, tokenBudget, maxTokenBudget }) => {
    if (!enabled && !written.has(id)) {
      return;
    }
    if (!isPositiveInteger(tokenBudget)) {
      events[id] = 'notPositive';
    } else if (maxTokenBudget !== undefined && tokenBudget > maxTokenBudget) {
      events[id] = 'aboveDefault';
    }
  });
  const perTurn = spec.perTurnTokenBudget;
  let perTurnTokenBudget: BudgetError | undefined;
  if (!isPositiveInteger(perTurn)) {
    perTurnTokenBudget = 'notPositive';
  } else if (perTurn < enabledEventTokens(rows)) {
    perTurnTokenBudget = 'belowEvents';
  }

  return {
    cacheTtlMinutes: rangeError(
      spec.context?.cacheTtlMinutes,
      CACHE_TTL_MINUTES_RANGE
    ),
    characterBudget: rangeError(
      spec.context?.characterBudget,
      CHARACTER_BUDGET_RANGE
    ),
    events,
    perTurnTokenBudget,
  };
};

export const hasBudgetErrors = (errors: HarnessBudgetErrors): boolean =>
  Boolean(
    errors.cacheTtlMinutes ||
      errors.characterBudget ||
      errors.perTurnTokenBudget ||
      Object.keys(errors.events).length
  );

export const deliveryGroups = (events: HookEvent[]): DeliveryGroup[] => {
  const groups = new Set(events.map((event) => DELIVERY_GROUP_OF_EVENT[event]));

  return DELIVERY_GROUP_ORDER.filter((group) => groups.has(group));
};

/** Share of the character budget the cached document uses, or undefined when never compiled. */
export const contextBudgetPercent = (
  summary: Pick<
    AgentHarnessPersonaSummary,
    'characterBudget' | 'charactersUsed' | 'lastGeneratedAt'
  >
): number | undefined => {
  if (!summary.lastGeneratedAt || summary.characterBudget <= 0) {
    return undefined;
  }

  return Math.min(
    100,
    Math.round((summary.charactersUsed * 100) / summary.characterBudget)
  );
};

/** One part per kind, in the order each kind first appears; the compiler may emit a kind more than once. */
export const segmentsByKind = (segments: Segment[]): Segment[] => {
  const tokensByKind = new Map<string, number>();
  segments.forEach(({ kind, tokens }) =>
    tokensByKind.set(kind, (tokensByKind.get(kind) ?? 0) + tokens)
  );

  return Array.from(tokensByKind, ([kind, tokens]) => ({ kind, tokens }));
};

/**
 * The skills a harness offers, with local edits. The default lists the skills it turns on or
 * names; a persona lists the default's, locked on, and the ones it adds.
 */
export const skillRows = (
  spec: AgentHarnessSpec,
  resolved: ResolvedAgentHarness,
  registry: SkillDefinition[]
): SkillRow[] =>
  registry.flatMap(({ name, description, defaultEnabled }) => {
    const own = spec.skills?.find((skill) => skill.name === name);
    const isPersona = resolved.kind === HarnessKind.Persona;
    const inherited =
      isPersona &&
      Boolean(resolved.skills.find((skill) => skill.name === name)?.inherited);
    const enabled =
      inherited || (own?.enabled ?? (!isPersona && defaultEnabled));

    return inherited || own || enabled
      ? [{ description, enabled, inherited, name }]
      : [];
  });

export const addableSkills = (
  rows: SkillRow[],
  registry: SkillDefinition[]
): SkillDefinition[] =>
  registry.filter(({ name }) => !rows.some((row) => row.name === name));

export const withSkill = (
  spec: AgentHarnessSpec,
  name: string,
  enabled: boolean
): AgentHarnessSpec => ({
  ...spec,
  skills: [
    ...(spec.skills ?? []).filter((skill) => skill.name !== name),
    { enabled, name },
  ],
});

const guardrailRank = (level: GuardrailLevel) =>
  GUARDRAIL_LEVEL_ORDER.indexOf(level);

/** The levels a guardrail may take: on a persona harness, the default's level and above. */
export const allowedLevels = (minLevel?: GuardrailLevel): GuardrailLevel[] =>
  GUARDRAIL_LEVEL_ORDER.filter(
    (level) => !minLevel || guardrailRank(level) >= guardrailRank(minLevel)
  );

/** Every registered guardrail at the level it has on this harness, with local edits. */
export const guardrailRows = (
  spec: AgentHarnessSpec,
  resolved: ResolvedAgentHarness,
  registry: GuardrailDefinition[]
): GuardrailRow[] =>
  registry.map(({ id, name, description, available, defaultLevel }) => {
    const own = spec.guardrails?.find((guardrail) => guardrail.id === id);
    const minLevel =
      resolved.kind === HarnessKind.Persona
        ? resolved.guardrails.find((guardrail) => guardrail.id === id)?.minLevel
        : undefined;
    const seed =
      GUARDRAIL_LEVEL_ORDER.find((level) => level === String(defaultLevel)) ??
      GuardrailLevel.Off;
    const base = own?.level ?? minLevel ?? seed;
    const level =
      minLevel && guardrailRank(minLevel) > guardrailRank(base)
        ? minLevel
        : base;

    return { available, description, id, level, minLevel, name };
  });

export const withGuardrailLevel = (
  spec: AgentHarnessSpec,
  id: string,
  level: GuardrailLevel
): AgentHarnessSpec => ({
  ...spec,
  guardrails: [
    ...(spec.guardrails ?? []).filter((guardrail) => guardrail.id !== id),
    { id, level },
  ],
});

const accessRank = (level: ToolAccessLevel) =>
  TOOL_ACCESS_LEVEL_ORDER.indexOf(level);

const stricterAccess = (
  first: ToolAccessLevel,
  second: ToolAccessLevel
): ToolAccessLevel =>
  accessRank(first) >= accessRank(second) ? first : second;

export const defaultAccessLevel = (value?: ToolAccessDefault) =>
  value === ToolAccessDefault.Deny
    ? ToolAccessLevel.Block
    : ToolAccessLevel.Allow;

const serverMatches = (
  entry: ToolAccessServer,
  server: string,
  tool?: string
): boolean => {
  const tools = entry.tools ?? [];

  return (
    entry.match.trim().toLowerCase() === server.toLowerCase() &&
    (tools.length === 0 || (tool !== undefined && tools.includes(tool)))
  );
};

/**
 * One harness's decision on an MCP call: the first matching server entry, else the harness's
 * default. Mirrors the server's `ToolAccessPolicy.decide`.
 */
export const toolAccessDecision = (
  access: ToolAccess | undefined,
  server: string,
  tool?: string
): ToolAccessLevel =>
  access
    ? access.servers?.find((entry) => serverMatches(entry, server, tool))
        ?.level ?? defaultAccessLevel(access.default)
    : ToolAccessLevel.Allow;

/** The lowest level a persona entry may set: the default's strictest decision on the calls it names. */
export const minServerLevel = (
  inherited: ToolAccess | undefined,
  entry: ToolAccessServer
): ToolAccessLevel => {
  const tools: (string | undefined)[] = entry.tools?.length
    ? entry.tools
    : [undefined];

  return tools
    .map((tool) => toolAccessDecision(inherited, entry.match.trim(), tool))
    .reduce(stricterAccess, ToolAccessLevel.Allow);
};

export const allowedAccessLevels = (
  minLevel: ToolAccessLevel
): ToolAccessLevel[] =>
  TOOL_ACCESS_LEVEL_ORDER.filter(
    (level) => accessRank(level) >= accessRank(minLevel)
  );

/**
 * The checks the server makes on `toolAccess`. `inherited` is the default's access, given only
 * for a persona harness, which may tighten it and never loosen it.
 */
export const toolAccessErrors = (
  access: ToolAccess | undefined,
  inherited?: ToolAccess
): ToolAccessErrors => {
  const errors: ToolAccessErrors = { servers: {} };
  if (!access) {
    return errors;
  }
  if (
    inherited &&
    accessRank(defaultAccessLevel(access.default)) <
      accessRank(defaultAccessLevel(inherited.default))
  ) {
    errors.default = 'belowDefault';
  }
  const listed = new Set<string>();
  (access.servers ?? []).forEach((entry, index) => {
    const match = entry.match.trim().toLowerCase();
    const key = `${match}|${(entry.tools ?? []).join(',')}`;
    if (!match) {
      errors.servers[index] = 'matchRequired';
    } else if (listed.has(key)) {
      errors.servers[index] = 'duplicate';
    } else if (
      inherited &&
      accessRank(entry.level) < accessRank(minServerLevel(inherited, entry))
    ) {
      errors.servers[index] = 'belowDefault';
    }
    listed.add(key);
  });

  return errors;
};

export const hasToolAccessErrors = (errors: ToolAccessErrors): boolean =>
  Boolean(errors.default || Object.keys(errors.servers).length);

/** `toolAccessErrors` for an editor's local spec, against the default's access on a persona. */
export const specToolAccessErrors = (
  spec: AgentHarnessSpec,
  resolved: ResolvedAgentHarness
): ToolAccessErrors =>
  toolAccessErrors(
    spec.toolAccess,
    resolved.kind === HarnessKind.Persona
      ? resolved.inheritedToolAccess
      : undefined
  );

/**
 * Sets a harness's MCP access. Access that only repeats what it inherits (no servers and the
 * same default) is dropped, so a persona harness lists access only when it tightens it.
 */
export const withToolAccess = (
  spec: AgentHarnessSpec,
  access: ToolAccess,
  inherited?: ToolAccess
): AgentHarnessSpec => {
  const repeatsInherited =
    !access.servers?.length &&
    defaultAccessLevel(access.default) ===
      defaultAccessLevel(inherited?.default);

  return { ...spec, toolAccess: repeatsInherited ? undefined : access };
};

/** Author-only memories stay with their author, so their visibility is always Private. */
export const withMemory = (
  spec: AgentHarnessSpec,
  memory: MemorySettings
): AgentHarnessSpec => ({
  ...spec,
  memory:
    memory.review === MemoryReview.AuthorOnly
      ? { ...memory, visibility: MemoryVisibility.Private }
      : memory,
});

export const surfacesCellText = (
  surfaces: Surface[] | undefined,
  t: TFunction
): { value: string; sub: string } => {
  if (!surfaces) {
    return { sub: '', value: '—' };
  }
  const notConnected = surfaces.find(
    ({ status }) => status !== SurfaceStatus.Connected
  );

  return {
    sub: notConnected
      ? t('message.agent-harness-surface-not-connected', {
          name: notConnected.name,
        })
      : t('message.agent-harness-all-surfaces-connected'),
    value: t('message.agent-harness-surfaces-connected', {
      connected: surfaces.filter(
        ({ status }) => status === SurfaceStatus.Connected
      ).length,
      total: surfaces.length,
    }),
  };
};

/** The shell command that installs the plugin on a CLI host; undefined for the other surfaces. */
export const getInstallCommand = (
  surface: SurfaceID,
  {
    serverName,
    serverUrl,
  }: Pick<AgentHarnessManagedSettings, 'serverName' | 'serverUrl'>
) => {
  switch (surface) {
    case SurfaceID.ClaudeCode:
      return `unzip ${PLUGIN_ZIP_NAME} -d ${PLUGIN_DIR}\nclaude --plugin-dir ./${PLUGIN_DIR}`;
    case SurfaceID.Codex:
      return `codex mcp add ${serverName} --url ${serverUrl}\ncodex mcp login ${serverName}`;
    default:
      return undefined;
  }
};
