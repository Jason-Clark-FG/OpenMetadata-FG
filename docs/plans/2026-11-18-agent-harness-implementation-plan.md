# Agent Harness implementation plan

- **Status:** Draft for review
- **Date:** 2026-11-18
- **Inputs:** `docs/plans/2026-11-18-default-context-harness.md` (server design, the "design doc"
  below); the Agent Harness UI specification and its clickable prototype (`Agent Harness.dc.html`),
  "the UI spec" below
- **Scope:** spec, server, MCP, migrations, UI, plugin generator, tests. The `om-context` binary
  and the answer harness execution path are separate tracks (§9)

## 1. What the UI spec changes in the design doc

The UI spec is the product decision. Where it differs from the design doc, the UI spec wins, and
the design doc is amended in the same PR series (§10, step 0).

| Topic | Design doc | UI spec (adopted) | Consequence |
|---|---|---|---|
| Harness storage | Extend `persona.contextDefinition`; no new entity | A harness with its own version, draft, publish, and status (`/v1/agentHarness`) | New `agentHarness` entity (§2). Persona context definitions migrate into it |
| Inheritance | A persona's rules and prompt replace the default's; only delivery, guardrails and memory inherit | A persona harness **extends** the default: inherited items are read-only and always present; the persona adds items; guardrails can only get stricter; event budgets stay within the default's | Server-side resolution returns a merged harness with `inherited` and `minLevel` flags; writes to inherited items are rejected (§3) |
| Resolution | active persona → default persona → organization default → built-in | A user without a persona harness gets the default; one with a persona harness gets default + persona | Same chain; the result is now always "default, plus the persona's additions when present" |
| Events | 4 (no PostToolUse); budgets 2,000 / 1,500 / 800 | 5 events: SessionStart 6,000, UserPromptSubmit 4,000, PreToolUse 2,000, PostToolUse 1,000, Stop 1,000; per-turn budget 20,000 | Add `PostToolUse` (DQ issues, freshness on query output). Validation: per-turn budget ≥ sum of enabled event budgets |
| Sources | Rules only (entity type + query filter) | Explicitly picked glossary terms, metrics, articles, plus asset rules | `sources.glossaryTerms`, `metrics`, `articles` as reference lists, compiled like `alwaysInContext` items, plus `assetRules` (existing rule shape) |
| Guardrails | tag/status → warn/deny/none | Server-registered guardrail ids with Off / Warn / Block; seeds: Prefer certified tables (Warn), Block deprecated assets (Block), PII columns need masking (Block), Flag open incidents (Warn), Query cost ceiling (Off) | Guardrail registry in the server (§2.3) |
| Memory | `requireReview`, `defaultVisibility` (memory visibility enum) | Capture, link to assets, deduplicate; review AutoPublish / StewardReview / AuthorOnly; visibility Private / Persona / Organization | Mapping in §2.4. **AutoPublish is dropped** to keep ADR:2026-10-09-data-consumers-create-context-memories; review is Steward review or Author only. "Persona" visibility adds `persona` as a memory share principal |
| Plugin contents | Skills, `.mcp.json`, hooks; no `bin/` | Adds instructions as `CLAUDE.md · AGENTS.md` and guardrails as `policy.json` | Claude plugins do not load `CLAUDE.md` or `policy.json` (§5.2). Instructions ship as an always-on skill plus MCP server `instructions`; `policy.json` is read by `om-context` and is informational elsewhere. The UI labels stay; the file list in the panel is corrected |
| Preview | `GET .../preview?event=` | "The preview reflects unsaved draft state" | `POST .../preview` with an optional draft body (§3) |

## 2. Data model

### 2.1 `agentHarness` entity

New schema `openmetadata-spec/.../entity/context/agentHarness.json` (`@om-entity-type`), with
`EntityInterface`, versioned like every entity. One row per persona harness and exactly one with
`kind: default` (name `CompanyDefault`, seeded).

