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
 * An agent harness after inheritance is applied. The default's items come first and are
 * marked inherited, then the persona's own, and every level and budget is the stricter of
 * the two. The server computes it, and clients never merge harnesses themselves.
 */
export interface ResolvedAgentHarness {
    /**
     * Minutes a compiled document stays fresh.
     */
    cacheTtlMinutes: number;
    /**
     * Character budget of the compiled document. A persona harness's budget is capped at the
     * default's.
     */
    characterBudget: number;
    /**
     * The default's company instructions. Read-only on a persona harness.
     */
    companyInstructions?: string;
    /**
     * Every hook event, in delivery order.
     */
    events: ResolvedEvent[];
    /**
     * Every registered guardrail.
     */
    guardrails: ResolvedGuardrail[];
    /**
     * The default's MCP access, on a persona harness only. Read-only in the persona's editor.
     */
    inheritedToolAccess?: ToolAccess;
    /**
     * Whether this is the company default or a persona harness.
     */
    kind: HarnessKind;
    /**
     * knowledge_graph settings in effect, sub-agents merged as skills are.
     */
    knowledgeGraph?: KnowledgeGraphSettings;
    /**
     * Agent memory settings in effect.
     */
    memory: MemorySettings;
    /**
     * True when a persona harness sets no memory settings and the default's apply.
     */
    memoryInherited: boolean;
    /**
     * Most tokens delivered across the events of one turn. A persona harness's budget is capped
     * at the default's.
     */
    perTurnTokenBudget: number;
    /**
     * The harness's own prompt.
     */
    prompt?: string;
    /**
     * Knowledge rules in the order they are compiled: the default's, then the persona's own.
     */
    rules: ResolvedRule[];
    /**
     * Every registered skill.
     */
    skills: ResolvedSkill[];
    /**
     * The harness's own MCP access entries. On a persona harness the default's entries also
     * apply, and each call gets the stricter decision.
     */
    toolAccess?: ToolAccess;
}

/**
 * Effective delivery setting of one hook event.
 */
export interface ResolvedEvent {
    /**
     * Whether context is delivered at this event.
     */
    enabled: boolean;
    /**
     * The hook event.
     */
    id: HookEvent;
    /**
     * True when the default delivers at this event, so a persona harness cannot turn it off.
     */
    inherited: boolean;
    /**
     * The default's budget for this event, the most a persona harness may set. Absent on the
     * default harness.
     */
    maxTokenBudget?: number;
    /**
     * Most tokens delivered at this event, never above `maxTokenBudget`.
     */
    tokenBudget: number;
}

/**
 * The hook event.
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

/**
 * Effective level of one registered guardrail.
 */
export interface ResolvedGuardrail {
    /**
     * Id of the guardrail.
     */
    id: string;
    /**
     * Level the guardrail runs at, never less strict than `minLevel`.
     */
    level: GuardrailLevel;
    /**
     * The default's level, the least strict a persona harness may set. Absent on the default
     * harness.
     */
    minLevel?: GuardrailLevel;
}

/**
 * Level the guardrail runs at, never less strict than `minLevel`.
 *
 * What a guardrail does when it matches, from least to most strict. Off does nothing, Warn
 * adds a warning to the context, Block denies the tool call.
 *
 * The default's level, the least strict a persona harness may set. Absent on the default
 * harness.
 */
export enum GuardrailLevel {
    Block = "Block",
    Off = "Off",
    Warn = "Warn",
}

/**
 * The default's MCP access, on a persona harness only. Read-only in the persona's editor.
 *
 * Which MCP servers and tools an agent may call. The first entry that matches a call
 * decides; a call no entry matches gets `default`. A persona harness can only make the
 * default's decisions stricter: each call gets the stricter of the default's decision and
 * the persona's.
 *
 * The harness's own MCP access entries. On a persona harness the default's entries also
 * apply, and each call gets the stricter decision.
 */
export interface ToolAccess {
    /**
     * Decision for calls no entry matches.
     */
    default: ToolAccessDefault;
    /**
     * Entries in the order they are matched.
     */
    servers?: ToolAccessServer[];
}

