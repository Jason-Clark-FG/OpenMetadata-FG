# Agent Harness implementation plan

- **Status:** Draft, revised after a four-model adversarial review (§0)
- **Date:** 2026-11-18
- **Inputs:** `docs/plans/2026-11-18-default-context-harness.md` (server design, the "design doc"
  below); the Agent Harness UI specification and its clickable prototype (`Agent Harness.dc.html`),
  "the UI spec" below
- **Scope:** spec, server, MCP, migrations, UI, plugin generator, tests. The `om-context` binary
  and the answer harness execution path are separate tracks (§9)

## 0. Review outcome (2026-11-18)

Four reviewers (Opus, Fable, Sonnet, Haiku) checked the first draft against the code. The
findings they agreed on, and that the code confirmed, changed the plan as follows. The sections
below already reflect these changes.

| Finding | Change |
|---|---|
| The turn compiler and `get_context` were in no track, so the plugin's hooks and the preview had nothing to call | Track A builds the compiler: `POST /v1/agentHarness/compile` (§3.2), used by the hooks, `get_context`, and the preview |
| One plugin built from every persona harness would ship persona prompts and article-derived skills to every employee, bypassing read access, and would need a re-upload on every publish | The plugin is static and carries no harness content. Everything is fetched per caller over MCP or the compile endpoint (§5) |
| JSON Patch against a merged view uses array indexes that do not exist in the stored persona spec; entity version consolidation merges a user's edits within 10 minutes (`EntityRepository.consolidateChanges`), so a draft inside the entity makes versions timing-dependent | The entity is the published spec, and Publish is its only write. The draft lives in an unversioned table, written whole with `If-Match` (§2.1, §3) |
| Seeding the company default with rules changes every persona's compiled context and MCP search scope on upgrade | The default is seeded with no rules and no instructions, so upgrade changes nothing (§2.2) |
| `EditAll` is granted on all resources to six seeded bot policies (`IngestionBotPolicy` and others) | Writes, publish, and preview keep `authorizeAdmin`, as persona AI context does today. The entity has no `owners` field (§3) |
| The MCP server is a single `McpStatelessSyncServer` with static instructions and no sessions | MCP instructions are static and tell the agent to call `get_context`; per-caller content comes from the tool. Surface status reads the existing per-call client label (`McpUsageRecorder`) (§4, §5.3) |
| Guardrails had no evaluator in track A | The compiler evaluates four guardrails at PreToolUse against live entity state, not the cache. Query cost ceiling stays disabled until track B (§2.3) |
| Removing `contextDefinition` from `persona.json` (`additionalProperties: false`) breaks reading old persona versions | The migration strips the field from stored persona versions too (§2.2) |
| Missing pieces: `/v1/personas/me/context` and the rule preview endpoint the rule editor calls, the table DDL, persona delete cascade, the route collision with a persona named `default`, the unpaged list | Added to §2.2, §3, and §6 |
| Rollout flag contradicted the one-release migration | No flag. The API, migration, and UI ship together (owner decision) |

Not adopted, with reasons: three reviewers recommended deferring the `persona` memory principal to
track C and deferring signing to track D. The owner chose to add the principal (§12, decision 2),
so it stays, as the last server step. Signing stays an owner decision (§12, decision 5), but no
host verifies plugin signatures and the verifier is the track D binary, so the signer is built in
track D together with that verifier (§5.1).

## 1. What the UI spec changes in the design doc

The UI spec is the product decision. Where it differs from the design doc, the UI spec wins, and
the design doc is amended in the same PR series (§10, step 0).

