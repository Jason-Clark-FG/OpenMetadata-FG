# One knowledge-graph tool explores the graph through self-describing node cards

- **Status:** Accepted
- **Revisions:** v1 2026-11-17 (initial) · v2 2026-11-18 (find matches ontology terms by label)
- **Deciders:** Pere Miquel Brull
- **Guard:** `KnowledgeGraphToolTest`, `KnowledgeGraphCallTest`, `NodeCardReaderTest`,
  `OntologyVocabularyTest`, `GraphExpansionTest`, `RdfToolGatingTest`,
  `RdfToolDescriptionVocabularyTest`, `ExplorationQueriesTest`, `OntologyTermsTest`,
  `KnowledgeGraphExplorerTest`, `CatalogStartingPointsTest`, `RdfKnowledgeGraphToolIT`
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

## Amendment 2026-11-18. Find reaches ontology terms search does not index

Search indexes no ontology concept or property, and the catalog projection leaves them out, so
`find` could not start from a business word. It now has a second source beside search.

- **Ontology terms by label, word for word.** The release graphs (`graph/ontology/<ontologyId>`,
  which hold only each ontology's active release) are read for classes (`owl:Class`,
  `skos:Concept`) and properties (`owl:ObjectProperty`, `owl:DatatypeProperty`,
  `owl:AnnotationProperty`). Labels (`rdfs:label`, `skos:altLabel`) and the question are cut into
  words the same way: camel case and anything but letters and digits split words, case is
  ignored, a plural folds to its singular (`-ies` to `-y`, a final `s` but not `ss`), and words
  under three characters or on a fixed list of common words are dropped. A term matches when
  every word of one of its labels is in the question, or every word of the question is in the
  label: "which tables hold subscriber postal codes" finds "Subscriber" and "postalCode", and
  "Age" is not found by "average". A question with no words left looks for no terms. Up to 12
  words are used. There are no synonyms beyond the `skos:altLabel` a release writes, and no
  stemming beyond plurals. A term whose IRI is not http(s) is left out, as no card can address
  it. A matched term is an ordinary card under its own IRI, so `expand` continues from it.
- **Binding.** The graph read only narrows candidates to labels containing one of the words; each
  word goes in as its own literal through Jena's `ParameterizedSparqlString`, the type list and
  limits are constants, and no pattern is built from caller text (the word splitting runs in Java
  with constant patterns). The read takes the same admission guard, asserted-only path and output
  bound as card reads, and returns at most 500 candidate labels, ordered by term IRI.
- **Order.** Each source gives at most `limit` nodes. Terms come most specific first: the match
  that covers more of the question's words, then the label with fewer words outside the question,
  then the term IRI. Hits come in search order. They alternate, a term first, and when one source runs out the other fills
  the rest. At most `limit` cards are read, then cut to the response budget as before.
  `moreMatched` counts the matched nodes of both sources left out; since each source is asked for
  `limit` nodes, it does not count matches beyond that. Alternating lets a term appear when the
  catalog has many name matches without terms crowding the catalog out.
- **`entityTypes`.** Absent: both sources. `ontologyConcept` and `ontologyProperty` choose those
  kinds of term; every other value is a catalog type. Only catalog types: no term lookup. Only
  ontology types: no catalog search.
- **Failure.** A term lookup that times out or exceeds the output bound yields no terms and is
  logged, and the find response carries `termLookupSkipped` with the reason; the field is present
  only then. The catalog half answers. No active release means no terms.

Known limits: the candidate read scans every label of every active release (`CONTAINS` uses no
index), and its cost on a very large imported ontology is unmeasured. When more than 500 labels
contain a question word, the candidates past the cut, by term IRI, are not ranked, so a more
specific term can be missed. The common-word list is English only.
