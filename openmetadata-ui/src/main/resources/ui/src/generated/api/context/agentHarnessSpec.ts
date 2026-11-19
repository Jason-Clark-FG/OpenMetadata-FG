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
 * The fields of an agent harness that an administrator edits. A draft holds one, `PUT
 * /v1/agentHarness/{id}/draft` takes one whole, and publishing copies it into the harness.
 * On a persona harness it holds only the persona's own settings, never the items inherited
 * from the default.
 */
export interface AgentHarnessSpec {
    /**
     * How `finish_answer` writes its citation block. Only on the default harness.
     */
    answerHarness?: AnswerHarness;
    /**
     * Instructions every agent receives. Only on the default harness.
     */
    companyInstructions?: string;
    /**
     * Knowledge rules, persona prompt, character budget and cache lifetime. The definition's
     * own `enabled` flag must stay true, because the harness's `enabled` decides. Its read-only
     * cache fields are ignored.
     */
    context?: PersonaContextDefinition;
    /**
     * Delivery settings per hook event. The default lists every event. A persona harness lists
     * only the events it adds or whose budget it lowers.
     */
    events?: EventDelivery[];
    /**
     * Guardrail levels. A persona harness lists only the guardrails it makes stricter.
     */
    guardrails?: GuardrailSetting[];
    /**
     * Agent memory settings. Absent on a persona harness that uses the default's.
     */
    memory?: MemorySettings;
    /**
     * Most tokens delivered across the events of one turn. At least the sum of the enabled
     * events' budgets.
     */
    perTurnTokenBudget?: number;
    /**
     * Skills offered to agents. A persona harness lists only the skills it adds.
     */
    skills?: SkillSetting[];
    /**
     * Which MCP servers and tools agents may call. A persona harness can only make the
     * default's decisions stricter.
     */
    toolAccess?: ToolAccess;
}

/**
 * How `finish_answer` writes its citation block. Only on the default harness.
 *
 * How `finish_answer` writes the "How I got this" block. Only the default harness's
 * settings apply, because two fiscal calendars would give two answers to one question.
 * Unset groups take built-in defaults.
 */
export interface AnswerHarness {
    /**
     * What the block shows.
     */
    answer?: Answer;
    /**
     * The organization's calendar for time phrases such as last quarter.
     */
    calendar?: Calendar;
    /**
     * Which trust signals the block reports.
     */
    trust?: Trust;
}

/**
 * What the block shows.
 */
export interface Answer {
    /**
     * Whether the block includes the SQL. A query that was not run always shows it.
     */
    showSql?: boolean;
}

/**
 * The organization's calendar for time phrases such as last quarter.
 */
export interface Calendar {
    /**
     * Month and day the fiscal year starts, as MM-DD.
     */
    fiscalYearStart?: string;
    /**
     * IANA time zone that decides where a day starts and ends.
     */
    timeZone?: string;
    /**
     * First day of the week.
     */
    weekStart?: WeekStart;
}

/**
 * First day of the week.
 */
export enum WeekStart {
    Monday = "Monday",
    Saturday = "Saturday",
    Sunday = "Sunday",
}

/**
 * Which trust signals the block reports.
 */
export interface Trust {
    /**
     * Whether failing data quality tests on a cited table appear in the block.
     */
    failingTests?: FailingTests;
    /**
     * Whether open incidents on a cited table appear in the block.
     */
    openIncidents?: FailingTests;
    /**
     * Age after which a table's freshness is flagged, as a number of hours (h) or days (d), for
     * example 24h.
     */
    staleAfter?: string;
}

/**
 * Whether failing data quality tests on a cited table appear in the block.
 *
 * Whether open incidents on a cited table appear in the block.
 */
export enum FailingTests {
    Ignore = "ignore",
    Warn = "warn",
}

/**
 * Knowledge rules, persona prompt, character budget and cache lifetime. The definition's
 * own `enabled` flag must stay true, because the harness's `enabled` decides. Its read-only
 * cache fields are ignored.
 *
 * Rules and materialization settings for the shared AI context document attached to a
 * Persona.
 */
export interface PersonaContextDefinition {
    /**
     * Read-only state of the compiled context cache.
     */
    cacheState?: CacheState;
    /**
     * Number of minutes a compiled context remains fresh before it is regenerated.
     */
    cacheTtlMinutes?: number;
    /**
     * Character budget for the compiled Markdown document. Mandatory knowledge and manifest
     * content can exceed this budget.
     */
    characterBudget?: number;
    /**
     * Whether persona context is enabled.
     */
    enabled?: boolean;
    /**
     * Read-only reason the latest compilation failed.
     */
    lastError?: string;
    /**
     * Read-only timestamp of the most recent successful compilation.
     */
    lastGeneratedAt?: number;
    /**
     * Instructions for the AI assistant serving this persona, such as who its users are, what
     * they understand and what they are responsible for. Sent to the assistant as instructions
     * while the context is enabled; never rendered into the context document.
     */
    prompt?: string;
    /**
     * Ordered dynamic entity-selection rules. An entity matched by multiple rules is rendered
     * under the first rule only.
     */
    rules?: ContextRule[];
}

/**
 * Read-only state of the compiled context cache.
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
 * Delivery setting of one hook event.
 */
export interface EventDelivery {
    /**
     * Whether context is delivered at this event. On a persona harness, true adds the event for
     * the persona. An event the default delivers stays delivered whatever the persona sets.
     */
    enabled: boolean;
    /**
     * The hook event.
     */
    id: HookEvent;
    /**
     * Most tokens delivered at this event. On a persona harness it cannot exceed the default's
     * budget for the event.
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
 * Level of one server-registered guardrail.
 */
export interface GuardrailSetting {
    /**
     * Id of a guardrail listed by `GET /v1/agentHarness/guardrails`.
     */
    id: string;
    /**
     * Level the guardrail runs at. On a persona harness it cannot be less strict than the
     * default's level.
     */
    level: GuardrailLevel;
}

/**
 * Level the guardrail runs at. On a persona harness it cannot be less strict than the
 * default's level.
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
 * Agent memory settings. Absent on a persona harness that uses the default's.
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
 * Whether one server-registered skill is offered to agents.
 */
export interface SkillSetting {
    /**
     * Whether the skill is offered. On a persona harness, true adds the skill for the persona.
     * A skill the default offers stays offered whatever the persona sets.
     */
    enabled: boolean;
    /**
     * Name of a skill listed by `GET /v1/agentHarness/skills`.
     */
    name: string;
}

/**
 * Which MCP servers and tools agents may call. A persona harness can only make the
 * default's decisions stricter.
 *
 * Which MCP servers and tools an agent may call. The first entry that matches a call
 * decides; a call no entry matches gets `default`. A persona harness can only make the
 * default's decisions stricter: each call gets the stricter of the default's decision and
 * the persona's.
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
