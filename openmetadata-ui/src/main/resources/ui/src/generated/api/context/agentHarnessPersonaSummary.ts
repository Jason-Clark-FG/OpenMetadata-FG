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
 * One persona in the agent harness list, as returned by `GET /v1/agentHarness/personas`.
 * The context figures are read from the context cache and never compiled for the list.
 */
export interface AgentHarnessPersonaSummary {
    /**
     * What the persona's harness adds to the default. Absent when the persona has no harness.
     */
    additions?: HarnessAdditions;
    /**
     * State of the cached document. A persona with nothing cached reports `STALE`.
     */
    cacheState: CacheState;
    /**
     * Character budget in effect for the persona's compiled document.
     */
    characterBudget: number;
    /**
     * Characters in the cached compiled document. Zero when nothing is cached.
     */
    charactersUsed: number;
    /**
     * The persona harness's `enabled` flag. Absent when the persona has no harness.
     */
    enabled?: boolean;
    /**
     * Hook events at which the persona's members receive context, with inheritance applied to
     * the published harnesses.
     */
    enabledEvents: HookEvent[];
    /**
     * Id of the persona's harness. Absent when the persona has none and its members get the
     * default.
     */
    harnessId?: string;
    /**
     * When the cached document was compiled. Absent when the context has never been generated.
     */
    lastGeneratedAt?: number;
    /**
     * The persona.
     */
    persona: EntityReference;
    /**
     * Where the persona's harness stands.
     */
    status: HarnessStatus;
    /**
     * Distinct active users who have the persona directly, through a team, or as their default
     * persona.
     */
    userCount: number;
}

/**
 * What the persona's harness adds to the default. Absent when the persona has no harness.
 *
 * What a persona harness adds to the default, counted from the harness's published settings.
 */
export interface HarnessAdditions {
    /**
     * Articles picked one by one.
     */
    articles: number;
    /**
     * Glossary terms picked one by one.
     */
    glossaryTerms: number;
    /**
     * Guardrails the harness makes stricter.
     */
    guardrails: number;
    /**
     * Metrics picked one by one.
     */
    metrics: number;
    /**
     * Context rules that are not picked lists.
     */
    rules: number;
    /**
     * Enabled skills the harness adds.
     */
    skills: number;
}

/**
 * State of the cached document. A persona with nothing cached reports `STALE`.
 *
 * Current state of the compiled persona context document.
 */
export enum CacheState {
    Failed = "FAILED",
    Fresh = "FRESH",
    Generating = "GENERATING",
    Stale = "STALE",
}

/**
 * Agent host event at which context is delivered.
 */
export enum HookEvent {
    PostToolUse = "PostToolUse",
    PreToolUse = "PreToolUse",
    SessionStart = "SessionStart",
    Stop = "Stop",
    UserPromptSubmit = "UserPromptSubmit",
}

/**
 * The persona.
 *
 * This schema defines the EntityReference type used for referencing an entity.
 * EntityReference is used for capturing relationships from one entity to another. For
 * example, a table has an attribute called database of type EntityReference that captures
 * the relationship of a table `belongs to a` database.
 */
export interface EntityReference {
    /**
     * If true the entity referred to has been soft-deleted.
     */
    deleted?: boolean;
    /**
     * Optional description of entity.
     */
    description?: string;
    /**
     * Display Name that identifies this entity.
     */
    displayName?: string;
    /**
     * Fully qualified name of the entity instance. For entities such as tables, databases
     * fullyQualifiedName is returned in this field. For entities that don't have name hierarchy
     * such as `user` and `team` this will be same as the `name` field.
     */
    fullyQualifiedName?: string;
    /**
     * Link to the entity resource.
     */
    href?: string;
    /**
     * Unique identifier that identifies an entity instance.
     */
    id: string;
    /**
     * If true the relationship indicated by this entity reference is inherited from the parent
     * entity.
     */
    inherited?: boolean;
    /**
     * Name of the entity instance.
     */
    name?: string;
    /**
     * Entity type/class name - Examples: `database`, `table`, `metrics`, `databaseService`,
     * `dashboardService`...
     */
    type: string;
}

/**
 * Where the persona's harness stands.
 *
 * Where a harness stands. `Published` has no pending draft. `Draft` has a draft that waits
 * to be published. `Inherited` means the persona has no harness and its members get the
 * default. `NotConfigured` is a persona harness that has never been published and has no
 * draft.
 */
export enum HarnessStatus {
    Draft = "Draft",
    Inherited = "Inherited",
    NotConfigured = "NotConfigured",
    Published = "Published",
}
