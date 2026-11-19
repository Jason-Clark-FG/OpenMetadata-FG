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
/**
 * The context compiled for the calling user at one hook event, returned by `POST
 * /v1/agentHarness/compile`.
 */
export interface CompiledAgentContext {
    /**
     * Guardrail decision for the tool call. Present only for PreToolUse.
     */
    decision?: Decision;
    /**
     * The harnesses the context was compiled from.
     */
    harness: AppliedHarnesses;
    /**
     * The parts `text` is made of, in order.
     */
    segments: Segment[];
    /**
     * The context to inject at the event.
     */
    text: string;
    /**
     * Estimated tokens in `text`. Equal to the sum of the segments.
     */
    tokens: number;
}

/**
 * Guardrail decision for the tool call. Present only for PreToolUse.
 *
 * Outcome of the guardrails evaluated for a tool call.
 */
export interface Decision {
    /**
     * The strictest action any matching guardrail asks for.
     */
    action: DecisionAction;
    /**
     * One reason per guardrail that matched.
     */
    reasons: string[];
}

/**
 * The strictest action any matching guardrail asks for.
 *
 * What the agent host does with the tool call.
 */
export enum DecisionAction {
    Allow = "allow",
    Deny = "deny",
    Warn = "warn",
}

/**
 * The harnesses the context was compiled from.
 *
 * The harnesses that applied to the caller.
 */
export interface AppliedHarnesses {
    /**
     * The company default.
     */
    default: AppliedHarness;
    /**
     * The harness of the caller's persona. Absent when that persona has no enabled, published
     * harness.
     */
    persona?: AppliedHarness;
}

/**
 * The company default.
 *
 * A published harness the context was compiled from.
 *
 * The harness of the caller's persona. Absent when that persona has no enabled, published
 * harness.
 */
export interface AppliedHarness {
    /**
     * Human-readable name to show for the harness. A persona harness is named by its persona's
     * id so a persona rename does not break it; this is the persona's display name, or its name
     * when it has none.
     */
    displayName?: string;
    /**
     * Id of the harness.
     */
    id: string;
    /**
     * Name of the harness.
     */
    name: string;
    /**
     * Published version the context was compiled from.
     */
    version: number;
}

/**
 * One part of the compiled text and its size.
 */
export interface Segment {
    /**
     * What the part holds, for example Instructions, Glossary, Metrics, Assets or Memories.
     */
    kind: string;
    /**
     * Estimated tokens in the part.
     */
    tokens: number;
}
