# Answer harness (track B) implementation plan

- **Status:** Draft, 2026-11-18. Ready to build after the user's track A test
- **Inputs:** `docs/plans/2026-11-18-default-context-harness.md` §7 and §14 P4/P5 (the design
  doc); `docs/plans/2026-11-18-agent-harness-implementation-plan.md` §9 (track A); code at
  `012aadeb78c`. Collate's SQL Studio executor appears only where OSS leaves a seam for it.

Asked "What was last month's ARR?", Claude Desktop today finds the certified table and writes SQL,
then answers in plain prose. Nothing shows which definition, window, table, and trust state the
answer rests on. Track B has the server build that evidence, the "How I got this" block, from
catalog reads made as the user, so the agent cannot invent it. Five slices, each usable alone and
testable on the local stack.

## 1. Slices

| Slice | Ships | User sees | Needs |
|---|---|---|---|
| **B1** | `finish_answer` returns a server-built Markdown citation block | The block in every client | Track A |
| **B2** | MCP Apps view for `finish_answer` | A citation card in Claude, Claude Desktop, ChatGPT; Markdown elsewhere | B1 |
| **B3** | Answer traces: storage, retention, read rules, trace page | "Full trace" opens the trace in OpenMetadata or Collate | B1 |
| **B4** | `start_answer`: question to approved concepts, data, trust, with an `answerId` | The agent starts from the certified metric; ambiguity is asked | B3 |
| **B5** | `validate_query`, `report_result`, Collate `run_query`; corrections to memories | "Ran through your connector (self-reported)"; "Saved, pending review" | B4; Collate `run_query` builds on it |

**Order change.** The brief put `start_answer` before traces. This plan swaps them. The MCP server
is one `McpStatelessSyncServer` with no sessions (track A §0), so the plan `start_answer` returns
must be stored for `finish_answer` to read by `answerId`. The trace is that store, and building it
first makes the trace link live a slice earlier (D1).

## 2. Shared shapes

All JSON Schema first under `openmetadata-spec/src/main/resources/json/schema/`, then
`make generate` from the repository root.

| Schema | Slice | Holds |
|---|---|---|
| `entity/context/agentHarness.json` `definitions.answerHarness`; `api/context/agentHarnessSpec.json` property `answerHarness` | B1, B4, B5 add field groups | Design doc §7.3. Unset groups take built-in defaults in `HarnessResolver` |
| `api/context/finishAnswer.json`, `api/context/answerCitation.json` | B1 | Tool input; the citation (tool `structuredContent`, view input, trace field) |
| `entity/context/answerTrace.json`; `answerTraceRetentionPeriod` in `entity/applications/configuration/internal/dataRetentionConfiguration.json` | B3 | Time-series record (`EntityTimeSeriesInterface`); retention days, default 90 |
| `api/context/startAnswer.json`, `api/context/answerBrief.json` | B4 | `start_answer` input and output |
| `api/context/validateQuery.json`, `validatedQuery.json`, `reportResult.json` | B5 | Query validation and host-execution report |
| `entity/context/contextMemory.json` `sourceType: AgentCapture`, `sourceAgent.answerId` | B5 or track C (D10) | Correction memories linked to the trace |

```text
AnswerCitation
  answerId?     uuid (B3+)            headline    the agent's one-line answer, quoted as the agent's
  concepts[]    { phrase, entityType: glossaryTerm|metric, fqn, displayName, status, version,
                  owners[], url } | { phrase, fqn, unresolved: notFound|notVisible }
  timeWindow?   { phrase, start, end, timeZone, fiscalYearStart, resolvedBy: server|agent }
  tables[]      { fqn, tier?, certified, deprecated, replacedBy?, freshness { at?, source },
                  failingTests[] {name, url}, openIncidents[] {id, status, severity, url}, url }
                | { fqn, unresolved }
  execution     { executor: none|hostConnector|sqlStudio, connector?, reason?, rowCount?,
                  durationMs?, selfReported }
  sql?          planChecked (B4+)     traceUrl? (B3+)     warnings[] (server-written only)
```

The agent supplies names (FQNs, phrases, SQL, headline). The server looks each one up as the
caller, drops facts the caller cannot see, and reports what it could not resolve.

## 3. B1: `finish_answer` with a server-built Markdown block

