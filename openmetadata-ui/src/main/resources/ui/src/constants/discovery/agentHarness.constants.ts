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
import { BadgeColors } from '@openmetadata/ui-core-components';
import { EntityType } from '../../enums/entity.enum';
import { SearchIndex } from '../../enums/search.enum';
import {
  GuardrailLevel,
  HarnessStatus,
  HookEvent,
  MemoryReview,
  MemoryVisibility,
  ToolAccessDefault,
  ToolAccessLevel,
} from '../../generated/api/context/agentHarnessState';
import { SurfaceID } from '../../generated/api/context/agentHarnessSurfaces';
import { CompileEvent } from '../../generated/api/context/compileAgentContext';
import { PreviewAgentHarness } from '../../generated/api/context/previewAgentHarness';
import {
  BudgetError,
  DeliveryGroup,
  HarnessSource,
  HarnessTab,
  PickableSource,
  PluginTarget,
  SurfaceAction,
  ToolAccessError,
} from '../../interface/discovery/agentHarness.interface';

/** How each surface gets the harness, and the one action its row offers. */
export const SURFACE_ROWS: Record<
  SurfaceID,
  { viaKey: string; action: SurfaceAction }
> = {
  [SurfaceID.ClaudeChat]: {
    action: SurfaceAction.ShowSetup,
    viaKey: 'message.agent-harness-surface-via-admin-console',
  },
  [SurfaceID.ClaudeDesktop]: {
    action: SurfaceAction.ShowSetup,
    viaKey: 'message.agent-harness-surface-via-admin-console',
  },
  [SurfaceID.Cowork]: {
    action: SurfaceAction.ShowSetup,
    viaKey: 'message.agent-harness-surface-via-admin-console',
  },
  [SurfaceID.ChatGPT]: {
    action: SurfaceAction.CopyConnectorUrl,
    viaKey: 'message.agent-harness-surface-via-workspace-connector',
  },
  [SurfaceID.ClaudeCode]: {
    action: SurfaceAction.CopyInstallCommand,
    viaKey: 'label.plugin',
  },
  [SurfaceID.Codex]: {
    action: SurfaceAction.CopyInstallCommand,
    viaKey: 'label.plugin',
  },
};

/** Guardrail levels from loosest to strictest; a persona harness may only move right. */
export const GUARDRAIL_LEVEL_ORDER: GuardrailLevel[] = [
  GuardrailLevel.Off,
  GuardrailLevel.Warn,
  GuardrailLevel.Block,
];

export const GUARDRAIL_LEVEL_LABEL_KEYS: Record<GuardrailLevel, string> = {
  [GuardrailLevel.Off]: 'label.off',
  [GuardrailLevel.Warn]: 'label.warn',
  [GuardrailLevel.Block]: 'label.block',
};

/** MCP access levels from loosest to strictest, the order the server compares them in. */
export const TOOL_ACCESS_LEVEL_ORDER: ToolAccessLevel[] = [
  ToolAccessLevel.Allow,
  ToolAccessLevel.Warn,
  ToolAccessLevel.Block,
];

export const TOOL_ACCESS_LEVEL_LABEL_KEYS: Record<ToolAccessLevel, string> = {
  [ToolAccessLevel.Allow]: 'label.allow',
  [ToolAccessLevel.Warn]: 'label.warn',
  [ToolAccessLevel.Block]: 'label.block',
};

export const TOOL_ACCESS_DEFAULT_LABEL_KEYS: Record<ToolAccessDefault, string> =
  {
    [ToolAccessDefault.Allow]: 'label.allow',
    [ToolAccessDefault.Deny]: 'label.deny',
  };

export const TOOL_ACCESS_ERROR_KEYS: Record<ToolAccessError, string> = {
  belowDefault: 'message.agent-harness-mcp-below-default',
  duplicate: 'message.agent-harness-mcp-duplicate-server',
  matchRequired: 'message.agent-harness-mcp-server-required',
};

export const MEMORY_REVIEW_LABEL_KEYS: Record<MemoryReview, string> = {
  [MemoryReview.StewardReview]: 'label.steward-review',
  [MemoryReview.AuthorOnly]: 'label.author-only',
};

export const MEMORY_VISIBILITY_LABEL_KEYS: Record<MemoryVisibility, string> = {
  [MemoryVisibility.Private]: 'label.private',
  [MemoryVisibility.Persona]: 'label.persona',
  [MemoryVisibility.Organization]: 'label.organization',
};

export const PLUGIN_TARGETS: { target: PluginTarget; labelKey: string }[] = [
  { labelKey: 'label.claude', target: PluginTarget.Claude },
  { labelKey: 'label.chatgpt', target: PluginTarget.ChatGpt },
  { labelKey: 'label.codex-and-claude-code', target: PluginTarget.Cli },
];

