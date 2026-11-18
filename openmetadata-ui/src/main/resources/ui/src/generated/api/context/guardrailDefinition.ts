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
 * A guardrail the server can evaluate, as listed by `GET /v1/agentHarness/guardrails`.
 * Guardrails are defined in server code. A harness only sets their level.
 */
export interface GuardrailDefinition {
    /**
     * False when the server cannot evaluate the guardrail yet. A harness can still store a
     * level for it, and the level has no effect until the guardrail is available.
     */
    available: boolean;
    /**
     * Level the company default is seeded with.
     */
    defaultLevel: GuardrailLevel;
    /**
     * What the guardrail checks.
     */
    description: string;
    /**
     * Hook event at which the guardrail is evaluated.
     */
    event: HookEvent;
    /**
     * Stable id a harness uses to set the guardrail's level.
     */
    id: string;
    /**
     * Name shown to administrators.
     */
    name: string;
}

/**
 * Level the company default is seeded with.
 *
 * What a guardrail does when it matches, from least to most strict. Off does nothing, Warn
 * adds a warning to the context, Block denies the tool call.
 */
export enum GuardrailLevel {
    Block = "Block",
    Off = "Off",
    Warn = "Warn",
}

/**
 * Hook event at which the guardrail is evaluated.
 *
 * Agent host event at which context is delivered.
 */
export enum HookEvent {
    PostToolUse = "PostToolUse",
    PreToolUse = "PreToolUse",
    SessionStart = "SessionStart",
    Stop = "Stop",
    UserPromptSubmit = "UserPromptSubmit",
}