**Schema.** `finishAnswer.json`, `answerCitation.json`; `answerHarness.calendar`
(`fiscalYearStart` as `MM-DD`, IANA `timeZone`, `weekStart`), `answerHarness.trust`
(`failingTests` and `openIncidents` as `ignore|warn`, `staleAfter`), `answerHarness.answer`
(`showSql`, `citations`). The calendar is read from the default harness only (D2). No UI
changes; admins set these through the harness draft API until B4's tab.

**Java.** New package `org.openmetadata.service.aicontext.answer`, owned by the `ai-mcp-context`
impact-map area through `aicontext/**`.

- `FiscalCalendar` is pure. `resolve(phrase, calendar, now)` covers today, yesterday, last N
  hours/days/weeks, this or last week, month, quarter, and year, MTD, QTD, YTD, `Qn FYyyyy`,
  `<Month> yyyy`, and ISO dates. Quarters and years follow `fiscalYearStart`. An unknown phrase
  keeps the agent's dates with `resolvedBy: agent` and a warning.
- `AnswerCitationBuilder` loads concepts with `Entity.getEntityByName` behind a `VIEW_BASIC`
  check, as `LiveAssetReader` does. Tables reuse `LiveAssetReader`, made public, with `LiveAsset`
  extended by tier, freshness, failing test names, and open incident ids. The existing counts
  become list sizes, so `GuardrailEvaluators` is unchanged. Freshness comes from the latest
  freshness test, else the latest profile, else `unknown`, never metadata `updatedAt` (D3). Links
  use `util/EntityUrls.buildEntityUrl`. Caps are 10 concepts, 5 tables, 20,000 SQL characters.
- `AnswerMarkdown` is a pure renderer of `AnswerCitation`.

**MCP.** `FinishAnswerTool` in `openmetadata-mcp/.../tools/`, a `finish_answer` case in
`DefaultToolContext`, a `tools.json` entry (`readOnlyHint: true`), and `McpServer.withClientName`
extended from `harness_hook` to `finish_answer` for D4. `McpServer.INSTRUCTIONS` gains "call
`finish_answer` last when you answer with data, and show its block".

```text
finish_answer(question: string, answer: string ≤ 500,
              concepts?: [{phrase, entityType, fqn}] ≤ 10, timeWindow?: {phrase, start?, end?},
              tables?: [{fqn, columns?}] ≤ 5, sql?: string,
              execution: {executor, connector?, reason?, rowCount?}, answerId?: uuid (B3+))
-> content[0].text = Markdown block plus one line asking the model to show it verbatim
   structuredContent = AnswerCitation
```

The block for the brief's question when the agent could not run the SQL:

```text
ARR, October 2026: not computed. The query was not run in this chat.

How I got this
 1  "ARR" → metric Annual Recurring Revenue (certified, owner Finance Analytics, v3)
 2  "last month" → October 2026, 2026-10-01 to 2026-10-31 (America/Chicago)
 3  Data → <service>.<db>.<schema>.arr_daily (Tier 1, certified), profiled 3 h ago
    Not used: arr_v1 (deprecated, replaced by arr_daily)
 4  Trust → no failing tests, no open incidents
 5  Not run: no warehouse connector is connected here. SQL below.
```

**Tests.** Unit `FiscalCalendarTest` (each phrase, a Feb 1 fiscal year, year boundary, DST,
leap year, literal dates), `AnswerMarkdownTest` (literal blocks for certified, deprecated, not
visible, not run, host-run), `AnswerCitationBuilderTest` (a fake catalog boundary, no mocks of own
classes), `FinishAnswerToolTest` (coercion, caps, client shaping). IT
`org/openmetadata/it/tests/mcp/answer/FinishAnswerIT` asserts that a certified metric cites owner
and version, a deprecated table names `replacedBy`, an invisible FQN returns `notVisible` and no
facts, an unknown FQN returns `notFound`, failing tests and incidents appear by name and id, and
`fiscalYearStart: 02-01` moves "last quarter". `McpToolsValidationIT` gains the tool.

**Local E2E.** The stack runs at `http://localhost:8585` with users ana and bob (FinanceAnalyst),
the Finance glossary, the ARR metric, `arr_daily` (certified), and `arr_v1` (deprecated).

