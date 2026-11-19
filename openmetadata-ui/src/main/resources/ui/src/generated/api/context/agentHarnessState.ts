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
 * One agent harness as its editor needs it: the published harness, the draft when one
 * exists, the draft revision to send back as `If-Match`, and the resolved view for display.
 */
export interface AgentHarnessState {
    /**
     * The unpublished edits, with the harness's own fields only. Absent when there is no draft.
     */
    draft?: AgentHarnessSpec;
    /**
     * Revision of the draft, to send as `If-Match` on the next draft write. It grows on every
     * draft write, discard and publish and never repeats for a harness.
     */
    draftRevision: number;
    /**
     * The harness as published, with its own fields only. A persona harness that has never been
     * published has no `publishedAt`.
     */
    published: AgentHarness;
    /**
     * What the draft, or the published harness when there is no draft, amounts to once the
     * published default is applied. For display only. Edits go to the draft.
     */
    resolved: ResolvedAgentHarness;
    /**
     * Where the harness stands.
     */
    status: HarnessStatus;
}

/**
 * The unpublished edits, with the harness's own fields only. Absent when there is no
 * draft.
 *
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
     * How agents use the knowledge_graph tool. Absent on a persona harness that uses the
     * default's.
     */
    knowledgeGraph?: KnowledgeGraphSettings;
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
 *
 * How `finish_answer` writes its citation block. Read from the default harness only.
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
 *
 * Knowledge rules, persona prompt, character budget and cache lifetime. The definition's
 * own `enabled` flag must stay true, because the harness's `enabled` decides. Its read-only
 * cache fields are not stored.
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
 *
 * The rule as stored, under its own name.
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
 *
 * Level the guardrail runs at, never less strict than `minLevel`.
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
 * How agents use the knowledge_graph tool. Absent on a persona harness that uses the
 * default's.
 *
 * How agents use the knowledge_graph tool.
 *
 * knowledge_graph settings in effect, sub-agents merged as skills are.
 */
