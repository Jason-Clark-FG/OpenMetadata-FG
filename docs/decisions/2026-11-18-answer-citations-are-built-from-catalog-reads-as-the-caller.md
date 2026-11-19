# Answer citations are built from catalog reads made as the caller

- **Status:** Accepted
- **Revisions:** v1 2026-11-18 (initial)
- **Deciders:** Agent harness track B
- **Guard:** `FinishAnswerIT` (a non-admin caller; a hidden table is not named), `AnswerCitationBuilderTest`
- **Related:** `docs/plans/2026-11-18-answer-harness-track-b-plan.md` (B1, D2, D3, D13), `docs/plans/2026-11-18-default-context-harness.md` §7

## Context

An agent that answers a business question in plain prose gives the user no way to tell which
definition, time window, table and trust state the answer rests on. If the agent also wrote that
evidence, it could cite a certified table it never read, or leave out a failing test.

## Decision

`finish_answer` takes only names from the agent: fully qualified names of terms, metrics and
tables, the time phrase and dates, the SQL, the execution it reports and a headline. The server
resolves each name as the calling user and writes the citation and its Markdown block itself.

- A name the caller cannot view with `VIEW_BASIC` is `notVisible`; it carries no fact and no name
  in the block or in `structuredContent`. A name nothing has is `notFound` and is echoed.
- Trust signals are read from what OpenMetadata already stores: certification, tier, owners,
  `EntityStatus.DEPRECATED`, the latest result of each test case on the table and its open
  incidents. Failing tests and open incidents can be set to `ignore` in the default harness's
  `answerHarness.trust`, and the citation then says they were not checked.
- Freshness is the latest freshness test's last run, else the latest table profile, else
  `unknown`. It is never the entity's `updatedAt`, which changes on every metadata edit and says
  nothing about the data.
- The calendar (`fiscalYearStart`, `timeZone`, `weekStart`) is read from the default harness only,
  so one question has one answer. A fiscal year is named by the calendar year it starts in. A
  phrase outside the grammar keeps the agent's dates, marked `resolvedBy: agent`, with a warning.
- Caps: 10 concepts, 5 tables, 20,000 SQL characters. The caps are checked by the server, because
  the SDK does not validate tool arguments.
- OpenMetadata does not run SQL. `execution.executor` may be `none` or `hostConnector`, a run is
  always `selfReported`, and `sqlStudio` is refused from the agent.
- `finish_answer` works without `start_answer` (`planChecked: false`).

## Consequences

The block can be trusted for what it says about the catalog, and says plainly what it could not
resolve. The cost is a catalog read per cited name on each call, bounded by the caps. The
`notFound`/`notVisible` split tells a caller that a name exists; that is the same fact the REST
API gives with 403 versus 404. Revisit if that difference must be hidden, by reporting both as
`unresolved`.