1. Add `scripts/harness/answer_smoke.py` (stdlib only), which calls a tool on `/mcp` with a PAT.
2. Call `finish_answer` as ana with ARR, "last month", and `arr_daily`. Compare with the sample.
3. Cite `arr_v1` as ana. The block names it deprecated and replaced by `arr_daily`.
4. Add a failing test and an incident to `arr_daily`, then rerun. Both appear with links.
5. As bob, cite a table bob cannot view. The line reads "not visible to you" and nothing more.
6. In Claude Desktop and in Claude Code, ask the ARR question. Record whether the model calls
   `finish_answer` unprompted and whether its reply shows the block verbatim.

**Done when.** Tests pass, steps 2 to 6 have screenshots, and the ADR
`<date>-answer-citations-are-built-from-catalog-reads-as-the-caller` is added.

## 4. B2: MCP Apps citation view (spec facts in Appendix A)

**Java.**

- `McpServer.buildServerCapabilities()` adds `.resources(false, false)` and its Javadoc changes.
  One `SyncResourceSpecification` serves `ui://openmetadata/answer-citation-v1.html` as
  `text/html;profile=mcp-app` with `_meta.ui` `{prefersBorder: true, csp: {}}`. The URI carries a
  version because hosts cache templates.
- `McpUtils.buildTool` copies `_meta` from `tools.json`, which it drops today. `finish_answer`
  declares `_meta.ui.resourceUri` and the ChatGPT alias `openai/outputTemplate` from one constant.
- `FinishAnswerTool` shapes `content` by `Mcp-Client-Name` (D4). A client known to render the view
  gets a three-line summary and "the card is shown; do not repeat it". Others get the full block.

**View.** `openmetadata-mcp/src/main/resources/mcp-apps/answer-citation-v1.html` is one vanilla
file with no external loads (D11). It sends `ui/initialize`, renders `structuredContent` from
`ui/notifications/tool-result` with `textContent` only, follows `prefers-color-scheme`, opens
links through `ui/open-link`, and toggles "View SQL" inline.

**Tests.** Unit `McpResourcesTest` (list, read with the exact mime type, `_meta` on
`finish_answer` only). IT `mcp/answer/McpAppsResourceIT` over HTTP; `McpAvailabilityIT` unchanged.
Playwright `playwright/e2e/Features/AnswerCitationView.spec.ts` plays the host with `postMessage`,
asserts the rendered lines and warnings, checks that a `<script>` in a description renders as
text, and checks that a link click posts `ui/open-link`.

**Local E2E.** Expose the stack over HTTPS through a tunnel. In claude.ai, Claude Desktop, and
ChatGPT developer mode, ask the ARR question, screenshot the card, and record whether a custom
connector renders it and whether the model repeats the block. Claude Code and Codex keep B1's
Markdown.

**Done when.** Tests pass, the per-client render matrix is in the PR, and the ADR
`<date>-answer-citations-render-as-an-mcp-apps-view-with-markdown-fallback` is added.

## 5. B3: answer traces

**Storage.** `AnswerTraceRepository extends EntityTimeSeriesRepository<AnswerTrace>`, modeled on
`McpExecutionRepository`. Table `answer_trace_time_series` is created in the current native
migration folder for MySQL and Postgres (2.1.0 today; confirm at build time). The partition key is
the asker's FQN hash, so listing one's own answers reads one partition. The row id is the
`answerId`.

```text
AnswerTrace  id, timestamp, askedBy, persona, harnessVersion, client, question, refines?
  stages[]     { stage: understand|concepts|route|shape|trust|validate|execute|cite|learn,
                 at, input, decision, candidates[] {fqn, score, why}, warnings[] }
  executions[] { executor, queryToken?, executedSql?, rowCount?, durationMs, selfReported }
  citation?    AnswerCitation          memoriesProposed[]  memory refs
```

Traces never hold result rows (D5). A stage write replaces that stage's entry, so a retried call
converges. Writes use `update(json, id)` with a version check and retry once on conflict.

**Reads, retention, MCP.** Only the asker and admins read a trace (D6), and no policy widens it,
as in ADR:2026-11-13-a-query-runner-workflow-is-read-only-by-its-requester. REST in
`resources/context/` serves `GET /v1/agentHarness/answers/{id}` and the caller's list (admins may
pass `user`). `DataRetention.cleanAnswerTraces(days)` sits beside `cleanTestCaseResults`.
`finish_answer` writes the trace, creating one without an `answerId`, and returns `traceUrl`
(`<mcp baseUrl>/context-center/answers/<id>`).

