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
  AgentHarnessSpec,
  ContextRule,
  GuardrailLevel,
  HarnessKind,
  HookEvent,
  MemoryReview,
  MemoryVisibility,
  ResolvedAgentHarness,
} from '../../../generated/api/context/agentHarnessState';
import {
  DeliveryGroup,
  HarnessSource,
} from '../../../interface/discovery/agentHarness.interface';
import {
  additionsSummary,
  budgetErrors,
  contextBudgetPercent,
  deliveryGroups,
  deliveryRows,
  hasBudgetErrors,
  pickedIds,
  sourceCounts,
  visibleRules,
  withEventDelivery,
  withPickedIds,
} from './agentHarness.utils';

const DEFAULT_RULE: ContextRule = {
  entityType: 'table',
  id: 'default-rule',
  name: 'Certified tables',
};

const PERSONA_RULE: ContextRule = {
  entityType: 'dashboard',
  id: 'persona-rule',
  name: 'Sales dashboards',
};

const pickedRule = (entityType: string, ids: string[]): ContextRule => ({
  entityType,
  id: `picked-${entityType}`,
  name: `Picked ${entityType}`,
  picked: true,
  queryFilter: JSON.stringify({
    query: { bool: { must: [{ terms: { id: ids } }] } },
  }),
});

const personaResolved = (): ResolvedAgentHarness => ({
  cacheTtlMinutes: 30,
  characterBudget: 400000,
  events: [
    {
      enabled: true,
      id: HookEvent.SessionStart,
      inherited: true,
      maxTokenBudget: 6000,
      tokenBudget: 6000,
    },
    {
      enabled: true,
      id: HookEvent.UserPromptSubmit,
      inherited: true,
      maxTokenBudget: 4000,
      tokenBudget: 4000,
    },
    {
      enabled: true,
      id: HookEvent.PreToolUse,
      inherited: true,
      maxTokenBudget: 2000,
      tokenBudget: 2000,
    },
    {
      enabled: false,
      id: HookEvent.PostToolUse,
      inherited: false,
      maxTokenBudget: 1000,
      tokenBudget: 1000,
    },
    {
      enabled: false,
      id: HookEvent.Stop,
      inherited: false,
      maxTokenBudget: 1000,
      tokenBudget: 1000,
    },
  ],
  guardrails: [],
  kind: HarnessKind.Persona,
  memory: {
    capture: true,
    dedup: true,
    linkAssets: true,
    review: MemoryReview.StewardReview,
    visibility: MemoryVisibility.Private,
  },
  memoryInherited: true,
  perTurnTokenBudget: 20000,
  rules: [
    { inherited: true, rule: DEFAULT_RULE },
    { inherited: false, rule: { ...PERSONA_RULE, name: 'Saved name' } },
  ],
  skills: [],
});

const personaSpec = (): AgentHarnessSpec => ({
  context: {
    cacheTtlMinutes: 30,
    characterBudget: 400000,
    rules: [PERSONA_RULE],
  },
  perTurnTokenBudget: 20000,
});