/**
 * Decision for calls no entry matches.
 *
 * What happens to a call to an MCP server that no entry lists.
 */
export enum ToolAccessDefault {
    Allow = "Allow",
    Deny = "Deny",
}

/**
 * Access to one MCP server, or to some of its tools.
 */
export interface ToolAccessServer {
    /**
     * What happens to a matching call.
     */
    level: ToolAccessLevel;
    /**
     * MCP server name as the agent host reports it, the `<server>` in a tool name
     * `mcp__<server>__<tool>`. Matched without regard to case.
     */
    match: string;
    /**
     * Tool names the entry applies to. Absent or empty means every tool of the server.
     */
    tools?: string[];
}

/**
 * What happens to a matching call.
 *
 * What happens to a call an entry matches, from least to most strict. Allow lets it run,
 * Warn lets it run with a warning in the context, Block denies it.
 */
export enum ToolAccessLevel {
    Allow = "Allow",
    Block = "Block",
    Warn = "Warn",
}

/**
 * Whether this is the company default or a persona harness.
 *
 * Default is the one company-wide harness that applies to every user. Persona is a harness
 * for the members of one persona that extends the default.
 */
export enum HarnessKind {
    Default = "Default",
    Persona = "Persona",
}

/**
 * knowledge_graph settings in effect, sub-agents merged as skills are.
 *
 * How agents use the knowledge_graph tool.
 */
export interface KnowledgeGraphSettings {
    /**
     * Connections (outside MCP servers) the tool may call. None is offered unless listed here.
     * A persona harness lists only those it adds.
     */
    connections?: ConnectionSetting[];
    /**
     * Whether agents answer data questions through knowledge_graph (find, expand, cite last)
     * rather than the catalog tools.
     */
    entry?: boolean;
    /**
     * Whether what a turn learnt, sent with cite, is saved as the caller's memories.
     */
    learn?: boolean;
    /**
     * Whether the tool's query operation runs SPARQL for agents.
     */
    rawQueries?: boolean;
    /**
     * Sub-agents the tool may hand a task to. A persona harness lists only those it adds.
     */
    subAgents?: SubAgentSetting[];
}

/**
 * Whether one registered connection (an outside MCP server) is offered to the
 * knowledge_graph tool.
 */
export interface ConnectionSetting {
    /**
     * Whether the connection is offered. On a persona harness, true adds it for the persona;
     * one the default offers stays offered.
     */
    enabled: boolean;
    /**
     * Name of a connection listed by `GET /v1/agentHarness/connections`. A name no longer
     * registered is kept and does nothing.
     */
    name: string;
}

/**
 * Whether one registered sub-agent is offered to the knowledge_graph tool.
 */
export interface SubAgentSetting {
    /**
     * Whether the sub-agent is offered. On a persona harness, true adds it for the persona; one
     * the default offers stays offered.
     */
    enabled: boolean;
    /**
     * Name of a sub-agent listed by `GET /v1/agentHarness/subAgents`.
     */
    name: string;
}

/**
 * Agent memory settings in effect.
 *
 * How memories that agents capture are stored. A persona harness that sets these replaces
 * the default's settings for its members.
 */
export interface MemorySettings {
    /**
     * Whether agents may propose memories during a session.
     */
    capture: boolean;
    /**
     * Whether a captured memory is merged with an existing near-duplicate.
     */
    dedup: boolean;
    /**
     * Whether a captured memory is linked to the assets used in the turn.
     */
    linkAssets: boolean;
    /**
     * Who approves a captured memory.
     */
    review: MemoryReview;
    /**
     * Who receives a captured memory. Must be Private when review is AuthorOnly.
     */
    visibility: MemoryVisibility;
}

/**
 * Who approves a captured memory.
 *
 * Who approves a memory an agent captured. StewardReview follows the deployment's memory
 * lifecycle. AuthorOnly keeps the memory private to its author.
 */
export enum MemoryReview {
    AuthorOnly = "AuthorOnly",
    StewardReview = "StewardReview",
}