| Topic | Design doc | UI spec (adopted) | Consequence |
|---|---|---|---|
| Harness storage | Extend `persona.contextDefinition`; no new entity | A harness with its own version, draft, publish, and status (`/v1/agentHarness`) | New `agentHarness` entity whose versions are its publishes; drafts in a separate table (§2). Persona context definitions migrate into it |
| Inheritance | A persona's rules and prompt replace the default's; only delivery, guardrails and memory inherit | A persona harness **extends** the default: inherited items are read-only and always present; the persona adds items; guardrails can only get stricter; event budgets stay within the default's | The resolver merges the published default and persona into one `PersonaContextDefinition` for the compiler, and returns a display view with `inherited` and `minLevel` flags. Editors write only the persona's own spec, so inherited items cannot be written (§2.1, §3) |
| Resolution | active persona → default persona → organization default → built-in | A user without a persona harness gets the default; one with a persona harness gets default + persona | The persona comes from the same chain as today (`X-OpenMetadata-Persona`, then the user's default persona, then the system default persona, `persona.default`). If that persona has an enabled, published harness, the caller gets default + persona; otherwise the default alone. `CompanyDefault` is a harness, not a persona, so the two "default" concepts do not overlap |
| Events | 4 (no PostToolUse); budgets 2,000 / 1,500 / 800 | 5 events: SessionStart 6,000, UserPromptSubmit 4,000, PreToolUse 2,000, PostToolUse 1,000, Stop 1,000; per-turn budget 20,000 | Add `PostToolUse` (open incidents and failed tests on assets in the tool output). Validation: per-turn budget ≥ sum of enabled event budgets. The design doc's per-rule `deliverAt` is dropped: each event selects by its own rule (§3.2), which the Delivery tab matches. Hosts cap injected text (Claude Code hook output, MCP instructions); the hook command truncates to the host cap, and the real-host check (§7.5) records the caps |
| Sources | Rules only (entity type + query filter) | Explicitly picked glossary terms, metrics, articles, plus asset rules | No second mechanism: a picked term, metric, or article becomes a `ContextRule` with an id filter and `alwaysInContext`, marked `picked: true`. The cards count rules by entity type, so migrated rules show on the right card, and the builder gains no new path |
| Guardrails | tag/status → warn/deny/none | Server-registered guardrail ids with Off / Warn / Block; seeds: Prefer certified tables (Warn), Block deprecated assets (Block), PII columns need masking (Block), Flag open incidents (Warn), Query cost ceiling (Off) | Guardrail registry in the server; four evaluators in track A, cost ceiling in track B (§2.3) |
| Memory | `requireReview`, `defaultVisibility` (memory visibility enum) | Capture, link to assets, deduplicate; review AutoPublish / StewardReview / AuthorOnly; visibility Private / Persona / Organization | Mapping in §2.4. **AutoPublish is dropped**; review is Steward review or Author only. "Persona" visibility adds `persona` as a memory share principal. Settings are stored in A and take effect with capture in track C |
| Plugin contents | `.mcp.json`, hooks; no `bin/` | Adds instructions as `CLAUDE.md · AGENTS.md` and guardrails as `policy.json` | Claude plugins do not load `CLAUDE.md` or `policy.json` (§5.2). The plugin is static: the MCP server (whose instructions say how to use OpenMetadata) and hooks that call the compile endpoint. Company and persona instructions, guardrails, and skills are served per caller, so publishing never requires a re-upload |
| Preview | `GET .../preview?event=` | "The preview reflects unsaved draft state" | `POST .../preview` runs the compiler on the draft, admin only (§3.2) |

## 2. Data model

### 2.1 `agentHarness` entity and draft

New schema `openmetadata-spec/.../entity/context/agentHarness.json` (`@om-entity-type`), with
`EntityInterface` and its own table `agent_harness_entity`. The entity JSON **is the published
harness**. Publish is the only write to it, so its version history is the publish history and
version consolidation cannot merge a draft into a publish.

```text
AgentHarness                                  # entity = published harness
  id, name, fullyQualifiedName, displayName, description, version, updatedAt, updatedBy, deleted
  kind: Default | Persona
  persona: EntityReference                    # Persona only; unique index on persona id
  enabled: boolean
  context: PersonaContextDefinition           # existing type, unchanged: rules, prompt,
                                              # characterBudget, cacheTtlMinutes, enabled
  companyInstructions: markdown               # Default only
  perTurnTokenBudget: int (20000)
  events:     [{ id: HookEvent, enabled, tokenBudget }]
  skills:     [{ name, enabled }]
  guardrails: [{ id, level: Off | Warn | Block }]
  toolAccess: { default: Allow | Deny, servers: [{ match, level: Allow | Warn | Block, tools? }] }
  memory:     { capture, linkAssets, dedup, review: StewardReview | AuthorOnly,
                visibility: Private | Persona | Organization }

agent_harness_draft (table, not versioned)
  harnessId (PK), json (same shape as the entity's editable fields), revision int, updatedBy, updatedAt
```

- `context` is `PersonaContextDefinition` by `$ref`, so the rule builder, `PersonaContextRuleValidation`,
  `PersonaContextHash`, and `PersonaContextBuilder` are reused as they are. The persona prompt is
  `context.prompt`. Picked terms, metrics, and articles are rules (§1).
- Kind-specific fields are checked by the repository on write: `persona` is required for `Persona`
  and absent for `Default`, `companyInstructions` only on `Default`, exactly one `Default`.
- No `owners` field: owners would let the `Owner-Rule` and `NoOwner` organization rules grant edit
  on an unowned harness.
- `answerHarness` is added in track B, not here.
- Persona delete removes its harness and draft; persona rename needs no change because the
  harness refers to the persona by id.

### 2.2 Migration from persona AI context

`bootstrap/sql/migrations/native/2.1.0/` (the current development migration) for the tables, and a
Java data migration in `migration/.../v210` (FQN hashes are computed in Java), MySQL and Postgres:

1. `schemaChanges.sql`: create `agent_harness_entity` and `agent_harness_draft`.
2. Seed `CompanyDefault`: no rules, no instructions, the UI spec's event budgets, the guardrail seed
   levels, memory review `StewardReview`, visibility `Private`. An empty default keeps every
   persona's compiled context and MCP search scope unchanged on upgrade.
3. For each persona with a non-empty `contextDefinition`, in id batches
   (ADR:2026-10-09-data-migration-backfills-walk-ids-in-batches): create a `Persona` harness, named
   after the persona, with `context` copied as is. Keyed by persona id, so a rerun skips it.
4. Only after step 3 completes: strip `contextDefinition` from the persona rows **and** from the
   persona versions in `entity_extension`, because `persona.json` is strict and old versions must
   still deserialize.
5. Remove `contextDefinition` from `persona.json` and `createPersona.json`, and the RDF mapping in
   `openmetadata.ttl`.

Removed in the same release, with no compatibility layer: `/v1/personas/{id}/aiContext*`
(including `rules`, `rules/preview`, `document`, `document:refresh`), `/v1/personas/aiContext/status`,
and `/v1/personas/me/context`. `PersonaResource`'s `/{id}/context` and `/name/{fqn}/context`
override the entity-wide AI context route (`EntityResource.getAiContextById`), so they stay and
return the caller's compiled harness for that persona instead of the stored definition.

Callers moved in the same change: `PersonaContextCache`, `PersonaContextAccess`,
`PersonaSearchScope` (MCP search scope), the MCP persona context tool, `UserRepository` persona
resolution, and `playwright/utils/visualData.ts`. Before the PR, search the Collate repository for
`getContextDefinition`, `PersonaContextDefinition` imports on `Persona`, and the removed paths.
`PersonaContextCache.getForCaller(persona, …)` keeps its signature and now returns the merged
default + persona context. With the empty seeded default that is the same content as today.

Downgrade is not supported: the previous release cannot read personas without the field. The
release notes say to back up before upgrading, as for other dropping migrations.

### 2.3 Guardrail and skill registries

- **Guardrails** are code-defined (`GuardrailDefinition`: id, name, description, default level,
  event, evaluator), listed by `GET /v1/agentHarness/guardrails`. Evaluators run in the compiler
  at PreToolUse on the assets named in the tool input, reading **live** entity state (bounded to 20
  assets), never the compile cache, so a table deprecated a minute ago is caught:

  | Guardrail | Seed | Evaluator |
  |---|---|---|
  | Prefer certified tables | Warn | Asset has no certification and a certified table shares its glossary terms |
  | Block deprecated assets | Block | Asset is deprecated (`deprecation` or the `Deprecated` lifecycle tag) |
  | PII columns need masking | Block | Asset has columns tagged `PII.Sensitive` and the caller has no masking policy for them |
  | Flag open incidents | Warn | Asset has a test case whose latest resolution status is not `Resolved` |
  | Query cost ceiling | Off | Disabled in the UI ("needs the answer harness") until track B |

  Block returns a deny decision; the hook turns it into the host's deny output. The server
  decision is what counts; hooks fail open on errors, and track B's `validate_query` enforces on
  surfaces without hooks.
- **Skills** are server-registered built-ins (`find-context`, `metric-definition`, …), listed by
  `GET /v1/agentHarness/skills`. Enabled skills are returned by `get_context` for the caller.
  Article-exported skills (`exportAsSkill`) are not in track A: they would put content written by
  any article author into a high-trust channel, and need an approval gate first.

### 2.4 Memory settings mapping

| UI value | `contextMemory` effect |
|---|---|
| Capture | Track C: `capture` endpoint and `remember` tool accept writes for members |
| Link to assets used | `primaryEntity` and `relatedEntities` from the answer plan (design doc §7.5) |
| Deduplicate | Track C: run `SemanticMemoryDuplicateFinder` before inserting a captured memory (today it runs only for file extraction) |
| Review: StewardReview (default) | The deployment's normal memory lifecycle: approved at once on OSS (ADR:2026-11-05-oss-memories-are-approved-without-reconciliation), reviewed on Collate (ADR:2026-10-09-data-consumers-create-context-memories) |
| Review: AuthorOnly | Memory is forced `Private` to its author and visibility is locked to Private. On Collate it is still created unprocessed; the author's own search includes their pending memories (ADR:2026-10-15-search-admits-a-subjects-own-pending-memories-on-request) |
| Review: AutoPublish | **Not offered.** The memory lifecycle ADRs decide approval, not the harness. The UI's Review control has two options |
| Visibility: Private | `shareConfig.visibility = Private` |
| Visibility: Persona | `shareConfig.visibility = Shared` with a `sharedWith` entry whose principal is the persona. New: `persona` becomes a supported principal type in `contextMemory.sharedPrincipal` (today user, team, domain); Membership is resolved at read time with the same rule as `SubjectContext.hasPersona` (direct, team-inherited, and default persona), so membership changes need no reindex. Changes: `contextMemory.json` principal enum, `ContextMemoryVisibility.isSharedWith`, `ContextMemorySearchVisibility.sharedPrincipalIds`, `ContextMemoryRepository` principal sets, the vector document builder, and the index mapping test. Persona delete removes its grants |
| Visibility: Organization | `Public` |

## 3. Server API

Resource `AgentHarnessResource` at `/v1/agentHarness`. It does not expose the generic entity
`PUT`, `PATCH`, or `DELETE`: every write goes through the endpoints below, so validation cannot be
bypassed.

### 3.1 Admin endpoints

| Endpoint | Behavior |
|---|---|
| `GET /v1/agentHarness/default`, `GET /v1/agentHarness/persona/{personaFqn}` | `{ published, draft?, draftRevision, resolved }`. `published` and `draft` hold the harness's **own** fields; `resolved` is the display view with inherited items (`inherited: true`), guardrail `minLevel`, and event `maxTokenBudget` |
| `GET /v1/agentHarness/personas?limit&after&before` | Paged over personas, like `aiContext/status` today (ADR:2026-10-14-persona-ai-context-status-reads-the-cache-and-pages-like-the-persona-list): persona, user count, harness status (`Published`, `Draft`, `Inherited`), enabled, enabled events, budget used from the cache (never compiling) |
| `PUT /v1/agentHarness/{id}/draft` | Whole own-spec, header `If-Match: <draftRevision>`; 412 on a stale revision. Validates: kind-specific fields, rule validation, guardrail level ≥ default, event budget ≤ default, per-turn ≥ sum of enabled events, positive integers |
| `DELETE /v1/agentHarness/{id}/draft` | Discard the draft |
| `POST /v1/agentHarness/persona/{personaFqn}` | Create a persona harness (unpublished, with an empty draft); 409 if one exists |
| `POST /v1/agentHarness/{id}/publish` | Copy the draft into the entity (one entity version), delete the draft, invalidate the compile cache for the default (all personas) or that persona |
| `PUT /v1/agentHarness/{id}/enabled` | Enable or disable without a draft; takes effect at once (the list toggle) |
| `GET /v1/agentHarness/{id}/versions`, `/versions/{v}` | Publish history; restore writes that version into the draft |
| `POST /v1/agentHarness/{id}/rules/preview` | Rule preview for the rule editor (today's `rules/preview`, moved) |
| `POST /v1/agentHarness/{id}/preview?event=…` | The compiler (§3.2) on the draft or a request body, for the calling admin; `prompt` and `toolInput` in the body for UserPromptSubmit and PreToolUse; uncached |
| `GET /v1/agentHarness/guardrails`, `/skills`, `/surfaces` | Registries and surface status |
| `GET /v1/agentHarness/plugin?target=claude` | The static plugin zip (§5) |

Authorization: every endpoint above calls `authorizer.authorizeAdmin`, as persona AI context does
today (`PersonaResource` writes). Bot tokens are not admins. The UI is read-only for non-admins,
and the UI spec's `EditAgentHarness` maps to "is admin".

### 3.2 The compiler

`POST /v1/agentHarness/compile`, for any authenticated user, about themselves only:

```text
request:  { event: SessionStart | UserPromptSubmit | PreToolUse | PostToolUse,
            prompt?, toolName?, toolInput?, toolOutput? }
response: { harness: {default, persona?}, text, tokens, segments: [{kind, tokens}],
            decision?: {action: allow | warn | deny, reasons[]} }
```

| Event | Selects (within the event's token budget) |
|---|---|
| SessionStart | Company instructions, persona prompt, enabled skills, `alwaysInContext` items |
| UserPromptSubmit | Items in the cached document that match the prompt (term, metric, and asset names and synonyms), then `alwaysInContext` items not yet sent this session |
| PreToolUse | Assets named in the tool input (identifiers matched against table FQNs and names), their cards, and the guardrail decision (§2.3) |
| PostToolUse | Open incidents and failed tests for assets named in the tool output |

Resolution and caching: resolve the persona (§1), merge the published default and persona
`context` into one `PersonaContextDefinition` (default rules first, rule names prefixed `default/`
and `persona/` so names cannot collide, persona `characterBudget` capped at the default's), and
read it through `PersonaContextCache`. The cache key hashes the merged definition, so publishing
the default changes every persona's key. Guardrail and budget values are clamped at resolution
(effective = stricter of default and persona), so raising a default later never leaves a persona
looser. Text is packed default-first; when the budget runs out, persona items are cut before
inherited ones.

`get_context` (MCP) and the `/{id}/context` persona route call the same compiler with
SessionStart.

### 3.3 Hooks over MCP, and MCP access

The plugin's hooks are `mcp_tool` handlers that call the connector's `harness_hook` tool, passing
the host's hook input through `${path}` substitution: `hook_event_name`, `session_id`, `cwd`,
`prompt`, `tool_name`, `tool_input`. The tool runs the compiler for the caller identified by the
connector's sign-in and returns the host's hook output JSON (`additionalContext`, or
`permissionDecision` with a reason at PreToolUse). The server never accepts a harness or persona
name from the client.

`toolAccess` (§2.1) is evaluated at PreToolUse when `tool_name` is `mcp__<server>__<tool>`: the
first matching server entry decides, else `default`. A persona harness can only tighten the
default's entries (clamped at resolution like guardrails). Plugin hooks match `mcp__.*` at
PreToolUse so every MCP call is checked.

`harness_hook` reports its own failures with `isError`, which the host treats as non-blocking
(fail open). Hard enforcement is the host's floor: `GET /v1/agentHarness/managedSettings` returns
the `allowedMcpServers` / `deniedMcpServers` JSON for Claude Code managed settings and the
`mcp_servers` allowlist for Codex `requirements.toml`, built from the default harness's `Block`
and `Allow` entries. The Surfaces tab shows it to copy.

The design doc's §9.10 explains why this makes the plugin set-and-forget.

## 4. MCP

- `harness_hook` tool (§3.3); its description tells the model it is for hooks, not for direct use.

- Static server `instructions`, set once in `McpServer.buildServer`: OpenMetadata is the context
  layer; call `get_context` at the start of a task; cite what it returns. Per-caller content is
  never in `instructions`, because the stateless server is built once.
- `get_context` tool: the compiler at SessionStart for the caller, plus enabled skills. The
  existing persona context tool is replaced by it.
- `PersonaSearchScope` reads the merged definition's search rules.

## 5. Plugin and surfaces

### 5.1 Artifacts

The plugin carries no harness content, so one package serves every employee and publishing never
requires a re-upload.

| Target | Artifact |
|---|---|
| Claude | `openmetadata-<host>.zip`: `.claude-plugin/plugin.json`, `.mcp.json` (the server's `/mcp` URL), `hooks/hooks.json` (`mcp_tool` hooks that call the connector's `harness_hook` tool with the host's hook input, so they use the connector's sign-in: no token, script, or binary). No `bin/`. Hooks load in Claude Code and Cowork and are ignored in chat |
| ChatGPT | No file. The panel shows the connector URL and OAuth details to enter, and the Enterprise steps |
| Codex & Claude Code | Copyable commands: `claude plugin install` from the zip, `codex mcp add openmetadata --url …`, and the managed-settings snippets for MDM. `install.sh` and `om-context` are track D |

The download is admin-only. The zip is built per request from the server URL and the plugin
version; it contains no secrets.

Signing: the per-deployment Ed25519 key (§12, decision 5) is built in track D with `om-context`,
which is the only verifier. Claude and Codex do not verify plugin signatures, so a signature in
track A would show assurance nothing checks.

### 5.2 Corrections to the UI spec's plugin panel

- The "Includes" list shows what is in the zip: Skill, MCP, Hooks. Instructions and guardrails are
  listed as "served by OpenMetadata at run time".
- `CLAUDE.md`, `AGENTS.md`, and `policy.json` are not in the plugin.

### 5.3 Surface status

`McpUsageRecorder` already records a client label per tool call from the User-Agent
(`AuthEnrichedMcpContextExtractor`). A surface is **Connected** when a call with its label was
recorded in the last 7 days, and **Not connected** otherwise. The label is self-declared, so the
status is a usage signal, not attestation. "Pending approval" waits for pre-approved OAuth clients
(design doc §11).

## 6. UI

Paths below are relative to `openmetadata-ui/src/main/resources/ui/`. New files follow
`DEVELOPER_HANDBOOK.md` (one stem, role suffixes) and use `@openmetadata/ui-core-components`
directly, with no wrappers.

### 6.1 Routing, navigation, permissions

| Change | Where |
|---|---|
| `ROUTES.CONTEXT_CENTER_AGENT_HARNESS` (`/context-center/agent-harness`), `/default`, and `/persona/:fqn`. The `persona/` segment keeps a persona named `default` from colliding with the company default | `src/constants/constants.ts` |
| Routes, lazy pages, and `Navigate` redirects from `_AI_CONTEXT` and `_AI_CONTEXT_DETAIL` (keep the persona FQN) | `src/components/AppRouter/ContextCenterRouter/ContextCenterRouter.tsx` |
| `getAgentHarnessListPath()`, `getAgentHarnessPath(fqn, tab?)`; old helpers return the new paths | `src/utils/ContextCenterClassBase.ts` |
| Sidebar: `SidebarItem.AGENT_HARNESS`, title `label.agent-harness`, same position; a new `isNew` flag next to `isBeta` rendering a core `Badge` with `label.new`, with a TODO naming the release that removes it | `src/constants/LeftSidebar.constants.ts`, `src/enums/sidebar.enum.ts`, `src/interface/sidebar.interface.ts`, `LeftSidebarItem.component.tsx` |
| Read-only mode for non-admins (`useAuth().isAdminUser`, as today), matching the server's `authorizeAdmin` | Editor and list pages |
| `?tab=` sync with `useSearchParams` | Editor page |

### 6.2 Files

```text
src/rest/AgentHarnessAPI.ts                      # §3 endpoints
src/pages/ContextCenterPage/AgentHarnessPage/
  AgentHarnessListPage.tsx                       # PageLayout + PageLayout.Header
  AgentHarnessEditorPage.tsx                     # breadcrumb, header, tabs, rail
src/components/ContextCenter/AgentHarness/
  AgentHarness.types.ts                          # view models over generated types
  AgentHarness.utils.ts                          # visibleItems(showInherited), allowedLevels(minLevel),
                                                 # validateBudgets(spec), additionsSummary(harness), usersLabel
  useAgentHarnessDraft.ts                        # load own spec, local edits, debounced PUT with
                                                 # If-Match, dirty flag, publish
  AgentHarnessList/
    DefaultHarnessCard.tsx                       # header row + 5 summary cells (auto-fit grid)
    PersonaHarnessTable.tsx                      # 7 columns, row click, Enabled toggle (stopPropagation)
    NewPersonaHarnessModal.tsx                   # persona picker: personas without a harness
  AgentHarnessEditor/
    HarnessHeader.tsx                            # version button, preview toggle, publish
    InheritanceStrip.tsx                         # chain chips + Show inherited; default banner
    HarnessSettingsBar.tsx                       # enabled, char budget, per-turn budget, TTL, freshness
    ContextTab.tsx  SourceCard.tsx  SourcePickerDrawer.tsx
    DeliveryTab.tsx                              # 5 event rows
    InstructionsTab.tsx                          # company (locked on persona), persona prompt, skills
    GuardrailsTab.tsx                            # Off/Warn/Block with minimum; MCP access list
    MemoryTab.tsx
    SurfacesTab.tsx  PluginPanel.tsx  SurfaceMatrix.tsx
    CompiledContextRail.tsx                      # budget bar, Preview as pills, payload
```

Reused without forks (moved, not copied, per `single-source.md`): `ContextRuleCard`,
`ContextRuleEditor` (its rule preview call moves to `/v1/agentHarness/{id}/rules/preview`),
`RuleQueryBuilderField`, `VersionHistoryDrawer` (rebased on harness versions), and the prompt field of
`ContextPromptCard` move from `components/ContextCenter/PersonaAIContext/` into
`components/ContextCenter/AgentHarness/`. `ContextRuleCard` gains an `inherited` prop (subtle
background, "Inherited · locked" badge, no actions). After the redirect ships,
`ContextCenterAIContextPage`, `ContextCenterAIContextDetailPage`, `PersonaAIContext.component`, and
`ContextPreviewModal` are deleted; grep the Collate repository for imports before deleting exports.

### 6.3 Core components per spec element

| Spec element | Core component |
|---|---|
| Tabs with badges, scrolling strip | `Tabs`, `TabList`, `Tab`, `TabPanel` |
| Toggles (`role="switch"`, 36×20) | `Toggle` |
| Off / Warn / Block, Review, Visibility, Install on, Preview as | `ButtonGroup` + `ButtonGroupItem` (radiogroup semantics; verify `role="radiogroup"` and per-item disabled state, else `RadioGroup` + `RadioButton`) |
| Status and "From default" badges | `Badge` |
| Persona table | `Table` |
| Picker drawer | `SlideoutMenu` |
| Budget bars | `ProgressBarBase` (list); a segmented bar built from tokens (rail) |
| Number fields | `NumberInput` |
| Prompt and instructions | `TextArea` |
| Breadcrumb | `Breadcrumbs` |
| Skeletons | `Skeleton` |
| Unsaved-changes confirm | `Modal` / `Dialog` |

Colors come only from tokens; the rail's segment colors use the brand and cyan tokens the spec
names. No `antd`, no new `.less`.

### 6.4 Draft model in the client

- The page loads `{published, draft, resolved}`. Edits change the **own** spec; `resolved` is only
  for display, so no edit can address an inherited item.
- `useAgentHarnessDraft` sends the whole own-spec with `PUT .../draft` and `If-Match` (debounced
  800 ms). On 412 it reloads and shows "Changed by someone else".
- The preview rail calls `POST .../preview` with the local spec, debounced, so it shows unsaved
  edits.
- Publish is enabled when a draft exists. Navigating away while a save is pending opens the
  confirm dialog.

### 6.5 Memories link

`MemoriesView` accepts `?source=agent` and applies a `sourceType = AgentCapture` filter (new; the
view only reads `?memory=` today). This needs the `AgentCapture` source type from the design doc
§8.1; until then the link opens Memories unfiltered.

### 6.6 i18n and accessibility

All copy from the UI spec becomes `label.*` / `message.*` keys in `en-us.json`, then `yarn i18n`,
with placeholders kept in the other 20 locales. Toggles expose `role="switch"` and `aria-checked`;
segmented controls `role="radiogroup"`; tabs `role="tablist"`. Visible focus rings come from the
core components.

## 7. Tests

What each test proves, by layer. New ITs and classes are recorded in
`.github/java-tests/impact-map.json`.

### 7.1 Server unit tests

| Test | Proves |
|---|---|
| `HarnessResolverTest` | Merged definition = default rules then persona rules, names prefixed; a disabled or missing persona harness resolves to the default; clamping when the default gets stricter after the persona was saved; persona budget capped at the default's; display view flags inherited items and `minLevel` |
| `HarnessDraftValidatorTest` | Rejects guardrail below minimum, event budget above the default's, per-turn below the enabled sum, kind-specific fields on the wrong kind, invalid rules |
| `GuardrailEvaluatorTest` | Deprecated asset → deny; PII column without masking → deny; open incident → warn; certified asset → allow; Off → no decision; evaluators read the entity, not the cache |
| `CompileSelectorTest` | Each event selects per §3.2 and stays within its budget; segments sum to tokens; persona items are cut before inherited ones |
| `PluginBuilderTest` | Zip has the manifests, `.mcp.json` with the server URL, hooks; no skill; no `bin/`; no harness content |

### 7.2 Integration tests (`openmetadata-integration-tests`)

| IT | Proves |
|---|---|
| `AgentHarnessResourceIT` | Default seeded once; create persona harness (409 on duplicate); draft writes leave the published harness and compile output unchanged; stale `If-Match` → 412; publish makes one version and changes compile output; restore a version into the draft; personas list pages and shows personas without a harness as `Inherited`; persona delete removes its harness |
| `AgentHarnessAuthorizationIT` | Non-admin and bot tokens get 403 on every admin endpoint; any user can compile for themselves; compile never returns entities the caller cannot see |
| `AgentHarnessCompileIT` | Default + persona merge; publishing the default changes a persona's compile output; PreToolUse on a deprecated table → deny; on a table with an open incident → warn; budgets respected per event; `get_context` over MCP returns the same text as SessionStart; `harness_hook` returns valid hook JSON per event; an `mcp__x__y` call blocked by `toolAccess` → deny, and a persona cannot loosen the default's block; a client-sent persona name is ignored |
| `AgentHarnessMigrationIT` | Personas with contexts become published persona harnesses with the same `context`; `contextDefinition` is gone from persona rows and versions; persona versions still read; rerun changes nothing; compiled context per persona unchanged |
| `PersonaAIContextIT` (existing, 19 tests) | Cases that still apply (caller visibility, budget truncation, search scope, membership authorization) move to `AgentHarnessCompileIT`; endpoint-shape cases are deleted with the endpoints |
| `PersonaMemoryVisibilityIT` | A memory shared with a persona is visible to direct, team-inherited, and default-persona members in get, list, and search, and invisible to others; removing a user from the persona removes access |
| `AgentHarnessPluginIT` | Admin downloads the zip; non-admin 403; the zip contains no prompt or rule text |

Run them with the `java-affected-tests` skill in the CI lane and engine before the PR (the merge
queue runs ITs; PR CI does not).

### 7.3 UI unit tests (Jest)

| Test | Proves |
|---|---|
| `AgentHarness.utils.test.ts` | Inheritance display filter, allowed guardrail levels, budget validation, additions summary |
| `useAgentHarnessDraft.test.ts` | Sends only the own spec with the current revision; 412 reloads; dirty state; publish enables and resets |
| `PersonaHarnessTable.test.tsx` | Seven columns, badges per status, row click navigates, toggle does not |
| `DeliveryTab.test.tsx`, `GuardrailsTab.test.tsx`, `MemoryTab.test.tsx`, `InstructionsTab.test.tsx`, `ContextTab.test.tsx` | Read-only state for non-admins; inherited items locked; Show inherited hides them; roles and keyboard operation |
| `SurfacesTab.test.tsx`, `PluginPanel.test.tsx` | Install-on target switches steps, artifact, includes, actions; done labels revert after about 1.8 s (fake timers) |
| `CompiledContextRail.test.tsx` | Segments sum to the used label; pills switch payload |
| Router and sidebar tests | Redirects from the old URLs; sidebar label and `isNew` badge |

### 7.4 Playwright

| Spec | Covers |
|---|---|
| `AgentHarness.spec.ts` | The spec's E2E: create a persona harness → add a rule → publish → generate the Claude plugin; assert the downloaded zip's file list; preview shows the new rule's asset |
| `AgentHarnessInheritance.spec.ts` | Inherited items locked; Show inherited; guardrail minimum disabled options |
| `AgentHarnessPermissions.spec.ts` | A non-admin sees every screen read-only; primary actions hidden |
| `AgentHarnessNavigation.spec.ts` | Old AI Context URLs redirect; `?tab=` survives reload; unsaved-changes dialog |
| Visual regression | Replace the `page-context-ai-context*` snapshots and update `playwright/utils/visualData.ts` |
| Layout checks | No overflow at 1024, 1280, 1440 px |

The five `PersonaAIContext*.spec.ts` files and `playwright/utils/personaAIContext.ts` are rewritten
onto the new pages, not kept beside them.

### 7.5 Local end-to-end check (before the PR)

On a local stack (memory: a unique compose project, never `run_local_docker.sh`), with sample data:

1. Upgrade from a database that has persona AI contexts; confirm the migration and that the
   personas' compiled context is unchanged.
2. In the UI: create a persona harness, add a rule, set a guardrail, publish, download the plugin.
3. Claude Code: install the zip with `claude --plugin-dir`, connect the connector once (`/mcp`),
   and check that a prompt naming a glossary term pulls its definition, a tool call naming a
   deprecated table is denied, and a call to an MCP server the harness blocks is denied. Then change
   the persona prompt and an MCP access entry in the UI, publish, and confirm the next turn follows
   **without reinstalling**. Record the host's caps (hook output is capped at 10,000 characters) and
   what happens when OpenMetadata is unreachable.
4. Claude Desktop or claude.ai with the connector: `get_context` returns the persona's context.
5. Codex: `codex mcp add`; `get_context` works.

## 8. Observability

Metrics: compile latency per event, compile cache hit rate, preview latency, publish count,
guardrail decisions per guardrail and level.

## 9. Tracks

| Track | Content | Depends on |
|---|---|---|
| **A, this plan** | Entity and draft, migration and removals, admin API, compiler with guardrails, `get_context`, static plugin, surfaces, persona memory principal, UI | — |
| B | Answer harness: `start_answer` … `finish_answer`, SQL Studio / host-connector execution, citations, traces, query cost ceiling, `validate_query` enforcement (design doc §7) | A |
| C | Agent memory capture: `AgentCapture`, `capture`, `remember`, feedback (design doc §8) | A (memory settings) |
| D | `om-context` binary, install script, plugin signing and verification, URL marketplace (design doc §9.5) | A |
| E | Conversation visibility (if adopted) | B, C |

The UI ships against A. Settings whose effect lands in another track (memory capture, the query
cost ceiling) are stored in A and show "takes effect when …" text until then.

## 10. Build order

Everything lands in one branch and one release: the migration removes the old endpoints, so the
API and the UI ship together. Commits follow this order, and each ends with its tests green.

| # | Commit | Done when |
|---|---|---|
| 0 | Amend the design doc; ADR "Agent harness is an entity; persona harnesses extend the default", recording §0 and §12; supersede note on ADR:2026-10-14-persona-ai-context-status-reads-the-cache-and-pages-like-the-persona-list | Docs committed |
| 1 | Spec: `agentHarness.json`, guardrail, skill, compile request and response types; `make generate` | Generated Java, Python, TS compile |
| 2 | Tables, repository, resolver, draft validator, admin API, seeded default | §7.1 resolver and validator tests; `AgentHarnessResourceIT`, `AuthorizationIT` |
| 3 | `toolAccess` in the schema, compiler, guardrail and tool-access evaluators, `get_context`, `harness_hook`, managed-settings generator, static MCP instructions, search scope on the merged definition | `CompileSelectorTest`, `GuardrailEvaluatorTest`, `AgentHarnessCompileIT` |
| 4 | Migration, removal of `contextDefinition` and the persona AI context endpoints, caller moves | `AgentHarnessMigrationIT`; moved persona context cases green |
| 5 | Plugin builder and surface status | `PluginBuilderTest`, `AgentHarnessPluginIT` |
| 6 | `persona` memory share principal | `PersonaMemoryVisibilityIT` |
| 7 | UI: routes, redirects, sidebar, REST client, list page | Jest for list and router |
| 8 | UI: editor shell, settings bar, Context, Delivery, preview rail | Jest per tab |
| 9 | UI: Instructions, Guardrails, Memory, Surfaces; delete the old AI Context UI | Jest; Playwright suite (§7.4) |
| 10 | Local end-to-end check (§7.5) | Recorded results |

## 11. Open decisions

None open. The decisions taken on 2026-11-18 are in §12.

## 12. Decisions

| # | Decision | Effect in this plan |
|---|---|---|
| 1 | Keep the memory approval ADRs | No AutoPublish review mode; approval follows the deployment's memory lifecycle (§2.4) |
| 2 | Add `persona` as a memory share principal | "Persona" visibility shares with the persona's members (§2.4, commit 6) |
| 3 | No compatibility layer; migrate in one release | `contextDefinition` and the persona AI context endpoints are removed with the migration; API and UI ship together (§2.2, §10) |
| 4 | Collate chat is out of scope | It keeps its own architecture; `getForCaller(persona, …)` keeps its signature and returns the merged context, the same content on upgrade (§2.2) |
| 5 | Per-deployment signing key | Ed25519 pair per deployment, built in track D with its verifier, `om-context` (§5.1) |
