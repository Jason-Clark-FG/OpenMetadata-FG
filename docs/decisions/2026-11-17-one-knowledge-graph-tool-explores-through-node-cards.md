# One knowledge-graph tool explores the graph through self-describing node cards

- **Status:** Accepted
- **Revisions:** v1 2026-11-17 (initial) · v2 2026-11-18 (find matches ontology terms by label) · v3 2026-11-18 (every stored value on the card, descriptions once per response) · v4 2026-11-18 (cite, term kinds, labels for unnamed nodes) · v5 2026-11-18 (the catalog half of find reads a question word by word) · v6 2026-11-18 (a server can offer knowledge_graph alone) · v7 2026-11-18 (with semantic search on, the catalog half is one hybrid search)
- **Deciders:** Pere Miquel Brull
- **Guard:** `KnowledgeGraphToolTest`, `KnowledgeGraphCallTest`, `NodeCardReaderTest`,
  `OntologyVocabularyTest`, `GraphExpansionTest`, `NodeCardValuesTest`, `RdfToolGatingTest`,
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

## Amendment 2026-11-18. Every stored value is on the card; descriptions come once

Used for real, a card showed thirteen fixed facts and hid every other value stored on the node:
profiles, the latest test result, an incident's state, a column's data type. And every `reachable`
entry repeated its relationship's comment, so a ten-card `find` of wide tables exceeded the
response budget. This replaces the card shape above.

- **`values`.** A card lists every property whose objects are literals on the node, except the
  header facts it already shows (label, display name, qualified name, description, certification,
  usage). Each entry has `property` (the name `query` takes), `type` (`integer`, `decimal`,
  `boolean`, `dateTime` or `date`, absent for text) and `value`. A property with several values
  gives the first and `last` in text order and their `count`; a value longer than 280 characters
  is cut, ends with `…`, and the entry gives the full `length` of the longest value. A cut
  description in the header gives its `descriptionLength`. The rest is read with `query`. Nothing
  is added to the graph: what the projection leaves out (masked profiles, say) stays out.
- **`properties`.** `reachable` entries carry `relationship`, `direction` and `count`. A `find` or
  `expand` response carries one `properties` map from every relationship and value property its
  cards or groups name to the ontology's label and comment. The budget charges each description
  once per response.
- **Reads.** Cards cost three graph reads per batch: header facts, values (one aggregated row per
  node and property, each value cut at 280 characters inside the query, so a long literal never
  crosses the read bound whole), and reachability.
- **Trace.** The stored step keeps no values and no descriptions.

Measured on tables with 30 stored, described relationships: one card went from 12,531 to 5,616
characters; a 10-card `find` from 125,347 to 63,396 (6 cards fitted the 80,000-character budget,
now all 10); a 20-card `expand` from 252,336 to 121,235 (6 cards fitted, now 12). The stored find
step stayed at 15,318.

## Amendment 2026-11-18. Cite, term kinds and labels for unnamed nodes

- **`cite`**, a fifth operation: `run`, `nodes` (card ids, at most 50) and optional `edges`
  (`{node, relationship, other}`, at most 50). Call it once, when the answer is ready. Only nodes
  the run served and edges its expansions returned (named from either end) are accepted; the
  rest come back under `refused`, and a cite that accepts nothing records nothing. The response is
  `{run, operation, cited: {nodes, relationships}, refused: {nodes, relationships}}`. `edges` is
  its own parameter because `relationships` is a list of names for `expand`.
- **Kinds.** An ontology term's card has `entityType` `ontologyConcept` or `ontologyProperty`,
  from its declared types; catalog entities keep the type in their IRI.
- **Labels.** A node with no label, display name or qualified name (a table constraint, lineage
  details, a constraint member) is labelled with its om: class and the value that tells it apart:
  `TableConstraint PRIMARY_KEY`, `LineageDetails Manual`.

## Amendment 2026-11-18. The catalog half of find reads a question word by word

Keyword search needs every word of its query, so a sentence found no tables. The catalog half now
reads the question with the word rule the ontology half uses (camel case and punctuation split
words, plurals fold, words under three characters and common words drop).

