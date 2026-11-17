# One knowledge-graph tool explores the graph through self-describing node cards

- **Status:** Accepted
- **Revisions:** v1 2026-11-17 (initial)
- **Deciders:** Pere Miquel Brull
- **Guard:** `KnowledgeGraphToolTest`, `KnowledgeGraphCallTest`, `NodeCardReaderTest`,
  `OntologyVocabularyTest`, `GraphExpansionTest`, `RdfToolGatingTest`,
  `RdfToolDescriptionVocabularyTest`
- **Related:** ADR:2026-09-16-agent-sparql-execution,
  ADR:2026-11-17-knowledge-graph-runs-are-traced-in-sql-for-a-day,
  ADR:2026-11-06-mcp-tool-arguments-are-coerced-by-tools-not-validated-by-the-sdk

## Context

A host model answering data questions from the RDF projection had to write SPARQL for every step,
and could not tell an edge the projection does not carry from one that is merely absent. We want
one MCP tool through which any model explores the graph by choosing what to fetch, with the graph
describing what is reachable from each node, and every call kept so the explored subgraph can be
drawn.

## Decision

- **One tool, `knowledge_graph`, four operations** chosen by `operation`: `find` (`question`,
  optional `entityTypes`, `limit`), `expand` (`nodes`, `relationships`, optional `direction`,
  `limit`, `cursor`), `query` (`sparql`) and `trace` (`run`). Every response carries `run`; a
  caller passes it back, and the server mints one when it is absent.
- **Node card.** `id` (graph IRI), `entityType` and `fullyQualifiedName` for catalog entities,
  `label`, `types`, `facts` (description cut at 280 characters on a word boundary, tier,
  certification, owners, service, usage; each only when stored), `reachable` and `notCovered`.
  `reachable` has one entry per relationship populated for the node in each direction:
  `relationship` (the prefixed name `expand` takes), `direction`, `count` of distinct far nodes,
  and `about` (the ontology's `rdfs:label`, and its descriptive `rdfs:comment` when it has one).
  `rdf:type` and literal-valued predicates are identity and facts, not relationships; blank-node
  objects are not addressable and are not counted. The map is not ranked or filtered by task.
- **`notCovered` is derived from the ontology.** It lists each `om:InferenceOnly` property whose
  `rdfs:domain` (named or `owl:unionOf`) includes one of the node's classes or their
  superclasses, or that has no domain. A property is left out when it is deprecated, or a
  sub-property or inverse of an `om:Stored` property, because the projection carries that fact
  under the other name. Fact families the ontology does not declare are not listed.
- **`find`** runs the existing search tool with the caller's context: `semantic_search` when
  vector embeddings are enabled, `search_metadata` otherwise. Each hit becomes the IRI the
  projection mints (`RdfUtils.entityUri`; columns by FQN, `RdfUtils.columnUri`). A hit the graph
  holds nothing about comes back with `inGraph: false`.
- **`expand`** pages rows of (node, relationship, direction, far node) ordered by those keys;
  `cursor` is the opaque offset of the next page. Far nodes come back once each as cards. A page
  holds up to `limit` rows (default 10, max 50) and ends early, at least one row in, rather than
  serialize past the MCP response budget, so a large page is cut where the cursor resumes instead
  of being replaced by the dispatcher's truncation envelope. One call takes at most 20 `nodes` and
  10 `relationships`, so the query text stays bounded; more is a correction that names the bound.
- **`find`** returns at most 10 cards. Hits that mint the same IRI (semantic search returns one
  per chunk) become one card, in search order. The leading cards that fit the response budget are
  returned and `moreMatched` counts the rest, so the entry point never comes back as an envelope.
- **Interpolation.** Every IRI the server places in a query (node ids, relationships, far nodes)
  must be an absolute http(s) IRI that `RdfIriValidator.validateEntityIri` accepts, because these
  queries skip the agent query validator. A relationship that is not one is a correction; a far
  node that is not one (a `urn:` concept, say) is listed in its group without a card.
- **`query`** is the `sparql_query` tool's own implementation, unchanged.
- **Access and bounds** are those of the sibling RDF tools: the `ExecuteSparqlQuery` check on the
  `rdf` resource, a `READY` projection for non-administrators, every graph read inside the shared
  admission guard and through the asserted-only read path with its output limit. The tool is
  withheld from `tools/list` when RDF is off.
- **Corrections.** A bad argument is answered as a 400 whose message names the argument, the rule
  and an accepted value, so a model can repeat the call correctly.

## Consequences

A host model needs one tool and learns the loop from it: find, read the map, expand what matters,
pass `run` back. Cards cost two graph reads per batch of nodes. A card's `notCovered` grows with
the ontology's inference-only vocabulary for its type (about 50 properties for a table). Adding an
operation, changing the card shape or the `notCovered` rule means amending this record.
