# A table records the table that replaces it as an entity relationship

- **Status:** Accepted
- **Revisions:** v1 2026-11-18 (initial)
- **Deciders:** Sriharsha Chintalapani
- **Guard:** `TableReplacedByIT` (set, read by fields, reject self, non-table and deleted targets, PUT keeps it, hard-delete cleanup); `EnumBackwardCompatibilityTest` (the ordinal)
- **Related:** the agent harness plan, `docs/plans/2026-11-18-agent-harness-implementation-plan.md`

## Context

A deprecated table gave an agent nowhere to go. The agent harness could only say "ask the owner
for the replacement", because OpenMetadata held no structured link from a table to its successor.
Ontology concepts and relationship types have `replacedBy`; data assets did not.

## Decision

`table.json` gains an optional `replacedBy` (an `EntityReference` to a table), and `createTable.json`
takes it as a fully qualified name.

- **Stored as a relationship, not in the entity JSON.** `Relationship.REPLACED_BY` is appended at
  the end of the enum, as ordinal 28, because the ordinal is what `entity_relationship.relation`
  stores. The edge runs from the replaced table to its replacement. `TableRepository` strips the
  field from the stored JSON and loads it only when `fields=replacedBy` is asked for, in bulk too.
- **Valid whether or not the table is deprecated.** The target must be an existing, non-deleted
  table other than the table itself. The UI shows it only while the table is deprecated.
- **A PUT that omits it keeps it; a PATCH can clear it.** Ingestion sends PUTs and does not know
  the field, so an omitted value cannot mean "clear". `replace` and `remove` in a PATCH can.
- **Setting it needs `EditAll`.** The PATCH operation mapper has no operation for the path, so it
  falls to `EditAll`, as `entityStatus` does for a table today.
- **A change is recorded** as a `replacedBy` field change in the change description.
- **Cleanup is the relationship's.** A hard delete of either table removes the edge with the rest of
  that table's relationships. A soft-deleted replacement still reads back, flagged `deleted`; the
  guardrail and the citation skip a deleted or unviewable replacement.
- **The search index is not changed.** The guardrail and the citation read the replacement through
  `LiveTrustReader`, so a reindex does not have to carry it.

The guardrail names it ("table X is deprecated. Use Y instead."), the citation says "deprecated,
replaced by `Y`", and a glossary term whose only tagged tables are deprecated names their
replacements on its `Use:` line. That lookup is bounded at 10 repository reads per compile.

## Consequences

A downstream distribution that switches exhaustively over `Relationship` must handle the new value.
Reverting is a schema removal plus a migration that deletes `relation = 28` rows, so the ordinal is
never reused for something else.