- A question with no such word searches nothing and gets no catalog cards.
- Semantic search, when enabled, still reads the whole question.
- Keyword search runs once with the whole question, as before, and, when the question has more
  than one word, once per word, at most 8. Each search runs as the caller with the caller's
  `entityTypes` and `limit`; each word is the plain query text, never joined into query syntax.
- Hits are ordered by how many of the words found them (a hit of the whole question counts as
  all of them), then by the best position any search gave them, then by when they were first
  found. The merge with ontology terms, the `limit`, the budget and `moreMatched` are unchanged.

Cost: up to nine searches per `find`. A generic word ("data") counts as much as a specific one.

## Amendment 2026-11-18. A server can offer knowledge_graph alone

A host that loads tools by searching their descriptions (Claude Desktop) picked five older tools
for "customer lifetime value" and never loaded `knowledge_graph`, so the server could not offer
the single entry point this tool exists to be.

- **`mcpOfferedTools`** in the server YAML, from `MCP_OFFERED_TOOLS`: a comma-separated list of
  tool names, blank by default. Blank offers every tool, exactly as before. Set, `tools/list`
  lists only those tools and a `tools/call` of any other is answered with an error, status 400,
  saying the tool "is not offered by this MCP server", so a client reads it as neither an unknown
  tool nor a permission failure. Read-only mode and `disabledTools` still apply to what is offered.
- **A deployment setting, not an admin one.** It is read once at start, where an admin setting in
  the database is reloaded live and keeps names it does not know. A name the server does not
  register (a typo, or `knowledge_graph` with `rdf.enabled` false) stops the start with
  `InvalidMcpConfigurationException`, which `registerMCPServer` rethrows, instead of a server
  that offers nothing.
- **Instructions.** When the list is `knowledge_graph` alone, `initialize` carries a short text
  (`knowledge-graph-instructions.txt`) with the loop: find in plain words, read the cards,
  expand, query only for what cards cannot give, cite once.
- **Description.** The tool description is written for a model that has no other tool, within
  1,600 characters (it was 2,390, now 1,479); `RdfToolDescriptionVocabularyTest` holds both.

## Amendment 2026-11-18. With semantic search on, the catalog half is one hybrid search

With embeddings switched on, the catalog half sent the question to semantic search alone, and the
column index holds no embeddings, so no column card came back; with them off, it matched words
alone. Each mode found what the other missed, and the caller could not tell which ran.

- **Semantic search on, OpenSearch:** one hybrid search, `SearchRepository.hybridSearch`. Its word
  leg is the data asset query the search settings build (`OpenSearchSourceBuilderFactory`), its
  KNN leg `VectorSearchQueryBuilder.buildHybridQuery`, ranked by the RRF pipeline inlined from
  `getHybridRrfPipelineDefinition` (the configured keyword and semantic weights). It searches the
  `dataAssetEmbeddings` alias and the column index together; the KNN leg sets `ignore_unmapped`,
  so a shard without embeddings contributes its word matches instead of failing the query. Hits
  fold to one per entity in rank order. Each is kept only if the caller may view it, a column by
  its table.
- **Semantic search on, Elasticsearch** (no hybrid ranking there): `semantic_search` and the word
  search both run, and their hits alternate, meaning first, each entity once.
- **Semantic search off:** the word search alone, as before.
- **Failure.** When the hybrid search or `semantic_search` fails, the word search answers alone and
  the find response carries `semanticSearchSkipped` with the reason.
- **Order.** The catalog hits, in whichever order the path gives them, alternate with ontology
  terms as before, a term first; limits, the budget and `moreMatched` are unchanged.

`SearchRepository.hybridSearch(query, indexes, filters, size, subject)` and
`OpenSearchVectorService.hybridSearch(HybridSearchRequest, pipeline)` are the one hybrid search in
OpenMetadata, placed so the commercial `HybridSearchService` can call them instead of its own
request assembly. For that it would need the request to carry what it adds today: a lexical leg
of its own (the shard-fair query), the governance `function_score` wrapper on the KNN leg,
aggregations, `from` and `preference`, and its parent-grouping window. Only a live OpenSearch
cluster with embeddings can show the ranking itself; the unit tests cover the request and the
choice of path.