**UI.** `src/pages/ContextCenterPage/AnswerTracePage/AnswerTracePage.tsx` on core `PageLayout`
shows the citation, the stages with candidates and warnings, the SQL, and the executions.
`src/rest/answerTraceAPI.ts` calls the endpoints. Strings go through `yarn i18n`.

**Tests.** IT `mcp/answer/AnswerTraceIT` asserts that ana reads her trace, bob gets 403, an admin
reads it, two `finish_answer` calls with one `answerId` leave one `cite` stage, retention removes
only traces past the period, and stored JSON holds no row values. Jest `AnswerTracePage.test.tsx`.
Playwright `AnswerTrace.spec.ts` opens a seeded trace and checks that bob is denied.

**Local E2E.** Ask the ARR question in Claude Desktop and open "Full trace". The page shows the
same facts. Signed in as bob, the same link is denied.

**Done when.** Tests pass and the ADR
`<date>-answer-traces-are-time-series-read-by-the-asker-and-admins` is added.

## 6. B4: `start_answer`

**Schema.** `startAnswer.json` (`question`, `refines?`), `answerBrief.json`; `answerHarness`
gains `concepts` (`sources`, `requireApproved`, `ambiguity: ask|bestGuess`), `routing` (`prefer`,
`allowUncertified`, `maxCandidates`), and `profile` (`enabled`, `sampleValues`). `trust` levels
gain `ask` and `stop`.

**Java,** in `aicontext.answer`.

- `QuestionParser` is pure and uses no LLM. It extracts time phrases through `FiscalCalendar` and
  leaves measure and filter words to binding.
- `ConceptBinder` wraps `AIContextFinder.find`, which runs as the caller under the persona search
  scope, and adds Approved memories. Approved terms and certified metrics outrank drafts. Two
  candidates within 0.1 of each other become an ambiguity the agent must ask about.
- `AnswerRouter` maps metrics to tables through `MetricColumnResolver` and the metric's `assets`,
  and terms through `KnowledgeAssetLookup`, which already ranks certified first in `AssetUse`. It
  prefers certified, then tier, then downstream by lineage, up to `maxCandidates`.
- `ShapeReader` reads column profiles through `ConceptContextBuilder`'s catalog boundary. Sample
  values need `profile.sampleValues` and the sample-data permission.
- Trust reuses B1's `LiveAssetReader` facts. `start_answer` writes stages 1 to 5 to the trace.

```text
start_answer(question: string, refines?: uuid)
-> { answerId, timeWindow?, concepts[] {phrase, fqn, status, definition, expression?, owners},
     ambiguities[] {phrase, candidates[] {fqn, why}},
     tables[] {fqn, tier, certified, joins[], columns[] {name, type, nullRatio?, distinct?,
               sampleValues?}},
     trust[] {fqn, level: warn|ask|stop, reason}, nextStep: ask|query|stop }
```

With an `answerId`, `finish_answer` checks cited items against the plan and warns on anything
outside it. Without one it still works, with `planChecked: false` (D13). Having `harness_hook`
call `start_answer` on UserPromptSubmit is a later, measured step.

**UI.** An "Answers" tab in `components/ContextCenter/AgentHarness/AnswersTab/` edits calendar
(default harness only), concepts, routing, trust, and answer settings. Persona harnesses show
inherited values read-only, as the other tabs do. `HarnessDraftValidator` validates the block and
`HarnessResolver` merges it.

**Tests.** Unit `QuestionParserTest`, `ConceptBinderTest`, `AnswerRouterTest`. IT
`mcp/answer/StartAnswerIT` asserts that "last month's ARR" binds the certified metric and routes
to `arr_daily`, never `arr_v1`; an Approved term beats a Draft one; two close terms return an
ambiguity; `trust.failingTests: stop` returns `nextStep: stop`; and bob's persona scope hides a
table ana sees. Jest for `AnswersTab`; Playwright `AgentHarnessAnswers.spec.ts` sets a fiscal year
start, publishes, and previews.

**Local E2E.** In Claude Desktop, the ARR question runs `start_answer` then `finish_answer`, and
the card shows the plan was checked. "Revenue last quarter" with two revenue terms makes the model
ask. After setting `fiscalYearStart` to 02-01 in the UI, "last quarter" follows fiscal dates.

**Done when.** Tests pass and the design doc's two §7 example questions run on sample data, with
their traces attached to the PR.

