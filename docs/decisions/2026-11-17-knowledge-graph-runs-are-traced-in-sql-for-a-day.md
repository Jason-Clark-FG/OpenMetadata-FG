# Knowledge-graph runs are traced in SQL for a day

- **Status:** Accepted
- **Revisions:** v1 2026-11-17 (initial)
- **Deciders:** Pere Miquel Brull
- **Guard:** `SqlRunTraceStoreTest`, `KnowledgeGraphExplorerTest`,
  `OntologySqlMigrationParityTest#upgradeCreatesTheExplorationRunTraceTable`,
  `KnowledgeGraphRunTraceDaoIT` (MySQL and Postgres)
- **Related:** ADR:2026-11-17-one-knowledge-graph-tool-explores-through-node-cards,
  ADR:2026-10-11-asserted-only-rdf-reads-name-every-non-inferred-graph,
  ADR:2026-10-26-the-rdf-graph-projects-the-live-catalog-only,
  ADR:2026-10-28-rdf-storage-maintenance-pauses-serving-writes-and-compacts-on-a-schedule

## Context

Every `knowledge_graph` call belongs to a run, and the nodes and edges a run served must be kept
so the explored subgraph can be drawn for the end user. MCP is stateless per call and a run can
continue on another server node, so the trace needs a store every node shares. It must never land
in, or be returned by, queries over the catalog knowledge graph, and it needs a bounded lifetime.

The triplestore is the wrong place. Asserted-only reads name every graph outside
`graph/inferred/` as their dataset, so a trace graph would be read by every catalog query; trace
writes would also have to bypass the single live writer and would stop during storage
maintenance. Redis is optional. No existing SQL table fits: `entity_extension_time_series` orders
rows by a millisecond timestamp, which ties, and `entity_extension` has no time column to expire
rows by.

## Decision

- Steps live in their own table, `rdf_exploration_run_step` (`id` auto-increment, `runId`,
  `createdAt`, `json`), created by the 2.1.0 schema migration on MySQL and Postgres. One row per
  call; a run's steps are ordered by `id`. Rows are only ever inserted, so two calls of one run on
  two nodes never overwrite each other.
- A step is kept for 24 hours after it was recorded (`RunTraceStore.RETENTION`). Every append
  deletes expired rows, and reads ignore them, so the table holds at most a day of calls.
- A step stores what the call served: the nodes returned as cards, each with the relationships its
  card offered (relationship, direction, count, under one-letter keys), and the edges an expansion
  returned. A find step of ten cards with thirty `reachable` entries each stores 15,280 characters
  of JSON; storing each offer as a full edge took 47,569 (`KnowledgeGraphExplorerTest`). `trace`
  folds the steps: a node is `explored`; an offered relationship stays `offered` until an
  expansion from that node follows it in that direction, then it is `explored`.
- Writing the step is part of the call. A call whose step cannot be stored fails, so a trace is
  never silently missing a step that the caller was answered from.
- `trace` of a run with no live steps is a correction (400), not an empty subgraph.

## Consequences

The trace survives across server nodes without touching the triplestore or its writer. The cost is
one insert and one indexed delete per call and a new table. A run longer than a day loses its
oldest steps. **Known gap:** the trace has no owner check. Any caller with `ExecuteSparqlQuery` who holds the
run handle can read it, including the SPARQL text of the run's `query` steps. Access rules for
exploration are deferred; adding one means amending this record.
Moving the trace to another store, or changing the retention, means a new record.
