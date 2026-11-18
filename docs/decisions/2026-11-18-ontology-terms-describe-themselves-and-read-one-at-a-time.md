# Ontology terms describe themselves, and a caller reads one term at a time

- **Status:** Accepted
- **Revisions:** v1 2026-11-18 (initial)
- **Deciders:** OpenMetadata maintainers
- **Guard:** `RdfOntologyDescriptionContractTest`, `OntologyDocumentTest`,
  `OntologyDescribeToolTest#everyClassReadsBackWholeWithinTheResponseBudget`
- **Related:** ADR:2026-09-16-agent-sparql-execution, `docs/rdf-ontology-contract.md`

## Context
A model exploring the knowledge graph sees which predicates leave a node, not what is behind them.
Most predicates in `openmetadata.ttl` carried only a label and a projection status, so the meaning
lived in prompts kept in other repositories. The whole file is about 310 KB as Turtle, several times
the MCP response budget, so `ontology_describe` without a `resource` returned a truncated prefix.

## Decision
- Every predicate that is declared and either written by the projection fixtures or the writers'
  constant predicates, or marked `om:Stored`, has a non-blank `rdfs:comment`. Every `om:` class the
  projection types a node with is declared as an `owl:Class` with a comment.
- `rdfs:domain` and `rdfs:range` are asserted only when they hold for every use the projection makes.
  A predicate shared by several entity types leaves the domain open and says so in its comment.
  External vocabulary terms get a usage comment and no domain or range of ours.
- `ontology_describe` takes `term`: a prefixed name or IRI of the bundled ontology. A class returns
  its own statements plus those of every property whose `rdfs:domain` is the class or a superclass;
  a property returns its own statements. Blank-node objects are followed so lists stay whole. It is
  served from the same static file as the full ontology and needs no permission; `term` and
  `resource` are not combined. An undeclared term is an argument error.
- `owl:versionInfo` is not bumped for comments and axiom corrections; the changelog records them
  under an unreleased entry.

## Consequences
Adding a stored predicate or a projected type without a comment fails the build, and so does a
domain or literal range the projection contradicts. Class ranges are only checked against literal
values: the fixtures point every reference at one shared id, so object types cannot be verified.
Terms written on branches still in flight are exempted by name in the test and must be removed from
that list when those branches merge. A class entry grows with every property whose domain covers
it; `om:Table` is about half the default budget, and the budget test fails before a class outgrows
it.
