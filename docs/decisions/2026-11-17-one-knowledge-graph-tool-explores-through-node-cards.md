# One knowledge-graph tool explores the graph through self-describing node cards

- **Status:** Accepted
- **Revisions:** v1 2026-11-17 (initial) · v2 2026-11-18 (find matches ontology terms by label) · v3 2026-11-18 (every stored value on the card, descriptions once per response) · v4 2026-11-18 (cite, term kinds, labels for unnamed nodes) · v5 2026-11-18 (the catalog half of find reads a question word by word) · v6 2026-11-18 (a server can offer knowledge_graph alone) · v7 2026-11-18 (with semantic search on, the catalog half is one hybrid search) · v8 2026-11-18 (answers fit the smallest client; cheaper cards) · v9 2026-11-18 (hybrid legs ranked over their whole scope; find covers the data asset types) · v10 2026-11-18 (a query serves the nodes it names) · v11 2026-11-18 (cards say how healthy and how trusted a node is) · v12 2026-11-18 (the query path does not mislead) · v13 2026-11-18 (find reflects trust and symptom questions) · v14 2026-11-18 (the description and instructions name the signals) · v15 2026-11-18 (trust is stated on the card, not in the rank) · v16 2026-11-19 (a listing is complete or says what it left out) · v17 2026-11-19 (the response size is the server's) · v18 2026-11-19 (a status relationship shows the status it points to)
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
  so a shard without embeddings contributes its word matches instead of failing the query. Both
  legs carry the caller's search access filter, built by `OpenSearchRbacQueries` exactly as keyword
  search builds it (none for an admin or with search access control off, match-none for a caller
  whose policies allow nothing); a filter that cannot be built fails the hybrid search, so `find`
  falls back to the word search and says so. Hits fold to one per entity in rank order. Each is
  kept only if the caller may view it, a column by its table.
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

## Amendment 2026-11-18. Answers fit the smallest client; cards are cheaper

A `find` with `limit: 10` came back at 49.8 KB. Claude Code saved it to a file and showed the
model a preview, so the model never read a card, never saw `reachable` and never expanded.

- **Client limits.** Claude Code saves a tool result to a file instead of showing it "once it's
  longer than 50,000 characters, whatever its token count", independently of
  `MAX_MCP_OUTPUT_TOKENS` (default 25,000 tokens); a tool can raise its own threshold with
  `_meta["anthropic/maxResultSizeChars"]`, up to 500,000 characters
  (code.claude.com/docs/en/mcp, "MCP output limits and warnings"). claude.ai and Claude Desktop
  connectors take a tool result of about 150,000 characters (claude.com/docs/connectors/building,
  "Design within the size and timeout limits"). The smallest is 50,000 characters.
- **Default size.** Every `find` and `expand` answer fits 40,000 characters unless the caller asks
  for more with `maxChars`, which the server caps at its own budget (80% of `maxResponseChars`).
  Fewer cards come back and `moreMatched` or `nextCursor` says so; 1,000 characters are kept for
  the envelope.
- **`notCovered` once per kind.** It is no longer on each card: a response carries one
  `notCovered` group (`types`, `properties`) for each distinct set of card types.
- **Bookkeeping by name.** Values that record how a node is kept rather than what it is
  (`dct:hasVersion`, `dcat:version`, `dct:modified`, `dct:created`, `dct:publisher`, `om:isDeleted`,
  `om:deleted`, `om:updatedAt`, `om:updatedBy`, `om:version`, `om:href`, `om:fqnHash`,
  `om:processedLineage`) are listed by name under `bookkeeping`, described in `properties` and read
  whole with `query`. Nothing is dropped.

Measured on tables with 30 stored, described relationships: a card went from 5,570 characters
(`notCovered` 3,136 of them, `reachable` 2,019, facts 185, identity 128) to 2,420; a default `find`
of 10 such tables from 10 cards in 62,773 characters (over Claude Code's limit) to 10 cards in
34,461. A glossary term card went from 1,634 to 660.

## Amendment 2026-11-18. Hybrid legs are ranked over their whole scope; find covers data assets

On a live OpenSearch 3.4.0 server the v7 hybrid search answered three data questions with seeded
AI-governance frameworks and policies, and with no glossary term or column. Two causes. The
`score-ranker-processor` ranks each leg of a `hybrid` query within each shard, and the
`dataAssetEmbeddings` alias is about thirty single-shard indexes, so every index's best KNN hit
ranked first and all of them tied (`0.4 / 31`), ordered by index name. And the word leg sent the
whole sentence to the data asset query, whose clauses need every word, so it matched nothing. This
amendment replaces the v7 bullet for OpenSearch.

- **Two searches, fused here.** The word leg and the KNN leg are each an ordinary search, ranked
  over their whole scope, and `HybridRanking` fuses them by reciprocal rank with the configured
  keyword and semantic weights and the pipeline's rank constant (30,
  `OpenSearchVectorService.RRF_RANK_CONSTANT`). No cluster pipeline ranks them.
- **Words.** The word leg asks the data asset query for the whole question or any of its words (the
  words v5 reads), so a question matches what its words name.
- **Scope.** Both legs are held to the types the caller asked for, else to the data asset types:
  the members of the `dataAsset` alias, which keyword search covers (tables, columns, glossary
  terms, dashboards, charts, data models, metrics, pipelines, topics, tags and the rest). Test
  cases and suites, governance frameworks and policies, domains, glossaries and context memory are
  outside it; a caller reaches them by naming their type, and test cases from a table along
  `om:testsEntity` (incoming). The word leg searches the global alias, the KNN leg the embeddings
  alias, each filtered by the scope; the KNN leg asks only for the types that carry embeddings
  (`AvailableEntityTypes`) and is skipped when none does.
- **Ranking.** A type without embeddings (a column, a tag) cannot be found by meaning, so its word
  rank stands for both legs instead of counting as a miss. A column found by words is a word match
  for its table too.
- **Kinds take turns.** `find` asks for three hybrid hits per card it can return and orders them by
  kind: each kind's best hit in rank order, then each kind's second. A term, a table and a column are
  different ways into one question; a run of one kind crowded the others out.

`HybridRankingTest` holds the fusion rules with the live rankings of one question;
`OpenSearchHybridSearchTest` holds the two requests, their scope and filters, and that neither is a
`hybrid` query or names a pipeline; `HybridSearchScopeTest` holds the default scope against the
shipped index mapping. The v7 entry points are now `SearchRepository.hybridSearch(question, words,
entityTypes, size, subject)` and `OpenSearchVectorService.hybridSearch(HybridSearchRequest,
HybridRanking)`.

## Amendment 2026-11-18. A query serves the nodes it names

`cite` refused a column the model had read with `SELECT ?p ?o WHERE { <column> ?p ?o }`: the query
step recorded only the IRIs its rows bound, and no row binds the subject the query was about. A
query step now also serves the IRIs its pattern names as a subject, an object or a `VALUES` entry,
when it returned rows.

## Amendment 2026-11-18. Cards say how healthy and how trusted a node is

In 56 sessions no run mentioned the failing test or open incident on the table it was asked about:
the card said `om:testsEntity` 11 and nothing more.

- **Status counts.** The ontology marks, with the annotation `om:statusProperty`, the properties
  that say how a node of a class stands: `om:latestResultStatus` and `om:hasOpenIncident` for
  `om:TestCase`, `om:incidentState` for `om:TestCaseIncident`. A `reachable` entry whose far nodes
  are of such a class carries `status`, counting them by it: `property=value` for a value,
  `property` alone for a relationship they have. The `orders` card now reads
  `om:testsEntity incoming 11, status {om:hasOpenIncident 3, om:latestResultStatus=Failed 4,
  om:latestResultStatus=Success 7}`. The counts come from the same read as `reachable`; a new kind
  of node gets them by being annotated, not by code.
- **Trust in the facts.** `facts` carries `status` (`om:entityStatus`, such as Deprecated) beside
  `tier` and `certification`.

## Amendment 2026-11-18. The query path does not mislead

Of 176 calls in round two, 99 were `query`. Answers of about 90,000 characters were saved to a
file the model never read, and three empty answers became statements that something did not
exist.

- **Fitted.** `query` reads the whole answer (up to the store's own output cap) and keeps the
  leading rows that fit the response limit `find` and `expand` use. `rows` gives `returned`,
  `total` and, when rows were left out, `rest`: run it again with `ORDER BY` and `OFFSET`. A body
  that is not SELECT rows is cut at the limit as before. An answer that fits is the
  `sparql_query` result unchanged.
- **No rows.** An empty answer carries `noRows`: the properties and classes the query names that
  the ontology does not declare (in its own `om:` namespace), each with the closest declared term
  (a name containing the term's first, then at most two edits), and the declared or foreign terms
  that no triple in the graph uses, from one extra read. With such terms its `means` says the empty
  answer is not proof that nothing exists and asks for them to be corrected. Without them it says
  only what is true: nothing matched the whole pattern, which is not proof of absence because a
  filter, a literal value or a direction may be wrong, and an absence is confirmed on the node's
  card. A card guarantees that: its `reachable` lists every stored edge of the node in both
  directions (the read is never cut; a read over the store's bound fails, and a response that
  cannot hold a card drops the whole card), so a relationship missing from it and not under
  `notCovered` has no stored edge; `facts`, `values` and `bookkeeping` together name every stored
  value. Inferred edges are not on a card. The served nodes a step records are those of the rows
  sent.
- **Prefixes.** A query that does not parse and uses prefixes cards name terms with (the
  ontology's own `om:`, `dct:`, `prov:`, `skos:` and the rest) without declaring them gets their
  declarations, listed in `prefixesAdded`, only when the result parses. The completed text is what
  runs, through the same validation, so nothing a caller could not have written is accepted.

## Amendment 2026-11-18. Find reflects trust and symptom questions

- **Deprecated last.** Within each kind of hit, a Deprecated asset (`entityStatus`), or a column
  of a Deprecated table among the hits, comes after every hit of that kind that is not. For
  "customer lifetime value" the certified `customers` mart now leads the deprecated
  `customers_clean`, and its columns lead that table's look-alike column. It applies on every path
  `find` takes (hybrid, word search alone, meaning and words alternated, and the fallback after a
  failed hybrid search): the word search asks for `entityStatus` and `table`, and
  `semantic_search` hits carry `entityStatus`. A column document carries neither its own status
  nor its table's, so a column is judged only when its table came back from the same find.
- **Test cases by meaning.** With no types named, the meaning leg also searches test cases; the
  word leg does not. A symptom ("order volume looked low", "the freshness check is failing")
  matches a test case's description, while its name repeats words of its table and columns, which
  those hits already carry. A type searched by meaning alone is ranked by its meaning rank in both
  legs, as a type without embeddings is by its word rank. Incidents are not indexed: a test case
  card offers `om:hasOpenIncident` with the incident's state. Governance frameworks and policies,
  test suites, domains and glossaries stay out unless named. On the play server's data this puts a
  freshness or volume test first for the symptom questions and no test case in the first five for
  the three data questions.

## Amendment 2026-11-18. The description and instructions name the signals

The description (1,599 of its 1,600 characters) and the server instructions (under 800) now say
that facts carry status such as Deprecated, that `reachable` carries `status` counts for tests and
incidents (`om:latestResultStatus=Failed`, `om:hasOpenIncident`), that `expand` gets the other
side in one call (`om:testsEntity`, incoming, for a table's tests), and that a query's `rows` and
`noRows` say what was left out and why an empty answer is not an absence.
`RdfToolDescriptionVocabularyTest` and `McpOfferedToolsTest` hold both.

## Amendment 2026-11-18. Trust is stated on the card, not in the rank

The "Deprecated last" rule of v13 is removed on every path; `find` returns relevance order. On the
play server it turned "ranked lower" into "not returned": `find` "customers_clean" (tables only,
`limit` 20, `maxChars` 400,000) did not return `customers_clean`, the round-2 question about that
table lost its card from the first `find` (3 of 3 runs had it before), and the rule was not even
applied evenly. A question about a deprecated asset is when the catalog must show it, and round
one showed no answer lost to a deprecated look-alike ranking first. The card says
`status: Deprecated` in its facts; that is where trust is stated. Test cases stay searched by
meaning (v13).

## Amendment 2026-11-19. A listing is complete, or says in words what it left out

Round 3 (eight root-cause questions, three runs each) followed cards as intended and scored lower,
23 of 48 against 31: a page of cards is partial more often than the model notices. A default page
held 8 of `orders`' 11 tests, a lowered `maxChars` 1 to 3, and only `nextCursor` said so; three R8
runs each read 3 passing tests and none paged, and one R1 run wrote "No data-quality test covers
this case" from 2 of 11. One R2 run read five `find` cards with 11 more matched and wrote that the
catalog had nothing. R3 spelled `raw.raw_orders.ordered_at` and got a table of that name in
another dataset.

- **expand lists every far node.** Each group gives `total`, every far node in short form
  (`listed`: id, name, type, and the values of its class's `om:statusProperty`, such as
  `om:latestResultStatus` and `om:hasOpenIncident`), the ids carded on this page (`nodes`), and
  `rest`, a sentence for what is left out with the call that reads it. Lists are paid for before
  any card, one far node per group in turn, up to 200 per group; a list the response cannot hold
  says how many are listed and gives the exact query for the rest. A relationship asked with no
  far node says `total` 0. Listed nodes are served to the run, so `cite` accepts them.
- **find says what it left out.** `matched` counts what find collected, `rest` says how many have
  cards and to ask again with a larger limit, `entityTypes` or a more specific question.
- **A smaller size is the default.** `maxChars` below 40,000 is read as 40,000: in 20 of 38
  expands the model lowered it to save context and read a fragment. It now only asks for more.
- **A qualified name resolves exactly.** A dotted name in the question is read as the end of a
  fully qualified name; the assets it names, and the table of a named column, are looked up through
  the caller's own search and come first on every path.

## Amendment 2026-11-19. The response size is the server's

Round 4 (eight new questions, three runs per arm) showed the listings working: the tool arm was
fully right in 22 of 24 runs, and none of its five false "there is none" statements came from a
partial page. It also showed that the model cannot know its client's limit: told it could ask for
more, it sent `maxChars` 60,000 or 80,000 on ten queries, and one answer of 55,921 characters was
withheld by its client, as lowered sizes had cut listings in round 3. `maxChars` is gone from the
schema and the texts; every find, expand and query answer is fitted to 40,000 characters, never more
than 80% of the MCP server's `maxResponseChars` (the operator setting in the MCP configuration),
and a call that still sends `maxChars` is answered as if it had not.

## Amendment 2026-11-19. A status relationship shows the status it points to

In round 4 the strict score was lost in one repeated place: the model found the failing check,
saw `om:hasOpenIncident: true` in the listing, and did not make the second call for the assignee
and the severity (H1, H3, H4). A status property whose value is a node now shows, in a far node's
short entry, that node's id and the values of the status properties its class is marked with, from
the same read; a relationship there shows the name (`rdfs:label`) of the node it points to, and
nothing further. `om:TestCaseIncident` is marked with `om:incidentSeverity` and
`om:incidentAssignee` beside `om:incidentState`, so a card's `reachable` status counts on
`om:hasOpenIncident` count those too. On the play server the listing of `orders`' 11 tests grows
from 2,139 to 2,731 characters.
