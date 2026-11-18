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
import { HookEvent } from '../../generated/api/context/agentHarnessState';

/** Which harness an editor page shows: the company default, or one persona's. */
export type HarnessTarget =
  | { kind: 'default' }
  | { kind: 'persona'; personaFqn: string };

export enum HarnessTab {
  Context = 'context',
  Delivery = 'delivery',
  Instructions = 'instructions',
  Guardrails = 'guardrails',
  Memory = 'memory',
  Surfaces = 'surfaces',
}

/** The four source cards of the Context tab. A rule belongs to one by its entity type. */
export enum HarnessSource {
  GlossaryTerms = 'glossaryTerms',
  Metrics = 'metrics',
  Assets = 'assets',
  Articles = 'articles',
}

/** Sources whose items an administrator picks one by one. */
export type PickableSource = Exclude<HarnessSource, HarnessSource.Assets>;

export interface SourceCount {
  /** Ids of the entities picked one by one. Always empty for assets. */
  pickedIds: string[];
  /** Rules that select entities of this source by a filter. */
  ruleCount: number;
}

/** The chip groups the persona list shows for the events a persona's members receive. */
export enum DeliveryGroup {
  Session = 'session',
  Prompt = 'prompt',
  ToolUse = 'toolUse',
  SessionEnd = 'sessionEnd',
}

/** One Delivery tab row: the event as it applies to this harness after inheritance. */
export interface DeliveryRow {
  id: HookEvent;
  enabled: boolean;
  tokenBudget: number;
  /** The default delivers this event, so a persona harness cannot turn it off. */
  inherited: boolean;
  /** The default's budget, the most a persona harness may set. */
  maxTokenBudget?: number;
}

export type BudgetError =
  | 'notPositive'
  | 'outOfRange'
  | 'aboveDefault'
  | 'belowEvents';

export interface HarnessBudgetErrors {
  characterBudget?: BudgetError;
  perTurnTokenBudget?: BudgetError;
  cacheTtlMinutes?: BudgetError;
  events: Partial<Record<HookEvent, BudgetError>>;
}

/** What a persona harness adds to the default, for the list's Harness column. */
export interface HarnessAdditions {
  glossaryTerms: number;
  metrics: number;
  articles: number;
  rules: number;
  skills: number;
  guardrails: number;
}