/** The zip the server serves (`PluginBuilder`); its entries never change with the harness. */
export const PLUGIN_ZIP_NAME = 'openmetadata.zip';
export const PLUGIN_DIR = PLUGIN_ZIP_NAME.replace(/\.zip$/, '');
export const PLUGIN_ZIP_ENTRIES = [
  '.claude-plugin/plugin.json',
  '.codex-plugin/plugin.json',
  '.mcp.json',
  'hooks/hooks.json',
  'skills/openmetadata/SKILL.md',
];
export const PLUGIN_INCLUDE_KEYS = [
  'label.skill',
  'label.mcp',
  'label.hook-plural',
];

export const CLAUDE_MANAGED_SETTINGS_FILE = 'managed-settings.json';
export const CODEX_REQUIREMENTS_FILE = 'requirements.toml';

/** How long a download or copy button shows its done label before it reverts. */
export const DONE_LABEL_MS = 1800;

export const HOOK_EVENT_ORDER: HookEvent[] = [
  HookEvent.SessionStart,
  HookEvent.UserPromptSubmit,
  HookEvent.PreToolUse,
  HookEvent.PostToolUse,
  HookEvent.Stop,
];

export const HARNESS_TABS: HarnessTab[] = [
  HarnessTab.Context,
  HarnessTab.Delivery,
  HarnessTab.Instructions,
  HarnessTab.Guardrails,
  HarnessTab.Memory,
  HarnessTab.Surfaces,
];

export const HARNESS_TAB_LABEL_KEYS: Record<HarnessTab, string> = {
  [HarnessTab.Context]: 'label.context',
  [HarnessTab.Delivery]: 'label.delivery',
  [HarnessTab.Instructions]: 'label.instructions-and-skills',
  [HarnessTab.Guardrails]: 'label.guardrail-plural',
  [HarnessTab.Memory]: 'label.memory',
  [HarnessTab.Surfaces]: 'label.surface-plural',
};

export const HARNESS_STATUS_BADGE: Record<
  HarnessStatus,
  { color: BadgeColors; labelKey: string }
> = {
  [HarnessStatus.Published]: { color: 'success', labelKey: 'label.published' },
  [HarnessStatus.Draft]: { color: 'warning', labelKey: 'label.draft-changes' },
  [HarnessStatus.Inherited]: { color: 'gray', labelKey: 'label.inherited' },
  [HarnessStatus.NotConfigured]: {
    color: 'error',
    labelKey: 'label.not-configured',
  },
};

export const DELIVERY_GROUP_ORDER: DeliveryGroup[] = [
  DeliveryGroup.Session,
  DeliveryGroup.Prompt,
  DeliveryGroup.ToolUse,
  DeliveryGroup.SessionEnd,
];

export const DELIVERY_GROUP_OF_EVENT: Record<HookEvent, DeliveryGroup> = {
  [HookEvent.SessionStart]: DeliveryGroup.Session,
  [HookEvent.UserPromptSubmit]: DeliveryGroup.Prompt,
  [HookEvent.PreToolUse]: DeliveryGroup.ToolUse,
  [HookEvent.PostToolUse]: DeliveryGroup.ToolUse,
  [HookEvent.Stop]: DeliveryGroup.SessionEnd,
};

export const DELIVERY_GROUP_LABEL_KEYS: Record<DeliveryGroup, string> = {
  [DeliveryGroup.Session]: 'label.session',
  [DeliveryGroup.Prompt]: 'label.prompt',
  [DeliveryGroup.ToolUse]: 'label.tool-use',
  [DeliveryGroup.SessionEnd]: 'label.session-end',
};

export const HOOK_EVENT_COPY: Record<
  HookEvent,
  { labelKey: string; descriptionKey: string; injectKeys: string[] }
> = {
  [HookEvent.SessionStart]: {
    labelKey: 'label.session-start',
    descriptionKey: 'message.agent-harness-event-session-start',
    injectKeys: [
      'label.instruction-plural',
      'label.glossary-digest',
      'label.metric-list',
    ],
  },
  [HookEvent.UserPromptSubmit]: {
    labelKey: 'label.prompt-submit',
    descriptionKey: 'message.agent-harness-event-prompt-submit',
    injectKeys: ['label.top-matches', 'label.certified-alternative-plural'],
  },
  [HookEvent.PreToolUse]: {
    labelKey: 'label.before-tool-use',
    descriptionKey: 'message.agent-harness-event-before-tool-use',
    injectKeys: [
      'label.guardrail-plural',
      'label.asset-profile',
      'label.open-incident-plural',
    ],
  },
  [HookEvent.PostToolUse]: {
    labelKey: 'label.after-tool-use',
    descriptionKey: 'message.agent-harness-event-after-tool-use',
    injectKeys: ['label.dq-issue-plural', 'label.freshness'],
  },
  [HookEvent.Stop]: {
    labelKey: 'label.session-end',
    descriptionKey: 'message.agent-harness-event-session-end',
    injectKeys: ['label.memory-proposal-plural'],
  },
};