```text
AgentHarness
  id, name, fullyQualifiedName, displayName, description, version, updatedAt, updatedBy, deleted
  kind: Default | Persona
  persona: EntityReference            # kind=Persona only; unique
  enabled: boolean
  status: Published | Draft            # Inherited / NotConfigured are computed for the list (§3)
  published: HarnessSpec               # what the compiler and surfaces use
  draft: HarnessSpec | null            # PATCH writes here; publish promotes it
  publishedVersion: string             # "1.3"; the entity version at publish time
  compiledAt, cacheState, lastError    # read-only, from the compile cache (as persona context today)

HarnessSpec
  charBudget (400000), perTurnTokenBudget (20000), cacheTtlMin (30)
  sources: { glossaryTerms: Ref[], metrics: Ref[], articles: Ref[], assetRules: ContextRule[] }
  events:  [{ id: HookId, enabled, tokenBudget }]          # HookId = SessionStart | UserPromptSubmit | PreToolUse | PostToolUse | Stop
  instructions: { company?: markdown, persona?: markdown }  # company only on Default; persona only on Persona
  skills:  [{ name, enabled }]
  guardrails: [{ id, level: Off | Warn | Block }]
  memory: { capture, linkAssets, dedup, review: StewardReview | AuthorOnly,
            visibility: Private | Persona | Organization }
  answerHarness: { … }                                     # design doc §7.3; not edited by this UI yet
```

`ContextRule` is the existing `personaContextDefinition.contextRule` type, reused by `$ref`, so the
rule builder and rule validation (`PersonaContextRuleValidation`) carry over unchanged.

Why a draft inside the entity instead of entity versions alone: every OpenMetadata write creates a
version, but the UI needs edits that do not affect agents until **Publish**. The compiler, the
surfaces, and MCP read only `published`; the preview reads `draft` when present.

### 2.2 Migration from persona AI context

In the current development migration (`bootstrap/sql/migrations/native/<version>/`, MySQL and
Postgres, idempotent, Java data migration because FQN hashes are computed in Java):

1. Seed `CompanyDefault` with the built-in defaults (§5.2 of the design doc, with the UI spec's
   budgets).
2. For each persona with a non-empty `contextDefinition`: create a `kind: Persona` harness whose
   `published` spec carries the rules (as `assetRules`, including glossary, metric, and page rules
   as they are), the prompt (as `instructions.persona`), `characterBudget`, `cacheTtlMinutes`, and
   `enabled`. Status `Published`, so behavior does not change on upgrade.
3. Remove `persona.contextDefinition` from the schema and its stored JSON in the same migration,
   and remove the `/v1/personas/{id}/aiContext*`, `/v1/personas/aiContext/status`, and
   `/v1/personas/{id|name}/context` endpoints in the same release. No compatibility layer: the UI
   moves to `/v1/agentHarness` in the same release, and the MCP `get_persona_context` tool reads the
   resolved harness.

Guard test: a migration IT that seeds personas with contexts on the old schema, runs the migration
twice (idempotent), and asserts the harnesses and that each persona's compiled context is
unchanged when read through the harness.

