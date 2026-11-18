# The RDF graph carries the latest quality evidence of each table, column and test case

- **Status:** Accepted
- **Revisions:** v1 2026-11-18 (initial)
- **Deciders:** OpenMetadata RDF maintainers
- **Guard:** `RdfQualityEvidenceTest`, `RdfEntityWritePlannerTest#aNewerTestResultReplacesTheOlderOneAndDeletingTheTestCaseRemovesIt`,
  `ProfileWritesRefreshRdfTest`, `RdfReindexerTest#everyPageIsWrittenWithTheQualityEvidenceLoadedOntoItsEntities`,
  `RdfOntologyContractTest`
- **Related:** ADR:2026-11-03-live-rdf-entity-writes-send-only-the-changed-triples,
  ADR:2026-10-26-the-rdf-graph-projects-the-live-catalog-only

## Context

A model writing SQL, or judging whether a number can be trusted, needs to see how the data looks
and whether it is healthy. Profiles and test results live in time-series tables, not in the entity
rows, so the projection never saw them: `RdfQualityMapper` existed but no read loaded a profile, and
`testCaseResult` was excluded from the graph. A test case had no link to what it tests; its entity
link was dropped because it is not an IRI.

## Decision

- **Latest only.** The graph holds the latest table profile, the latest profile of each column and
  the latest result of each test case. History stays in the profile and results APIs.
- **Loaded on projection, both paths.** `RdfQualityEvidence` reads the newest rows in batches and
  attaches them before translation: the live replay's entity loader (`RdfProjectionLoaders`) and the
  reindex pages (`RdfReindexer.repositoryPages`) call the same code. `testCaseResult` is not loaded
  through the test case read, which would look it up in search first.
- **Live triggers.** A profile write or delete (`TableRepository`, `EntityProfileRepository`)
  queues a refresh of its table. A test result already updates its test case, whose entity hook
  queues the refresh.
- **Stable names, exact replacement.** A measure is `<subject>/measurement/<Metric>`, the result
  `<test case>/result/latest`, its values `<result>/value/<index>`. The entity write owns them
  (`RdfOwnedResources`), so a newer profile or result replaces the old one exactly, and deleting a
  table, column or test case removes its evidence.
- **Bounded measures.** Table: row count, column count, size. Column: values, null count, null
  proportion, distinct count, unique count, min, max, mean, min and max length. Min and max are
  emitted only when numeric. One table with N columns adds at most 18 + 60·N triples.
- **Result shape.** `om:hasTestResult` → status, time (`prov:generatedAtTime`), message, passed and
  failed rows, min and max bound, and `om:hasResultValue` nodes (name, observed value). Sample rows
  are never emitted. A test case links its live table (`om:testsEntity`) and, for a column test, its
  column (`om:testsColumn`).
- **PII.** What reaches the graph is what a caller without PII access gets from the API
  (`PIIMasker`): no profile for a `PII.Sensitive` column or the fields beneath one, and no result for
  a test on such a column or on a table that cannot be resolved.

## Consequences

- Incidents are not modelled: the test case keeps the opaque `incidentStatus` literal it had.
- A tag change that makes a column sensitive rewrites the table, so its profile leaves at once, but
  its test cases keep their last result until they are written again or reindexed.
- A result written while RDF was off, or before this change, appears only after a reindex or the
  next write of its test case. Existing stores hold no evidence until a reindex.
- Revisit if the per-column measure set must grow, or if profile history becomes a graph need.