## 7. B5: queries and corrections

**Schema.** `validateQuery.json`, `validatedQuery.json`, `reportResult.json`;
`answerHarness.query` (`executor`, `allowHostConnector`, `services`, `rowLimit`,
`timeoutSeconds`, `tablesOutsidePlan`) and `answerHarness.learning` (`captureCorrections`,
`notifyOwners`). Memory fields per D10.

**Java,** in `aicontext.answer`.

- `QueryValidator` parses with JSqlParser 4.9, already a service dependency (D8). It checks tables
  against the plan, PII columns against guardrails, a time filter when the plan has a window, and
  the row limit, added with `OntologyConceptSqlGenerator`'s dialect rules. Unparseable SQL warns.
- `QueryToken` is `sha256(answerId + normalizedSql)`. Normalization strips comments, collapses
  whitespace, and drops a trailing semicolon.
- `ExecutorResolver` resolves `auto` to `hostConnector`, or `none` when disallowed, in OSS. Collate
  implements it for SQL Studio. OSS never runs SQL
  (ADR:2026-10-21-ontology-live-data-execution-is-a-collate-extension).
- `CorrectionDetector` compares a `refines` plan with its parent. A changed concept, filter
  definition, or table proposes a memory; grouping or time window alone does not (D12). The memory
  is `Learning` or `Preference` with `primaryEntity` set to the corrected concept,
  `relatedEntities` set to the plan tables, unapproved and private. A correction to a certified
  metric or Approved term opens an owner task through `MemoryConflictReviewTasks`.

**MCP.** `validate_query(answerId, sql)` returns `{approvedSql, queryToken, executor, warnings[],
denied?}`. `report_result(answerId, queryToken, executedSql, rowCount, summary?)` records a host
run. Collate adds `run_query(answerId, queryToken)` in its `McpServer` subclass. `HarnessHookTool`
PreToolUse matches a warehouse connector's execute tool, compares its SQL with the caller's
`queryToken`s from the last 30 minutes, and warns or blocks per `tablesOutsidePlan`. The query
cost ceiling guardrail stays Off in OSS because it needs warehouse `EXPLAIN`.

**UI.** The Answers tab gains query and learning settings and the design doc's test-questions
runner, which shows each question's concept, table, and SQL before and after a draft change.

**Tests.** Unit `QueryValidatorTest` (Snowflake, BigQuery, Postgres samples; outside the plan;
PII; no time filter; unparseable), `QueryTokenTest`, `CorrectionDetectorTest`, and SQL-mismatch
cases in `HarnessHookToolTest`. ITs in `mcp/answer/` are `ValidateQueryIT` (warn and deny per
config), `ReportResultIT` (cited as self-reported), and `AnswerCorrectionIT` (a filter change
yields one unapproved memory linked to ARR and `arr_daily` plus an owner task; a by-month follow-up
yields none). Playwright `AgentHarnessTestQuestions.spec.ts`.

**Local E2E.** In Claude Code with a Postgres or Snowflake MCP server, the model validates, runs
the approved SQL, and reports. The card reads "ran through your connector, not executed by
OpenMetadata". Edited SQL that reads `arr_v1` is blocked at PreToolUse. In Claude Desktop, the
follow-up "exclude trial accounts" shows "Saved, pending review by Finance Analytics", and the
memory and owner task exist in the UI.

**Done when.** Tests pass and the ADR
`<date>-a-query-token-is-the-hash-of-the-answer-and-normalized-sql` is added.

## 8. Decisions for the user

| # | Decision | Recommendation |
|---|---|---|
| D1 | Traces (B3) before `start_answer` (B4) | Yes. The stateless server needs the trace to carry the plan between calls |
| D2 | Calendar per persona or organization-wide | Default harness only. Two fiscal calendars give two answers to one question |
| D3 | Freshness source | Freshness test, else latest profile, else "unknown" |
| D4 | Avoid a repeated block where the card renders | Shape `content` by `Mcp-Client-Name`; unknown clients get the full block. Revisit after B2 |
| D5 | Trace contents and retention | Question, plan, SQL, execution metadata; never rows; 90 days |
| D6 | Who reads traces | Asker and admins. Owners see corrections through review tasks |
| D7 | Transpiling in OSS | No. Quote and limit per dialect; SQL Studio transpiles in Collate |
| D8 | SQL parser | JSqlParser, which covers more dialects than Calcite's lexers |
| D9 | `tablesOutsidePlan` default | `warn`, as the design doc recommends for deny guardrails |
| D10 | Who adds `AgentCapture` and `sourceAgent` to memories | Whichever of track C and B5 merges first; the other rebases |
| D11 | View technology | One vanilla HTML file. React and the ext-apps `App` class add a build for one card |
| D12 | Which follow-ups are corrections | Concept, filter definition, or table changes only (design doc §13.10) |
| D13 | `finish_answer` without `start_answer` | Allowed, cited with `planChecked: false`. Chat clients often skip the first call |