/**
 * Who receives a captured memory. Must be Private when review is AuthorOnly.
 *
 * Who receives a memory an agent captured: only its author, the members of the author's
 * persona, or everyone.
 */
export enum MemoryVisibility {
    Organization = "Organization",
    Persona = "Persona",
    Private = "Private",
}

/**
 * A knowledge rule and where it comes from.
 */
export interface ResolvedRule {
    /**
     * True when the rule comes from the default and is read-only on this harness.
     */
    inherited: boolean;
    /**
     * The rule as stored, under its own name.
     */
    rule: ContextRule;
}

/**
 * The rule as stored, under its own name.
 *
 * A dynamic entity-selection rule whose query is evaluated whenever the persona context is
 * materialized.
 */
export interface ContextRule {
    /**
     * Place this rule before relevance-scoped rules so its content is injected into every
     * request.
     */
    alwaysInContext?: boolean;
    /**
     * Optional explanation of why these entities belong in the persona context.
     */
    description?: string;
    /**
     * Whether the rule participates in materialization.
     */
    enabled?: boolean;
    /**
     * Entity type selected by the rule, for example table, dashboard, glossaryTerm, page, or
     * metric.
     */
    entityType: string;
    /**
     * Deliver this rule as a search scope instead of preloading its entities. When true the
     * rule contributes its entityType and queryFilter to the persona search scope handed to AI
     * search tools, and alwaysInContext, fullyRendered, sections and maxAssets are ignored.
     * Absent is read as false so rules stored before this field keep preloading; the rule
     * creation API stamps true when the client omits it, making search scoping the default for
     * new rules.
     */
    filteredInSearch?: boolean;
    /**
     * Serialized react-awesome-query-builder JSON tree used only to restore the rule editor UI.
     */
    filterJsonTree?: string;
    /**
     * Render complete documentation for every matched entity and ignore sections. Fully
     * rendered Data Products also include the context of every contained asset.
     */
    fullyRendered?: boolean;
    /**
     * Stable identifier used by the persona AI context rule CRUD APIs.
     */
    id?: string;
    /**
     * Read-only count of entities currently matched by this rule.
     */
    matchedCount?: number;
    /**
     * Maximum number of matching entities to include from this rule.
     */
    maxAssets?: number;
    /**
     * Unique display name for the rule within this persona.
     */
    name: string;
    /**
     * True when the rule holds glossary terms, metrics or articles an administrator picked one
     * by one. It is still an ordinary rule whose filter selects them by id. Editors read the
     * flag to show the picks as a list instead of a filter.
     */
    picked?: boolean;
    /**
     * Elasticsearch query DSL string emitted by the Explore query builder. Empty selects every
     * entity of the configured type.
     */
    queryFilter?: string;
    /**
     * Entity-type-specific documentation sections to render. Ignored when fullyRendered is true.
     */
    sections?: ContextSection[];
}

/**
 * A section of an entity's documentation that can be included by a persona context rule.
 */
export enum ContextSection {
    Articles = "articles",
    Constraints = "constraints",
    DataQuality = "dataQuality",
    Definition = "definition",
    Description = "description",
    FormulaExpression = "formulaExpression",
    FullBody = "fullBody",
    GlossaryTerms = "glossaryTerms",
    Joins = "joins",
    Lineage = "lineage",
    Metrics = "metrics",
    Owner = "owner",
    Profile = "profile",
    RelatedAssets = "relatedAssets",
    RelatedTerms = "relatedTerms",
    Schema = "schema",
    Synonyms = "synonyms",
    Tags = "tags",
    TitleSummary = "titleSummary",
    UnitGrain = "unitGrain",
}

/**
 * Whether one registered skill is offered.
 */
export interface ResolvedSkill {
    /**
     * Whether the skill is offered to agents.
     */
    enabled: boolean;
    /**
     * True when the default offers the skill, so a persona harness cannot turn it off.
     */
    inherited: boolean;
    /**
     * Name of the skill.
     */
    name: string;
}