export const SOURCE_ORDER: HarnessSource[] = [
  HarnessSource.GlossaryTerms,
  HarnessSource.Metrics,
  HarnessSource.Assets,
  HarnessSource.Articles,
];

export const SOURCE_LABEL_KEYS: Record<HarnessSource, string> = {
  [HarnessSource.GlossaryTerms]: 'label.glossary-term-plural',
  [HarnessSource.Metrics]: 'label.metric-plural',
  [HarnessSource.Assets]: 'label.asset-plural',
  [HarnessSource.Articles]: 'label.article-plural',
};

export const PICKABLE_SOURCE_ENTITY_TYPE: Record<PickableSource, EntityType> = {
  [HarnessSource.GlossaryTerms]: EntityType.GLOSSARY_TERM,
  [HarnessSource.Metrics]: EntityType.METRIC,
  [HarnessSource.Articles]: EntityType.KNOWLEDGE_PAGE,
};

export const PICKABLE_SOURCE_SEARCH_INDEX: Record<
  PickableSource,
  | SearchIndex.GLOSSARY_TERM
  | SearchIndex.METRIC
  | SearchIndex.KNOWLEDGE_PAGE_INDEX
> = {
  [HarnessSource.GlossaryTerms]: SearchIndex.GLOSSARY_TERM,
  [HarnessSource.Metrics]: SearchIndex.METRIC,
  [HarnessSource.Articles]: SearchIndex.KNOWLEDGE_PAGE_INDEX,
};

/** Chips a source card shows before it folds the rest into a count. */
export const SOURCE_CARD_CHIPS = 3;
export const SOURCE_PICKER_PAGE_SIZE = 25;

export const BUDGET_ERROR_KEYS: Record<BudgetError, string> = {
  aboveDefault: 'message.agent-harness-budget-above-default',
  belowEvents: 'message.agent-harness-budget-below-events',
  notPositive: 'message.agent-harness-budget-not-positive',
  outOfRange: 'message.agent-harness-budget-out-of-range',
};

/**
 * Names of the rules that hold picked items. Rule names reach the compiled document, so they
 * are plain English like the rest of it, and unique within a harness by construction.
 */
export const PICKED_RULE_NAMES: Record<PickableSource, string> = {
  [HarnessSource.GlossaryTerms]: 'Picked glossary terms',
  [HarnessSource.Metrics]: 'Picked metrics',
  [HarnessSource.Articles]: 'Picked articles',
};

/** Server bounds (`PersonaRepository.validateContextDefinition`). */
export const CHARACTER_BUDGET_RANGE = { min: 10000, max: 2000000 };
export const CACHE_TTL_MINUTES_RANGE = { min: 1, max: 1440 };
export const MAX_PICKED_ITEMS = 1000;
export const MAX_HARNESS_RULES = 25;

export const DRAFT_SAVE_DELAY_MS = 800;
export const PREVIEW_DELAY_MS = 600;
export const CHARS_PER_TOKEN = 4;

export const PREVIEW_EVENTS: { event: CompileEvent; labelKey: string }[] = [
  { event: CompileEvent.SessionStart, labelKey: 'label.session-start' },
  { event: CompileEvent.UserPromptSubmit, labelKey: 'label.prompt' },
  { event: CompileEvent.PreToolUse, labelKey: 'label.tool-use' },
];

/**
 * What each preview stands in for. These go to the compiler as the user's turn, so they are
 * sample data rather than interface copy.
 */
export const PREVIEW_SAMPLE_REQUESTS: Record<
  CompileEvent,
  Omit<PreviewAgentHarness, 'spec'>
> = {
  [CompileEvent.SessionStart]: {},
  [CompileEvent.UserPromptSubmit]: {
    prompt: 'What was net revenue by region last quarter?',
  },
  [CompileEvent.PreToolUse]: {
    toolInput: {
      query: 'SELECT region, SUM(amount) FROM raw.orders GROUP BY 1',
    },
    toolName: 'run_sql',
  },
  [CompileEvent.PostToolUse]: { toolName: 'run_sql', toolOutput: [] },
};

/**
 * Segment colors of the rail's budget bar, in the order the compiler reports kinds. Utility
 * tokens flip in dark mode; core has no utility cyan, so blue-light stands in for cyan-400.
 */
export const SEGMENT_COLOR_CLASSES = [
  'tw:bg-utility-brand-800',
  'tw:bg-utility-brand-600',
  'tw:bg-utility-brand-400',
  'tw:bg-utility-blue-light-400',
  'tw:bg-utility-gray-400',
];