## 9. Risks

| Risk | Slice | Watch |
|---|---|---|
| Text hosts paraphrase or drop the block | B1 | Step 6 records it; if it fails, repeat the instruction in `get_context` |
| Chat models skip `finish_answer` without hooks | B1 | Calls per data question in `McpUsageRecorder`. If low, carry the instruction in `FirstCallContext` (in flight, uncommitted), which prefixes the first tool result for hookless hosts |
| Custom connectors may not render MCP Apps in claude.ai or ChatGPT | B2 | Render matrix; Markdown stays the fallback |
| A stateless server cannot see the host's UI capability, so `_meta.ui` is always declared | B2 | Check Claude Code and Codex ignore it |
| Advertising `resources` changes what hosts call | B2 | `McpAvailabilityIT`; smoke in VS Code and Claude Code |
| Collate overrides `buildServerCapabilities` or `getTools` | B2, B5 | Build Collate against the branch before the PR |
| Catalog text injected into the view | B2 | `textContent` only; the Playwright script-tag case |
| `validate_query` and `report_result` race on one trace | B3 | Version check plus retry; a concurrent-write IT case |
| No profiler run, or a time phrase outside the grammar | B1 | Shown as "unknown" or as the agent's dates, flagged; grow the grammar from traces |
| Host-executed results are self-reported | B5 | The citation says so |
| Another agent edits `McpServer`, `HarnessHookTool`, and the harness package now | All | Rebase before each slice; keep those edits small |

## 10. Conventions

Run `mvn spotless:apply` and `java-affected-tests` per PR. ITs live in `mcp/answer/`, owned by the
`ai-mcp-context` area; add `jdbi3/Answer*` to its sources. No caches are planned; any cache gets a
Caffeine `maximumSize`. UI uses core components, `tw:` classes, i18n, and `ui-checkstyle`. Each
slice's ADR ships in its PR.

## Appendix A. MCP Apps facts used in B2

Read on 2026-11-18.

| Fact | Source |
|---|---|
| SEP-1865 is Final: `ui://` resources, `text/html;profile=mcp-app`, an optional negotiated extension | [SEP-1865](https://modelcontextprotocol.io/seps/1865-mcp-apps-interactive-user-interfaces-for-mcp) |
| Extension `io.modelcontextprotocol/ui`, advertised by the client in `capabilities.extensions` with `mimeTypes`; tool `_meta.ui.resourceUri` and `visibility`; resource `_meta.ui.csp`, `permissions`, `domain`, `prefersBorder`; `ui/initialize`, `ui/notifications/tool-result`, `ui/open-link`; `structuredContent` feeds the view, not the model; tools must return useful `content` | [ext-apps draft spec](https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/draft/apps.mdx) (a stable 2026-01-26 version exists; not read) |
| Claude and Claude Desktop are listed clients | [MCP Apps overview](https://modelcontextprotocol.io/extensions/apps/overview.md) |
| ChatGPT implements MCP Apps; `openai/outputTemplate` is an optional alias | [OpenAI Apps SDK](https://developers.openai.com/apps-sdk/mcp-apps-in-chatgpt) |
| SDK 2.1.0 here has `Tool.meta()`, `Resource`, `resources(Boolean, Boolean)`, stateless resource specs | `javap` on `mcp-core-2.1.0.jar` |

Unverified until B2's local E2E: whether claude.ai and Claude Desktop render the view for an
organization-added connector; how a ChatGPT workspace enables UI for a custom app; the
`User-Agent` of claude.ai and ChatGPT connector calls, which D4 relies on; whether any host needs
the server to advertise `io.modelcontextprotocol/ui` (the spec shows only the client doing so);
whether Claude Code or Codex render MCP Apps (neither is listed).
