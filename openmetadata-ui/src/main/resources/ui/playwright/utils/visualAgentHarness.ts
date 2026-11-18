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
import { AgentHarnessManagedSettings } from '../../src/generated/api/context/agentHarnessManagedSettings';
import {
  AgentHarnessPersonaSummary,
  CacheState,
} from '../../src/generated/api/context/agentHarnessPersonaSummary';
import {
  AgentHarnessState,
  GuardrailLevel,
  HarnessKind,
  HarnessStatus,
  HookEvent,
  MemoryReview,
  MemorySettings,
  MemoryVisibility,
  ResolvedAgentHarness,
} from '../../src/generated/api/context/agentHarnessState';
import {
  AgentHarnessSurfaces,
  SurfaceID,
  SurfaceStatus,
} from '../../src/generated/api/context/agentHarnessSurfaces';
import { CompiledAgentContext } from '../../src/generated/api/context/compiledAgentContext';
import { GuardrailDefinition } from '../../src/generated/api/context/guardrailDefinition';
import { SkillDefinition } from '../../src/generated/api/context/skillDefinition';

interface VisualPersona {
  id: string;
  name: string;
  fullyQualifiedName?: string;
  displayName?: string;
  users?: unknown[];
}

const DEFAULT_HARNESS_ID = 'b7c1f0a2-3d4e-5f60-8a71-92b3c4d5e6f7';
const PERSONA_HARNESS_ID = 'c8d2a1b3-4e5f-4a71-9b82-a3c4d5e6f708';
const PUBLISHED_AT = 1768471200000;
const CHARACTER_BUDGET = 40000;
const PER_TURN_TOKEN_BUDGET = 2000;
const CACHE_TTL_MINUTES = 60;

const MEMORY: MemorySettings = {
  capture: true,
  dedup: true,
  linkAssets: true,
  review: MemoryReview.StewardReview,
  visibility: MemoryVisibility.Persona,
};

const SKILLS: SkillDefinition[] = [
  {
    defaultEnabled: true,
    description: 'Search and read catalog metadata.',
    name: 'openmetadata',
  },
];

const GUARDRAILS: GuardrailDefinition[] = [
  {
    available: true,
    defaultLevel: GuardrailLevel.Warn,
    description: 'Warns before a query reads a column tagged as PII.',
    event: HookEvent.PreToolUse,
    id: 'pii-columns',
    name: 'PII columns',
  },
];