Before removing the endpoints, search the Collate repository for callers of the removed REST paths,
`PersonaContextDefinition`, and `persona.getContextDefinition()`, and land the Collate change with
this PR (the PR's Compatibility check builds Collate against it).

### 2.3 Guardrail and skill registries

- **Guardrails** are code-defined (`GuardrailDefinition`: id, name, description, default level,
  evaluation point `PreToolUse` or `PostToolUse`, evaluator). Seeds per the UI spec. `GET
  /v1/agentHarness/guardrails` lists them for the UI. Evaluators live with the compile pipeline
  (design doc §6.2) and the answer harness validation (§7.2).
- **Skills** are server-registered: built-in (`openmetadata-data-answers`, `find-context`,
  `metric-definition`, …) plus skills exported from article rules (`exportAsSkill`, design doc
  §5.3). `GET /v1/agentHarness/skills` lists them for the **+ Add skill** picker.

### 2.4 Memory settings mapping

| UI value | `contextMemory` effect |
|---|---|
| Capture | `capture` endpoint and `remember` tool accept writes for members |
| Link to assets used | `primaryEntity` and `relatedEntities` from the answer plan (design doc §7.5) |
| Deduplicate | Run `SemanticMemoryDuplicateFinder` before insert (already default; the toggle allows turning it off for testing only) |
| Review: StewardReview (default) | Memory starts unapproved; existing review flow (today's behavior) |
| Review: AuthorOnly | Memory stays `Private` to its author; no review, never shared |
| Review: AutoPublish | **Not offered.** ADR:2026-10-09-data-consumers-create-context-memories stands: a non-admin's memory starts unapproved and only a review approves it. The UI's Review control has two options, Steward review and Author only |
| Visibility: Private | `shareConfig.visibility = Private` |
| Visibility: Persona | `shareConfig.visibility = Shared` with a `sharedWith` entry whose principal is the persona. New: `persona` becomes a supported principal type in `contextMemory.sharedPrincipal` (today user, team, domain); `ContextMemoryVisibility`, `ContextMemorySearchVisibility` (search filter), and share validation accept it, and the persona's assigned users read the memory |
| Visibility: Organization | `Public` |

## 3. Server API

Resource `AgentHarnessResource` at `/v1/agentHarness`, extending `EntityResource` so list, get,
versions, and authorization follow the standard pattern.

| Endpoint | Behavior |
|---|---|
| `GET /v1/agentHarness` | `{ default: HarnessSummary, personas: HarnessSummary[] }`. Personas **without** a harness are included with `status: Inherited` (they use the default). Summary carries user count, additions summary, enabled events, context budget used (from the compile cache, never compiling), status |
| `GET /v1/agentHarness/{id\|default}` | Resolved harness: default items merged into a persona harness with `inherited: true`, guardrail `minLevel`, event `maxTokenBudget`. Query `view=draft\|published` |
| `PATCH /v1/agentHarness/{id}` | JSON Patch against the draft. Rejects with 400: any op on an inherited item, a guardrail level below `minLevel`, an event budget above the default's, per-turn budget below the sum of enabled event budgets, non-positive integers |
| `PUT /v1/agentHarness/personas/{personaFqn}` | Create a persona harness in draft (the "+ New persona harness" flow); 409 if one exists |
| `POST /v1/agentHarness/{id}/publish` | Promote draft, bump `publishedVersion`, invalidate the compile cache, record a change event |
| `GET /v1/agentHarness/{id}/versions` | Standard entity versions; restore = PATCH the draft from a version |
| `POST /v1/agentHarness/{id}/preview?event=…` | Body: optional draft spec. Returns `{ budget: {used, total, segments[]}, payload }` for SessionStart, UserPromptSubmit, PreToolUse; compiles against the caller (or `asUser`, admin only) without caching |
| `GET /v1/agentHarness/guardrails`, `/skills` | Registries (§2.3) |
| `POST /v1/agentHarness/plugin?target=claude\|chatgpt\|cli` | Claude: zip. ChatGPT: connector manifest JSON. CLI: install command and MDM snippet. Built from the published default plus all published persona harnesses (§5) |
| `GET /v1/agentHarness/plugin/marketplace.json` | URL marketplace for Claude Code managed settings (design doc §9.3) |
| `GET /v1/agentHarness/surfaces` | Surface rows with channel support and status (§5.3) |

Authorization: resource `agentHarness` with standard operations. The UI spec's `EditAgentHarness`
is `EditAll` on this resource; `ViewAll` reads. Seed: admins only (matches persona context editing
today).

Compile path changes (design doc §6): the turn compiler reads the **resolved published** harness;
`PersonaContextBuilder` and `PersonaContextCache` are keyed by harness id and spec hash instead of
persona id.

The Collate chat path is out of scope: it keeps its own architecture and decides separately whether
to adopt the harness. Whatever server-side Java API it calls today for a persona's compiled context
(`PersonaContextCache.getForCaller(persona, …)` and the persona prompt) keeps its signature and
returns the same content, now read from that persona's published harness.

## 4. MCP

- `instructions` rendered from the resolved harness (company instructions + persona instructions).
- Tools: `get_context`, `start_answer` / `validate_query` / `run_query` / `report_result` /
  `finish_answer`, `remember` (design doc §7, §8). This plan covers `get_context` and `remember`;
  the answer-session tools are track B (§9).
- Client identification for the Surfaces status: record `clientInfo.name` and version from MCP
  `initialize` and the OAuth client registration on each session.

## 5. Plugin generator and surfaces

### 5.1 Artifacts

| Target | Artifact | Built from |
|---|---|---|
| Claude | `openmetadata-claude-plugin-<ver>.zip`: `.claude-plugin/plugin.json`, `.mcp.json`, `skills/openmetadata-instructions/SKILL.md` (company instructions; persona prompt is fetched at run time), enabled skills, `commands/`, `hooks/hooks.json`, `policy.json`. No top-level `bin/` | Published default + persona harnesses |
| ChatGPT | `openmetadata-chatgpt-connector.json`: server URL, OAuth metadata URL, tool list, instructions | Published default |
| Codex & Claude Code | `curl -fsSL https://<om>/downloads/om-context/install.sh \| sh -s -- --codex --claude-code --with-hooks`, and an MDM snippet (managed settings JSON for Claude Code; `managed_config.toml` payload for Codex) | Server URL, marketplace URL |

Generation is deterministic: the same published inputs produce byte-identical archives (sorted
entries, fixed timestamps), so "signed" and the version are stable.

Signing uses a **per-deployment key**: an Ed25519 key pair generated on first use, the private key
stored encrypted through the secrets manager like other server secrets, the public key served at
`/v1/agentHarness/plugin/signing-key` and shown on the Surfaces tab. Rotation generates a new pair,
re-signs on the next generation, and keeps the previous public key listed for one rotation so
installed plugins can still be verified.

### 5.2 Corrections to the UI spec's plugin panel

- `CLAUDE.md · AGENTS.md`: Claude plugins load skills, commands, agents, hooks, MCP servers, LSP,
  output styles, and settings, not an instruction file. Show "Instructions · `skills/openmetadata-instructions` + MCP instructions" for Claude, and `AGENTS.md` only for the CLI target (written by `om-context sync`).
- `policy.json`: enforced by the server (compile, `validate_query`) and by `om-context` hooks; not
  by the host. The panel text says so.

### 5.3 Surface status

| Status | Rule |
|---|---|
| Connected | At least one MCP session from the surface's client in the last 7 days |
| Pending approval | The surface's OAuth client is registered but not pre-approved, or the admin started a rollout step that has not seen a session yet |
| Not connected | Neither |

## 6. UI

Paths below are relative to `openmetadata-ui/src/main/resources/ui/`. New files follow
`DEVELOPER_HANDBOOK.md` (one stem, role suffixes) and use `@openmetadata/ui-core-components`
directly, with no wrappers.

### 6.1 Routing, navigation, permissions

| Change | Where |
|---|---|
| `ROUTES.CONTEXT_CENTER_AGENT_HARNESS` (`/context-center/agent-harness`) and `_AGENT_HARNESS_DETAIL` (`/context-center/agent-harness/:fqn`; `default` is a reserved value) | `src/constants/constants.ts` |
| Routes, lazy pages, and `Navigate` redirects from `_AI_CONTEXT` and `_AI_CONTEXT_DETAIL` (keep the persona FQN) | `src/components/AppRouter/ContextCenterRouter/ContextCenterRouter.tsx` |
| `getAgentHarnessListPath()`, `getAgentHarnessPath(fqn, tab?)`; old helpers return the new paths | `src/utils/ContextCenterClassBase.ts` |
| Sidebar: `SidebarItem.AGENT_HARNESS`, title `label.agent-harness`, same position; a new `isNew` flag next to `isBeta` rendering a core `Badge` with `label.new`, with a TODO naming the release that removes it | `src/constants/LeftSidebar.constants.ts`, `src/enums/sidebar.enum.ts`, `src/interface/sidebar.interface.ts`, `LeftSidebarItem.component.tsx` |
| `ResourceEntity.AGENT_HARNESS = 'agentHarness'`; read-only mode when the caller lacks `EditAll` (replaces today's `isAdminUser` gate) | `src/enums/permissions.enum.ts`, read through `usePermissionProvider().getResourcePermission` |
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
  useAgentHarnessDraft.ts                        # load, local draft, JSON Patch diff, dirty flag,
                                                 # navigation blocker, publish
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
    GuardrailsTab.tsx                            # Off/Warn/Block with minimum
    MemoryTab.tsx
    SurfacesTab.tsx  PluginPanel.tsx  SurfaceMatrix.tsx
    CompiledContextRail.tsx                      # budget bar, Preview as pills, payload
```

Reused without forks (moved, not copied, per `single-source.md`): `ContextRuleCard`,
`ContextRuleEditor`, `RuleQueryBuilderField`, `VersionHistoryDrawer`, and the prompt field of
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

- The page loads the resolved harness (`view=draft` when a draft exists). Edits change a local
  copy; `useAgentHarnessDraft` debounces a JSON Patch of **only non-inherited paths** to `PATCH`
  (500 ms), so the server draft survives reloads and the preview can read it.
- The preview rail sends the local draft in the `POST .../preview` body, so it reflects edits
  before the PATCH lands.
- Publish is enabled when the server draft differs from published. Navigating away with an
  unsaved local change opens the confirm dialog.

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
| `HarnessResolverTest` | Persona harness = default items (flagged `inherited`) + persona additions; a disabled persona harness resolves to the default; guardrail `minLevel` equals the default's level; event `maxTokenBudget` equals the default's budget |
| `HarnessPatchValidatorTest` | 400 for a patch touching an inherited path, a guardrail below minimum, an event budget above the default's, a per-turn budget below the enabled sum, non-positive integers; 200 for valid additions |
| `PluginGeneratorTest` | Claude zip has the manifest, `.mcp.json` with the server URL, enabled skills only, hooks, `policy.json`, and **no top-level `bin/`**; two runs on the same inputs produce identical bytes; ChatGPT manifest validates against its JSON schema |
| `GuardrailRegistryTest` | Seed ids and default levels match the UI spec |
| `MemorySettingsMapperTest` | Each review and visibility value maps per §2.4; AutoPublish is rejected |
| `PluginSignerTest` | Signature verifies with the published key; rotation keeps the previous key verifiable |

### 7.2 Integration tests (`openmetadata-integration-tests`)

| IT | Proves |
|---|---|
| `AgentHarnessResourceIT` | Default seeded once; create persona harness (409 on duplicate); PATCH writes draft only (published unchanged, compile output unchanged); publish bumps version and changes compile output; versions and restore-to-draft; list returns personas without a harness as `Inherited` |
| `AgentHarnessAuthorizationIT` | Non-admin: read allowed per `ViewAll`, every write 403; admin can write; preview as another user is admin-only and never returns entities that user cannot see |
| `AgentHarnessInheritanceIT` | Writes to inherited items rejected over REST, not only in the UI; guardrail minimum enforced; changing the default changes every persona's resolved harness after publish |
| `AgentHarnessPreviewIT` | Preview with a draft body reflects it without persisting; SessionStart, UserPromptSubmit, PreToolUse payloads stay within their budgets; segments sum to `used` |
| `AgentHarnessMigrationIT` | Personas with contexts become published persona harnesses with the same rules, prompt, budget, and TTL; `contextDefinition` is gone from persona JSON; running the migration twice changes nothing; each persona's compiled context is unchanged |
| `PersonaAIContextIT` (existing, 19 tests) | Rewritten as harness tests: cases that still apply (caller visibility, budget truncation, search scope, membership authorization) move to `AgentHarnessResourceIT` and `AgentHarnessPreviewIT`; endpoint-shape cases are deleted with the endpoints |
| `PersonaMemoryVisibilityIT` | A memory shared with a persona is visible to its members in get, list, and search, and invisible to others; removing a user from the persona removes access |
| `AgentHarnessPluginIT` | `target=claude` zip, `chatgpt` manifest, `cli` command; `marketplace.json` lists the plugin; published-only inputs (a draft never leaks into the plugin) |
| `McpHarnessInstructionsIT` | MCP `instructions` contain the company instructions and the caller's persona prompt; a user without a persona harness gets the default's only |
| `AgentHarnessSurfacesIT` | A session from a known client marks its surface Connected; none marks Not connected |

Run them with the `java-affected-tests` skill in the CI lane and engine before each PR (the
merge queue runs ITs; PR CI does not).

### 7.3 UI unit tests (Jest)

| Test | Proves |
|---|---|
| `AgentHarness.utils.test.ts` | Inheritance display filter, allowed guardrail levels, budget validation, additions summary |
| `useAgentHarnessDraft.test.ts` | Patches contain only non-inherited paths; dirty state; publish enables and resets |
| `PersonaHarnessTable.test.tsx` | Seven columns, badges per status, row click navigates, toggle does not |
| `DeliveryTab.test.tsx`, `GuardrailsTab.test.tsx`, `MemoryTab.test.tsx`, `InstructionsTab.test.tsx`, `ContextTab.test.tsx` | Read-only state without `EditAll`; inherited items locked; Show inherited hides them; roles and keyboard operation |
| `SurfacesTab.test.tsx`, `PluginPanel.test.tsx` | Install-on target switches steps, artifact, includes, actions; done labels revert after about 1.8 s (fake timers) |
| `CompiledContextRail.test.tsx` | Segments sum to the used label; pills switch payload |
| Router and sidebar tests | Redirects from the old URLs; sidebar label and `isNew` badge |

### 7.4 Playwright

| Spec | Covers |
|---|---|
| `AgentHarness.spec.ts` | The spec's E2E: create a persona harness → add a rule → publish → generate the Claude plugin; assert the downloaded zip's file list |
| `AgentHarnessInheritance.spec.ts` | Inherited items locked; Show inherited; guardrail minimum disabled options |
| `AgentHarnessPermissions.spec.ts` | Data Consumer sees every screen read-only; primary actions hidden |
| `AgentHarnessNavigation.spec.ts` | Old AI Context URLs redirect; `?tab=` survives reload; unsaved-changes dialog |
| Layout checks | No overflow at 1024, 1280, 1440 px |

The five `PersonaAIContext*.spec.ts` files and `playwright/utils/personaAIContext.ts` are rewritten
onto the new pages, not kept beside them.

### 7.5 Real-host checks (scripted where possible, recorded in the PR)

- Generated Claude zip: uploaded as an organization plugin; loads in claude.ai chat and Cowork
  (skills, connector) and in Claude Code (`claude --plugin-dir`, plus the URL marketplace).
- Codex: plugin imported; connector allowlisted; `install.sh` path on macOS.
- ChatGPT: manifest values entered as a custom connector; `search` and `fetch` succeed (when track
  B ships them).

## 8. Observability and rollout

- Server setting `agentHarness.enabled` gates the new routes, sidebar label, and endpoints until
  the migration has run. Off keeps the old AI Context page.
- Metrics: compile latency per event, preview latency, publish count, plugin generations per
  target, sessions per surface.

## 9. Tracks

| Track | Content | Depends on |
|---|---|---|
| **A, this plan** | Entity, migration, API, compile rekey, preview, MCP instructions, plugin generator, surfaces, UI | — |
| B | Answer harness: `start_answer` … `finish_answer`, SQL Studio / host-connector execution, citations, traces (design doc §7) | A (harness spec carries `answerHarness`) |
| C | Agent memory capture: `AgentCapture`, `capture`, `remember`, feedback (design doc §8) | A (memory settings) |
| D | `om-context` binary and install script (design doc §9.5) | A (plugin, marketplace) |
| E | Conversation visibility (if adopted) | B, C |

The UI ships against A. Tabs whose server side lands in other tracks (Memory capture, hooks status
on Surfaces) render their settings from A and show "takes effect when …" text until then.

## 10. Order of PRs

Each PR ends in a state that is tested and shippable behind the flag.

| # | PR | Done when |
|---|---|---|
| 0 | Amend the design doc per §1; add an ADR: "Agent harness is an entity; persona harnesses extend the default", recording §12 | Doc and ADR reviewed |
| 1 | Spec: `agentHarness.json`, `HarnessSpec`, guardrail and skill types; `make generate` | Generated Java, Python, TS compile |
| 2 | Server: repository, resource, resolver, patch validator, registries, permissions, seeded default | §7.1 unit tests; `AgentHarnessResourceIT`, `AuthorizationIT`, `InheritanceIT` |
| 3 | Migration; removal of `persona.contextDefinition` and the persona AI context endpoints; MCP `get_persona_context` on the harness; Collate change for removed APIs | `AgentHarnessMigrationIT`; rewritten persona context cases green; Compatibility check green |
| 3a | `persona` memory share principal | `PersonaMemoryVisibilityIT` |
| 4 | Compile cache keyed by harness; preview endpoint; MCP instructions | `AgentHarnessPreviewIT`, `McpHarnessInstructionsIT`, existing `AIContext*IT` green |
| 5 | Plugin generator, marketplace, surfaces | `PluginGeneratorTest`, `AgentHarnessPluginIT`, `SurfacesIT`; real-host check of the Claude zip |
| 6 | UI: routes, redirects, sidebar, REST client, list page | Jest for list and router; Playwright navigation |
| 7 | UI: editor shell, settings bar, Context and Delivery tabs, preview rail | Jest per tab; Playwright create → rule → publish |
| 8 | UI: Instructions, Guardrails, Memory tabs; Memories `?source=` | Jest; Playwright inheritance and permissions |
| 9 | UI: Surfaces tab | Jest; Playwright plugin download |
| 10 | Remove the old AI Context UI; flag on by default | Full Playwright suite; UI PR recording |

## 11. Open decisions

None open. The decisions taken on 2026-11-18 are in §12.

## 12. Decisions

| # | Decision | Effect in this plan |
|---|---|---|
| 1 | Keep ADR:2026-10-09-data-consumers-create-context-memories | No AutoPublish review mode; agent memories start unapproved (§2.4) |
| 2 | Add `persona` as a memory share principal | "Persona" visibility shares with the persona's members (§2.4, PR 3a) |
| 3 | No compatibility layer; migrate in one release | `contextDefinition` and the persona AI context endpoints are removed with the migration (§2.2, PR 3) |
| 4 | Collate chat is out of scope | It keeps its own architecture; the server-side Java API it uses for persona context returns the same content (§3) |
| 5 | Per-deployment signing key | Ed25519 pair per deployment, private key in the secrets manager, rotation keeps the previous public key (§5.1) |