export interface KnowledgeGraphSettings {
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
 * Agent memory settings. Absent on a persona harness that uses the default's.
 *
 * How memories that agents capture are stored. A persona harness that sets these replaces
 * the default's settings for its members.
 *
 * Agent memory settings in effect.
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
 *
 * Which MCP servers and tools agents may call. Absent means every call is allowed.
 *
 * The default's MCP access, on a persona harness only. Read-only in the persona's editor.
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
 * The harness as published, with its own fields only. A persona harness that has never been
 * published has no `publishedAt`.
 *
 * The context an AI agent receives from OpenMetadata: which knowledge goes in, at which
 * hook events, with which instructions, skills, guardrails and memory settings. The entity
 * holds the published harness. Edits are held in a draft outside the entity until they are
 * published, so each version is one publish or one enable or disable switch.
 */
export interface AgentHarness {
    /**
     * How `finish_answer` writes its citation block. Read from the default harness only.
     */
    answerHarness?: AnswerHarness;
    /**
     * Change that led to this version.
     */
    changeDescription?: ChangeDescription;
    /**
     * Instructions every agent receives. Only on the default harness.
     */
    companyInstructions?: string;
    /**
     * Knowledge rules, persona prompt, character budget and cache lifetime. The definition's
     * own `enabled` flag must stay true, because the harness's `enabled` decides. Its read-only
     * cache fields are not stored.
     */
    context?: PersonaContextDefinition;
    /**
     * When true indicates the entity has been soft deleted.
     */
    deleted?: boolean;
    /**
     * Description of the harness.
     */
    description?: string;
    /**
     * Display name of the harness.
     */
    displayName?: string;
    /**
     * Whether the harness is applied. A disabled persona harness leaves its members with the
     * default alone. A disabled default delivers nothing to anyone. Changed with `PUT
     * /v1/agentHarness/{id}/enabled`, never through a draft.
     */
    enabled?: boolean;
    /**
     * Delivery settings per hook event. The default lists every event. A persona harness lists
     * only the events it adds or whose budget it lowers.
     */
    events?: EventDelivery[];
    /**
     * Fully qualified name, the same as `name`.
     */
    fullyQualifiedName?: string;
    /**
     * Guardrail levels. A persona harness lists only the guardrails it makes stricter.
     */
    guardrails?: GuardrailSetting[];
    /**
     * Link to this resource.
     */
    href?: string;
    /**
     * Unique identifier of the harness.
     */
    id: string;
    /**
     * Bot user that performed the action on behalf of the actual user.
     */
    impersonatedBy?: string;
    /**
     * Incremental change that led to this version.
     */
    incrementalChangeDescription?: ChangeDescription;
    /**
     * Whether this is the company default or a persona harness.
     */
    kind: HarnessKind;
    /**
     * How agents use the knowledge_graph tool. Absent on a persona harness that uses the
     * default's.
     */
    knowledgeGraph?: KnowledgeGraphSettings;
    /**
     * Agent memory settings. Absent on a persona harness that uses the default's.
     */
    memory?: MemorySettings;
    /**
     * Name of the harness. `CompanyDefault` for the default harness and the persona's id for a
     * persona harness, so a persona rename or a persona named like the default cannot collide.
     */
    name: string;
    /**
     * Persona whose members this harness serves. Required on a persona harness and absent on
     * the default. At most one harness refers to a persona.
     */
    persona?: EntityReference;
    /**
     * Most tokens delivered across the events of one turn. At least the sum of the enabled
     * events' budgets.
     */
    perTurnTokenBudget?: number;
    /**
     * Time of the latest publish in Unix epoch time milliseconds. Absent on a persona harness
     * that has never been published, which is not applied.
     */
    publishedAt?: number;
    /**
     * Skills offered to agents. A persona harness lists only the skills it adds.
     */
    skills?: SkillSetting[];
    /**
     * Which MCP servers and tools agents may call. Absent means every call is allowed.
     */
    toolAccess?: ToolAccess;
    /**
     * Last update time in Unix epoch time milliseconds.
     */
    updatedAt?: number;
    /**
     * User who made the update.
     */
    updatedBy?: string;
    /**
     * Metadata version of the entity.
     */
    version?: number;
}

/**
 * Change that led to this version.
 *
 * Description of the change.
 *
 * Incremental change that led to this version.
 */
export interface ChangeDescription {
    changeSummary?: { [key: string]: ChangeSummary };
    /**
     * Names of fields added during the version changes.
     */
    fieldsAdded?: FieldChange[];
    /**
     * Fields deleted during the version changes with old value before deleted.
     */
    fieldsDeleted?: FieldChange[];
    /**
     * Fields modified during the version changes with old and new values.
     */
    fieldsUpdated?: FieldChange[];
    /**
     * When a change did not result in change, this could be same as the current version.
     */
    previousVersion?: number;
}

export interface ChangeSummary {
    changedAt?: number;
    /**
     * Name of the user or bot who made this change
     */
    changedBy?:    string;
    changeSource?: ChangeSource;
    [property: string]: any;
}

/**
 * The source of the change. This will change based on the context of the change (example:
 * manual vs programmatic)
 */
export enum ChangeSource {
    Automated = "Automated",
    Derived = "Derived",
    Ingested = "Ingested",
    Manual = "Manual",
    Propagated = "Propagated",
    Suggested = "Suggested",
}

export interface FieldChange {
    /**
     * Name of the entity field that changed.
     */
    name?: string;
    /**
     * New value of the field. Note that this is a JSON string and use the corresponding field
     * type to deserialize it.
     */
    newValue?: any;
    /**
     * Previous value of the field. Note that this is a JSON string and use the corresponding
     * field type to deserialize it.
     */
    oldValue?: any;
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
 * Persona whose members this harness serves. Required on a persona harness and absent on
 * the default. At most one harness refers to a persona.
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
 * What the draft, or the published harness when there is no draft, amounts to once the
 * published default is applied. For display only. Edits go to the draft.
 *
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

/**
 * Where the harness stands.
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
