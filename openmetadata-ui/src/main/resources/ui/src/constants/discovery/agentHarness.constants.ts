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
import {
  HarnessStatus,
  HookEvent,
} from '../../generated/api/context/agentHarnessState';
import { CompileEvent } from '../../generated/api/context/compileAgentContext';
import {
  DeliveryGroup,
  HarnessSource,
  HarnessTab,
  PickableSource,
} from '../../interface/discovery/agentHarness.interface';

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