const SURFACES: AgentHarnessSurfaces = {
  surfaces: [
    {
      connector: true,
      hooks: true,
      id: SurfaceID.ClaudeCode,
      lastSeenAt: PUBLISHED_AT,
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
};

const MANAGED_SETTINGS: AgentHarnessManagedSettings = {
  claudeCode: { allowedMcpServers: [{ serverName: 'openmetadata' }] },
  codexRequirementsToml: '[mcp_servers.openmetadata]\nrequired = true\n',
  notes: [],
  serverName: 'openmetadata',
  serverUrl: 'https://openmetadata.example.com/mcp',
};

const COMPILED_CONTEXT: CompiledAgentContext = {
  harness: {
    default: { id: DEFAULT_HARNESS_ID, name: 'default', version: 1 },
    persona: {
      id: PERSONA_HARNESS_ID,
      name: 'VisualAnalystPersona',
      version: 1,
    },
  },
  segments: [
    { kind: 'instructions', tokens: 120 },
    { kind: 'rules', tokens: 340 },
  ],
  text: '# Company instructions\nAnswer from governed, certified data first.\n\n# Analyst persona\nRevenue tables are reconciled daily.',
  tokens: 460,
};

const resolved = (kind: HarnessKind): ResolvedAgentHarness => ({
  cacheTtlMinutes: CACHE_TTL_MINUTES,
  characterBudget: CHARACTER_BUDGET,
  companyInstructions: 'Answer from governed, certified data first.',
  events: [HookEvent.SessionStart, HookEvent.UserPromptSubmit].map((id) => ({
    enabled: true,
    id,
    inherited: kind === HarnessKind.Persona,
    tokenBudget: PER_TURN_TOKEN_BUDGET,
  })),
  guardrails: [{ id: 'pii-columns', level: GuardrailLevel.Warn }],
  kind,
  memory: MEMORY,
  memoryInherited: kind === HarnessKind.Persona,
  perTurnTokenBudget: PER_TURN_TOKEN_BUDGET,
  prompt:
    kind === HarnessKind.Persona
      ? 'You help finance analysts reconcile revenue.'
      : undefined,
  rules: [],
  skills: [
    {
      enabled: true,
      inherited: kind === HarnessKind.Persona,
      name: 'openmetadata',
    },
  ],
});

const defaultState = (): AgentHarnessState => ({
  draftRevision: 1,
  published: {
    companyInstructions: 'Answer from governed, certified data first.',
    enabled: true,
    id: DEFAULT_HARNESS_ID,
    kind: HarnessKind.Default,
    memory: MEMORY,
    name: 'default',
    publishedAt: PUBLISHED_AT,
    version: 1,
  },
  resolved: resolved(HarnessKind.Default),
  status: HarnessStatus.Published,
});

const personaState = (persona: VisualPersona): AgentHarnessState => ({
  draftRevision: 1,
  published: {
    context: {
      prompt: 'You help finance analysts reconcile revenue.',
      rules: [],
    },
    enabled: true,
    id: PERSONA_HARNESS_ID,
    kind: HarnessKind.Persona,
    name: persona.name,
    persona: {
      displayName: persona.displayName,
      fullyQualifiedName: persona.fullyQualifiedName,
      id: persona.id,
      name: persona.name,
      type: 'persona',
    },
    publishedAt: PUBLISHED_AT,
    version: 1,
  },
  resolved: resolved(HarnessKind.Persona),
  status: HarnessStatus.Published,
});

const personaSummary = (
  persona: VisualPersona
): AgentHarnessPersonaSummary => ({
  cacheState: CacheState.Fresh,
  characterBudget: CHARACTER_BUDGET,
  charactersUsed: 1840,
  enabled: true,
  enabledEvents: [HookEvent.SessionStart, HookEvent.UserPromptSubmit],
  harnessId: PERSONA_HARNESS_ID,
  lastGeneratedAt: PUBLISHED_AT,
  persona: {
    displayName: persona.displayName,
    fullyQualifiedName: persona.fullyQualifiedName,
    id: persona.id,
    name: persona.name,
    type: 'persona',
  },
  status: HarnessStatus.Published,
  userCount: persona.users?.length ?? 0,
});

/** The editor compiles its preview with a POST that reads but never writes. */
export const isVisualAgentHarnessPreview = (path: string) =>
  /^agentHarness\/[^/]+\/(?:rules\/)?preview$/.test(path);

/**
 * The Agent Harness response for a fixture read, or undefined when the read is not modelled.
 * Every persona carries the same published harness; the empty state has no personas.
 */
export const visualAgentHarnessResponse = (
  path: string,
  personas: VisualPersona[],
  isEmpty: boolean
): unknown => {
  const listed = isEmpty ? [] : personas;
  const personaFqn = /^agentHarness\/persona\/(.+)$/.exec(path)?.[1];

  if (personaFqn !== undefined) {
    const persona = listed.find(
      ({ fullyQualifiedName, name }) =>
        (fullyQualifiedName ?? name) === personaFqn
    );

    return persona ? personaState(persona) : undefined;
  }
  if (/^agentHarness\/[^/]+\/rules\/preview$/.test(path)) {
    return { matchedCount: 0, sampleNames: [] };
  }
  if (/^agentHarness\/[^/]+\/preview$/.test(path)) {
    return COMPILED_CONTEXT;
  }

  const reads: Record<string, unknown> = {
    'agentHarness/default': defaultState(),
    'agentHarness/guardrails': GUARDRAILS,
    'agentHarness/managedSettings': MANAGED_SETTINGS,
    'agentHarness/personas': {
      data: listed.map(personaSummary),
      paging: { total: listed.length },
    },
    'agentHarness/skills': SKILLS,
    'agentHarness/surfaces': SURFACES,
  };

  return reads[path];
};
