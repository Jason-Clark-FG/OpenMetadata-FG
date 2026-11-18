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
  ToolAccess,
  ToolAccessDefault,
  ToolAccessLevel,
} from '../../../generated/api/context/agentHarnessState';
import {
  SurfaceID,
  SurfaceStatus,
} from '../../../generated/api/context/agentHarnessSurfaces';
import { GuardrailDefinition } from '../../../generated/api/context/guardrailDefinition';
import { SkillDefinition } from '../../../generated/api/context/skillDefinition';
import {
  DeliveryGroup,
  HarnessSource,
} from '../../../interface/discovery/agentHarness.interface';
import {
  addableSkills,
  allowedLevels,
  budgetErrors,
  contextBudgetPercent,
  deliveryGroups,
  deliveryRows,
  guardrailRows,
  hasBudgetErrors,
  minServerLevel,
  pickedIds,
  segmentsByKind,
  skillRows,
  sourceCounts,
  surfacesCellText,
  toolAccessDecision,
  toolAccessErrors,
  visibleRules,
  withEventDelivery,
  withMemory,
  withPickedIds,
  withSkill,
  withToolAccess,
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

    it('checks the budget of an event only when it is on or written', () => {
      const rows = deliveryRows(personaSpec(), personaResolved()).map(
        (row) => ({ ...row, tokenBudget: 0 })
      );
      const spec: AgentHarnessSpec = {
        ...personaSpec(),
        events: [{ enabled: false, id: HookEvent.Stop, tokenBudget: 0 }],
      };

      expect(budgetErrors(spec, rows).events).toEqual({
        [HookEvent.SessionStart]: 'notPositive',
        [HookEvent.UserPromptSubmit]: 'notPositive',
        [HookEvent.PreToolUse]: 'notPositive',
        [HookEvent.Stop]: 'notPositive',
      });
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

  it('sums the compiled parts of one kind into a single part', () => {
    expect(
      segmentsByKind([
        { kind: 'Instructions', tokens: 1200 },
        { kind: 'Assets', tokens: 300 },
        { kind: 'Glossary', tokens: 3800 },
        { kind: 'Assets', tokens: 450 },
      ])
    ).toEqual([
      { kind: 'Instructions', tokens: 1200 },
      { kind: 'Assets', tokens: 750 },
      { kind: 'Glossary', tokens: 3800 },
    ]);
  });
});

const SKILLS = [
  {
    defaultEnabled: true,
    description: 'Look up context first',
    name: 'find-context',
  },
  {
    defaultEnabled: false,
    description: 'Explain a metric',
    name: 'metric-definition',
  },
  { defaultEnabled: false, description: 'Trace lineage', name: 'lineage' },
] as SkillDefinition[];

const GUARDRAILS = [
  {
    available: true,
    defaultLevel: 'Block',
    description: '',
    event: 'PreToolUse',
    id: 'block-deprecated',
    name: 'Block deprecated assets',
  },
  {
    available: true,
    defaultLevel: 'Warn',
    description: '',
    event: 'PreToolUse',
    id: 'flag-incidents',
    name: 'Flag open incidents',
  },
  {
    available: false,
    defaultLevel: 'Off',
    description: '',
    event: 'PreToolUse',
    id: 'query-cost',
    name: 'Query cost ceiling',
  },
] as GuardrailDefinition[];

const defaultResolved = (): ResolvedAgentHarness => ({
  ...personaResolved(),
  guardrails: [],
  kind: HarnessKind.Default,
  skills: [],
});

describe('agentHarness.utils for instructions, guardrails, MCP access and memory', () => {
  describe('skillRows', () => {
    it('lists on the default the skills it turns on or names', () => {
      const rows = skillRows(
        { skills: [{ enabled: false, name: 'lineage' }] },
        defaultResolved(),
        SKILLS
      );

      expect(rows).toEqual([
        {
          description: 'Look up context first',
          enabled: true,
          inherited: false,
          name: 'find-context',
        },
        {
          description: 'Trace lineage',
          enabled: false,
          inherited: false,
          name: 'lineage',
        },
      ]);
      expect(addableSkills(rows, SKILLS).map(({ name }) => name)).toEqual([
        'metric-definition',
      ]);
    });

    it('locks the default skills on a persona and lists the ones it adds', () => {
      const resolved = {
        ...personaResolved(),
        skills: [
          { enabled: true, inherited: true, name: 'find-context' },
          { enabled: false, inherited: false, name: 'metric-definition' },
        ],
      };
      const spec = withSkill({}, 'metric-definition', true);

      expect(skillRows(spec, resolved, SKILLS)).toEqual([
        {
          description: 'Look up context first',
          enabled: true,
          inherited: true,
          name: 'find-context',
        },
        {
          description: 'Explain a metric',
          enabled: true,
          inherited: false,
          name: 'metric-definition',
        },
      ]);
    });
  });

  describe('guardrails', () => {
    it('allows only the default level and stricter on a persona', () => {
      expect(allowedLevels(GuardrailLevel.Warn)).toEqual([
        GuardrailLevel.Warn,
        GuardrailLevel.Block,
      ]);
      expect(allowedLevels()).toEqual([
        GuardrailLevel.Off,
        GuardrailLevel.Warn,
        GuardrailLevel.Block,
      ]);
    });

    it('seeds the default from the registry and keeps local levels', () => {
      const rows = guardrailRows(
        { guardrails: [{ id: 'flag-incidents', level: GuardrailLevel.Off }] },
        defaultResolved(),
        GUARDRAILS
      );

      expect(
        rows.map(({ id, level, minLevel }) => [id, level, minLevel])
      ).toEqual([
        ['block-deprecated', GuardrailLevel.Block, undefined],
        ['flag-incidents', GuardrailLevel.Off, undefined],
        ['query-cost', GuardrailLevel.Off, undefined],
      ]);
      expect(rows[2].available).toBe(false);
    });

    it('raises a persona level that the default has since made stricter', () => {
      const resolved = {
        ...personaResolved(),
        guardrails: [
          {
            id: 'flag-incidents',
            level: GuardrailLevel.Block,
            minLevel: GuardrailLevel.Block,
          },
        ],
      };
      const [, incidents] = guardrailRows(
        { guardrails: [{ id: 'flag-incidents', level: GuardrailLevel.Warn }] },
        resolved,
        GUARDRAILS
      );

      expect(incidents.level).toBe(GuardrailLevel.Block);
      expect(incidents.minLevel).toBe(GuardrailLevel.Block);
    });
  });

  describe('MCP access', () => {
    const inherited: ToolAccess = {
      default: ToolAccessDefault.Allow,
      servers: [
        { level: ToolAccessLevel.Warn, match: 'github', tools: ['push'] },
        { level: ToolAccessLevel.Block, match: 'GitHub' },
      ],
    };

    it('decides by the first matching server, then the default', () => {
      expect(toolAccessDecision(inherited, 'github', 'push')).toBe(
        ToolAccessLevel.Warn
      );
      expect(toolAccessDecision(inherited, 'github', 'read')).toBe(
        ToolAccessLevel.Block
      );
      expect(toolAccessDecision(inherited, 'slack')).toBe(
        ToolAccessLevel.Allow
      );
      expect(
        toolAccessDecision({ default: ToolAccessDefault.Deny }, 'slack')
      ).toBe(ToolAccessLevel.Block);
      expect(toolAccessDecision(undefined, 'slack')).toBe(
        ToolAccessLevel.Allow
      );
    });

    it('floors a persona entry at the strictest call it names', () => {
      expect(
        minServerLevel(inherited, {
          level: ToolAccessLevel.Allow,
          match: ' github ',
          tools: ['push'],
        })
      ).toBe(ToolAccessLevel.Warn);
      expect(
        minServerLevel(inherited, {
          level: ToolAccessLevel.Allow,
          match: 'github',
        })
      ).toBe(ToolAccessLevel.Block);
    });

    it('reports blank, repeated and looser entries by index', () => {
      expect(
        toolAccessErrors(
          {
            default: ToolAccessDefault.Allow,
            servers: [
              { level: ToolAccessLevel.Block, match: ' ' },
              { level: ToolAccessLevel.Block, match: 'jira' },
              { level: ToolAccessLevel.Warn, match: 'Jira' },
              { level: ToolAccessLevel.Warn, match: 'github' },
            ],
          },
          inherited
        )
      ).toEqual({
        servers: { 0: 'matchRequired', 2: 'duplicate', 3: 'belowDefault' },
      });
      expect(
        toolAccessErrors(
          { default: ToolAccessDefault.Allow },
          { default: ToolAccessDefault.Deny }
        )
      ).toEqual({ default: 'belowDefault', servers: {} });
      expect(
        toolAccessErrors({
          default: ToolAccessDefault.Allow,
          servers: [{ level: ToolAccessLevel.Allow, match: 'github' }],
        })
      ).toEqual({ servers: {} });
    });

    it('drops access that only repeats what the harness inherits', () => {
      expect(
        withToolAccess(
          {},
          { default: ToolAccessDefault.Deny, servers: [] },
          { default: ToolAccessDefault.Deny }
        ).toolAccess
      ).toBeUndefined();
      expect(
        withToolAccess({}, { default: ToolAccessDefault.Deny, servers: [] })
          .toolAccess
      ).toEqual({ default: ToolAccessDefault.Deny, servers: [] });
    });
  });

  describe('withMemory', () => {
    it('forces Private visibility when review is Author only', () => {
      expect(
        withMemory(
          {},
          {
            capture: true,
            dedup: false,
            linkAssets: true,
            review: MemoryReview.AuthorOnly,
            visibility: MemoryVisibility.Organization,
          }
        ).memory?.visibility
      ).toBe(MemoryVisibility.Private);
    });
  });

  describe('surfacesCellText', () => {
    const t = ((key: string, params?: Record<string, unknown>) =>
      params ? `${key} ${JSON.stringify(params)}` : key) as never;

    it('counts connected surfaces and names the first one still to connect', () => {
      expect(
        surfacesCellText(
          [
            {
              connector: true,
              hooks: true,
              id: SurfaceID.ClaudeCode,
              name: 'Claude Code',
              skills: true,
              status: SurfaceStatus.Connected,
            },
            {
              connector: true,
              hooks: false,
              id: SurfaceID.ChatGPT,
              name: 'ChatGPT',
              skills: false,
              status: SurfaceStatus.NotConnected,
            },
          ],
          t
        )
      ).toEqual({
        sub: 'message.agent-harness-surface-not-connected {"name":"ChatGPT"}',
        value:
          'message.agent-harness-surfaces-connected {"connected":1,"total":2}',
      });
      expect(surfacesCellText(undefined, t)).toEqual({ sub: '', value: '—' });
    });
  });
});
