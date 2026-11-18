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
import {
  CACHE_TTL_MINUTES_RANGE,
  CHARACTER_BUDGET_RANGE,
  DELIVERY_GROUP_OF_EVENT,
  DELIVERY_GROUP_ORDER,
  HOOK_EVENT_ORDER,
  PICKABLE_SOURCE_ENTITY_TYPE,
  PICKED_RULE_NAMES,
} from '../../../constants/discovery/agentHarness.constants';
import { EntityType } from '../../../enums/entity.enum';
import { AgentHarnessPersonaSummary } from '../../../generated/api/context/agentHarnessPersonaSummary';
import {
  AgentHarness,
  AgentHarnessSpec,
  AgentHarnessState,
  ContextRule,
  EventDelivery,
  HarnessKind,
  HookEvent,
  ResolvedAgentHarness,
  ResolvedRule,
} from '../../../generated/api/context/agentHarnessState';
import { Segment } from '../../../generated/api/context/compiledAgentContext';
import {
  BudgetError,
  DeliveryGroup,
  DeliveryRow,
  HarnessAdditions,
  HarnessBudgetErrors,
  HarnessSource,
  PickableSource,
  SourceCount,
} from '../../../interface/discovery/agentHarness.interface';

/** The editable fields of a published harness, as a draft would hold them. */
export const ownSpecOf = (harness: AgentHarness): AgentHarnessSpec => {
  const {
    companyInstructions,
    context,
    events,
    guardrails,
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

/** What a persona harness's own spec adds to the default. */
export const additionsSummary = (spec: AgentHarnessSpec): HarnessAdditions => {
  const counts = sourceCounts(ownRules(spec));

  return {
    articles: counts[HarnessSource.Articles].pickedIds.length,
    glossaryTerms: counts[HarnessSource.GlossaryTerms].pickedIds.length,
    guardrails: spec.guardrails?.length ?? 0,
    metrics: counts[HarnessSource.Metrics].pickedIds.length,
    rules: ownRules(spec).filter(({ picked }) => !picked).length,
    skills: (spec.skills ?? []).filter(({ enabled }) => enabled).length,
  };
};

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