describe('agentHarness.utils', () => {
  describe('visibleRules', () => {
    it('lists the default rules first, locked, then the local own rules', () => {
      expect(visibleRules(personaResolved(), personaSpec(), true)).toEqual([
        { inherited: true, rule: DEFAULT_RULE },
        { inherited: false, rule: PERSONA_RULE },
      ]);
    });

    it('hides the inherited rules when Show inherited is off', () => {
      expect(visibleRules(personaResolved(), personaSpec(), false)).toEqual([
        { inherited: false, rule: PERSONA_RULE },
      ]);
    });
  });

  describe('picked items', () => {
    it('reads the ids back from a picked rule and ignores filter rules', () => {
      expect(pickedIds(pickedRule('metric', ['m1', 'm2']))).toEqual([
        'm1',
        'm2',
      ]);
      expect(
        pickedIds({ ...DEFAULT_RULE, queryFilter: '{"query":{}}' })
      ).toEqual([]);
    });

    it('counts picks and rules per source card', () => {
      const counts = sourceCounts([
        pickedRule('glossaryTerm', ['t1', 't2', 't3']),
        pickedRule('page', ['a1']),
        { ...DEFAULT_RULE },
        { ...PERSONA_RULE },
        { entityType: 'glossaryTerm', id: 'g', name: 'Finance terms' },
      ]);

      expect(counts[HarnessSource.GlossaryTerms]).toEqual({
        pickedIds: ['t1', 't2', 't3'],
        ruleCount: 1,
      });
      expect(counts[HarnessSource.Articles]).toEqual({
        pickedIds: ['a1'],
        ruleCount: 0,
      });
      expect(counts[HarnessSource.Assets]).toEqual({
        pickedIds: [],
        ruleCount: 2,
      });
      expect(counts[HarnessSource.Metrics]).toEqual({
        pickedIds: [],
        ruleCount: 0,
      });
    });

    it('writes picks as one always-in-context rule with an id filter', () => {
      const spec = withPickedIds(
        personaSpec(),
        HarnessSource.Metrics,
        ['m1', 'm2'],
        () => 'new-id'
      );
      const rule = spec.context?.rules?.[1];

      expect(rule).toEqual({
        alwaysInContext: true,
        enabled: true,
        entityType: 'metric',
        filteredInSearch: false,
        fullyRendered: true,
        id: 'new-id',
        maxAssets: 2,
        name: 'Picked metrics',
        picked: true,
        queryFilter:
          '{"query":{"bool":{"must":[{"terms":{"id":["m1","m2"]}}]}}}',
      });
    });

    it('updates the same rule on a second pick and drops it when nothing is picked', () => {
      const once = withPickedIds(
        personaSpec(),
        HarnessSource.Metrics,
        ['m1'],
        () => 'first'
      );
      const twice = withPickedIds(
        once,
        HarnessSource.Metrics,
        ['m1', 'm3'],
        () => 'second'
      );
      const cleared = withPickedIds(
        twice,
        HarnessSource.Metrics,
        [],
        () => 'third'
      );

      expect(twice.context?.rules?.map(({ id }) => id)).toEqual([
        'persona-rule',
        'first',
      ]);
      expect(pickedIds(twice.context?.rules?.[1] as ContextRule)).toEqual([
        'm1',
        'm3',
      ]);
      expect(cleared.context?.rules).toEqual([PERSONA_RULE]);
    });
  });

  describe('delivery', () => {
    it('shows every event in order, inherited ones on, with the default budget', () => {
      const rows = deliveryRows(
        {
          ...personaSpec(),
          events: [{ enabled: true, id: HookEvent.Stop, tokenBudget: 500 }],
        },
        personaResolved()
      );

      expect(rows).toEqual([
        {
          enabled: true,
          id: HookEvent.SessionStart,
          inherited: true,
          maxTokenBudget: 6000,
          tokenBudget: 6000,
        },
        {
          enabled: true,
          id: HookEvent.UserPromptSubmit,
          inherited: true,
          maxTokenBudget: 4000,
          tokenBudget: 4000,
        },
        {
          enabled: true,
          id: HookEvent.PreToolUse,
          inherited: true,
          maxTokenBudget: 2000,
          tokenBudget: 2000,
        },
        {
          enabled: false,
          id: HookEvent.PostToolUse,
          inherited: false,
          maxTokenBudget: 1000,
          tokenBudget: 1000,
        },
        {
          enabled: true,
          id: HookEvent.Stop,
          inherited: false,
          maxTokenBudget: 1000,
          tokenBudget: 500,
        },
      ]);
    });

    it('keeps only the events a persona changes', () => {
      const rows = deliveryRows(personaSpec(), personaResolved());
      const lowered = withEventDelivery(personaSpec(), HarnessKind.Persona, {
        ...rows[0],
        tokenBudget: 3000,
      });
      const restored = withEventDelivery(lowered, HarnessKind.Persona, {
        ...rows[0],
        tokenBudget: 6000,
      });

      expect(lowered.events).toEqual([
        { enabled: true, id: HookEvent.SessionStart, tokenBudget: 3000 },
      ]);
      expect(restored.events).toEqual([]);
    });

    it('keeps every event on the default, in delivery order', () => {
      const spec = withEventDelivery(
        {
          events: [{ enabled: true, id: HookEvent.Stop, tokenBudget: 1000 }],
          perTurnTokenBudget: 20000,
        },
        HarnessKind.Default,
        {
          enabled: false,
          id: HookEvent.SessionStart,
          inherited: false,
          tokenBudget: 6000,
        }
      );

      expect(spec.events).toEqual([
        { enabled: false, id: HookEvent.SessionStart, tokenBudget: 6000 },
        { enabled: true, id: HookEvent.Stop, tokenBudget: 1000 },
      ]);
    });

    it('groups enabled events into the list chips', () => {
      expect(
        deliveryGroups([
          HookEvent.Stop,
          HookEvent.PostToolUse,
          HookEvent.SessionStart,
          HookEvent.PreToolUse,
        ])
      ).toEqual([
        DeliveryGroup.Session,
        DeliveryGroup.ToolUse,
        DeliveryGroup.SessionEnd,
      ]);
    });
  });

  describe('budgetErrors', () => {
    it('accepts a per-turn budget equal to the enabled events', () => {
      const spec = { ...personaSpec(), perTurnTokenBudget: 12000 };
      const errors = budgetErrors(spec, deliveryRows(spec, personaResolved()));

      expect(errors).toEqual({
        cacheTtlMinutes: undefined,
        characterBudget: undefined,
        events: {},
        perTurnTokenBudget: undefined,
      });
      expect(hasBudgetErrors(errors)).toBe(false);
    });

    it('flags a per-turn budget below the enabled events and an event above the default', () => {
      const spec: AgentHarnessSpec = {
        ...personaSpec(),
        events: [
          { enabled: true, id: HookEvent.SessionStart, tokenBudget: 7000 },
        ],
        perTurnTokenBudget: 12000,
      };
      const errors = budgetErrors(spec, deliveryRows(spec, personaResolved()));

      expect(errors.perTurnTokenBudget).toBe('belowEvents');
      expect(errors.events).toEqual({
        [HookEvent.SessionStart]: 'aboveDefault',
      });
      expect(hasBudgetErrors(errors)).toBe(true);
    });

    it('rejects non-integers and values outside the server range', () => {
      const spec: AgentHarnessSpec = {
        context: { cacheTtlMinutes: 0, characterBudget: 5000 },
        perTurnTokenBudget: 1.5,
      };
      const errors = budgetErrors(spec, []);

      expect(errors.cacheTtlMinutes).toBe('notPositive');
      expect(errors.characterBudget).toBe('outOfRange');
      expect(errors.perTurnTokenBudget).toBe('notPositive');
    });
  });

  it('summarises what a persona adds to the default', () => {
    expect(
      additionsSummary({
        context: {
          rules: [
            pickedRule('glossaryTerm', ['t1', 't2']),
            pickedRule('metric', ['m1']),
            PERSONA_RULE,
          ],
        },
        guardrails: [{ id: 'cert', level: GuardrailLevel.Block }],
        skills: [
          { enabled: true, name: 'explain-metric' },
          { enabled: false, name: 'write-sql' },
        ],
      })
    ).toEqual({
      articles: 0,
      glossaryTerms: 2,
      guardrails: 1,
      metrics: 1,
      rules: 1,
      skills: 1,
    });
  });

  it('reports the share of the character budget, or nothing before the first compile', () => {
    expect(
      contextBudgetPercent({
        characterBudget: 400000,
        charactersUsed: 248000,
        lastGeneratedAt: 1,
      })
    ).toBe(62);
    expect(
      contextBudgetPercent({ characterBudget: 400000, charactersUsed: 0 })
    ).toBeUndefined();
  });
});
