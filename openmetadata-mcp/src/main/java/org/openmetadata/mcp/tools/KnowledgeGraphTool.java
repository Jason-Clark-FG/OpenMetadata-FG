/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.mcp.util.PageCursor;
import org.openmetadata.mcp.util.ResponseBudget;
import org.openmetadata.schema.api.rdf.RdfProjectionState;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.rdf.RdfProjectionStateResolver;
import org.openmetadata.service.rdf.RdfRepository;
import org.openmetadata.service.rdf.RdfSparqlService;
import org.openmetadata.service.rdf.RdfUtils;
import org.openmetadata.service.rdf.SparqlQueryExecutionGuard;
import org.openmetadata.service.rdf.SparqlQueryExecutionGuard.QueryTimeoutException;
import org.openmetadata.service.rdf.SparqlQueryLimits.OutputLimitExceededException;
import org.openmetadata.service.rdf.explore.Citation;
import org.openmetadata.service.rdf.explore.CiteResult;
import org.openmetadata.service.rdf.explore.ExpandPage;
import org.openmetadata.service.rdf.explore.ExpandRequest;
import org.openmetadata.service.rdf.explore.ExpansionGroup;
import org.openmetadata.service.rdf.explore.FindRequest;
import org.openmetadata.service.rdf.explore.FindResult;
import org.openmetadata.service.rdf.explore.GraphMemories;
import org.openmetadata.service.rdf.explore.KnowledgeGraphCard;
import org.openmetadata.service.rdf.explore.KnowledgeGraphCards;
import org.openmetadata.service.rdf.explore.KnowledgeGraphExplorer;
import org.openmetadata.service.rdf.explore.MemoryEntry;
import org.openmetadata.service.rdf.explore.MemoryListing;
import org.openmetadata.service.rdf.explore.MemoryNote;
import org.openmetadata.service.rdf.explore.NoRows;
import org.openmetadata.service.rdf.explore.NodeCard;
import org.openmetadata.service.rdf.explore.OntologyVocabulary;
import org.openmetadata.service.rdf.explore.Operation;
import org.openmetadata.service.rdf.explore.QueryPrefixes;
import org.openmetadata.service.rdf.explore.RelationshipAbout;
import org.openmetadata.service.rdf.explore.RunContext;
import org.openmetadata.service.rdf.explore.RunTrace;
import org.openmetadata.service.rdf.explore.RunTraceStore;
import org.openmetadata.service.rdf.explore.SqlRunTraceStore;
import org.openmetadata.service.rdf.explore.TraceEdge;
import org.openmetadata.service.rdf.explore.TraceNode;
import org.openmetadata.service.rdf.explore.TraceStep;
import org.openmetadata.service.rdf.federation.SparqlFederationGuard;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * One tool over the knowledge graph: {@code find} starting nodes, {@code expand} relationships,
 * {@code query} with SPARQL, and {@code trace} a run. Access, readiness and read bounds are those
 * of the sibling RDF tools; {@code query} is {@code sparql_query} itself. See
 * ADR:2026-11-17-one-knowledge-graph-tool-explores-through-node-cards.
 */
@Slf4j
public class KnowledgeGraphTool extends RdfMcpTool<KnowledgeGraphTool.Response> {

  /**
   * The largest response sent unless the caller asks for more: Claude Code saves a tool result
   * longer than 50,000 characters to a file instead of showing it, so this leaves a fifth spare.
   */
  public static final long DEFAULT_RESPONSE_CHARS = 40_000;

  /** Room the response keeps beyond its cards: run, operation, counts and skip notes. */
  private static final long ENVELOPE_RESERVE = 1_000;

  /** Room for a field's name and separators. */
  private static final long FIELD_CHARS = 24;

  /** Room for the note on memories, kept whenever notes or preferences are sent. */
  private static final long NOTE_CHARS = MemoryNote.TEXT.length() + FIELD_CHARS;

  private final SparqlQueryTool sparqlQuery;
  private final StartingPoints startingPoints;
  private final Supplier<RunTraceStore> traces;
  private final Clock clock;

  public KnowledgeGraphTool() {
    this(
        RdfRepository::getInstanceOrNull,
        RdfProjectionStateResolver::resolveConfigured,
        new CatalogStartingPoints(),
        () ->
            new SqlRunTraceStore(
                Entity.getCollectionDAO().rdfExplorationRunDAO(), Clock.systemUTC()),
        Clock.systemUTC());
  }

  KnowledgeGraphTool(
      final Supplier<RdfRepository> repositorySupplier,
      final Supplier<RdfProjectionState> projectionStateSupplier,
      final StartingPoints startingPoints,
      final Supplier<RunTraceStore> traces,
      final Clock clock) {
    super(repositorySupplier, projectionStateSupplier);
    this.sparqlQuery =
        new SparqlQueryTool(
            repositorySupplier,
            SparqlQueryExecutionGuard.shared()::execute,
            projectionStateSupplier);
    this.startingPoints = Objects.requireNonNull(startingPoints);
    this.traces = Objects.requireNonNull(traces);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Every response names its run, which the caller passes back on the next call. */
  public sealed interface Response
      permits FindResponse, ExpandResponse, QueryResponse, TraceResponse, CiteResponse {
    UUID run();

    Operation operation();
  }

  /**
   * {@code properties} says once what each property the cards name is. {@code moreMatched} counts
   * matched nodes left out. {@code termLookupSkipped} is present only when ontology terms could not
   * be looked for, so their absence proves nothing; {@code semanticSearchSkipped} when semantic
   * search was on and did not take part, so the catalog hits are word matches only.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  /**
   * {@code matched} counts what find collected, {@code nodes} the best of them that fit; {@code
   * rest} says in words what was left out and how to see it, and is absent when nothing was.
   */
  public record FindResponse(
      UUID run,
      Operation operation,
      List<NodeCard> nodes,
      Map<String, RelationshipAbout> properties,
      int matched,
      int moreMatched,
      @JsonInclude(JsonInclude.Include.NON_NULL) String rest,
      String termLookupSkipped,
      String semanticSearchSkipped,
      MemoryListing notes,
      String notesSkipped,
      List<MemoryEntry> preferences,
      String aboutMemories)
      implements Response {}

  /** {@code properties} as in find; {@code nextCursor} is null on the last page. */
  public record ExpandResponse(
      UUID run,
      Operation operation,
      List<ExpansionGroup> expansions,
      List<NodeCard> nodes,
      Map<String, RelationshipAbout> properties,
      String nextCursor,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<MemoryEntry> preferences,
      @JsonInclude(JsonInclude.Include.NON_NULL) String aboutMemories)
      implements Response {}

  /**
   * A query's answer: the rows that fit ({@code rows} says how many of how many and how to read the
   * rest), what the tool can tell when there are none ({@code noRows}), and the card prefixes it
   * declared for the query ({@code prefixesAdded}).
   */
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record QueryResponse(
      UUID run,
      Operation operation,
      SparqlQueryTool.Result result,
      QueryRows rows,
      NoRows noRows,
      List<String> prefixesAdded,
      List<MemoryEntry> preferences,
      String aboutMemories)
      implements Response {}

  /** What a cite recorded, and what it refused because the run never reached it. */
  public record CiteResponse(UUID run, Operation operation, Citation cited, Citation refused)
      implements Response {}

  public record TraceResponse(
      UUID run,
      Operation operation,
      List<TraceNode> nodes,
      List<TraceEdge> edges,
      List<TraceStep> steps)
      implements Response {}

  /**
   * The answer as text for the model, unchanged, and the card an MCP Apps host draws of it. A card
   * that cannot be built is left out; the answer never fails for it.
   */
  public CardResult executeWithCard(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final Map<String, Object> params)
      throws IOException {
    final Response response = execute(authorizer, securityContext, params);
    return new CardResult(response, card(securityContext, response, params));
  }

  private KnowledgeGraphCard card(
      final CatalogSecurityContext securityContext,
      final Response response,
      final Map<String, Object> params) {
    KnowledgeGraphCard card = null;
    try {
      card = cardOf(securityContext, response, Objects.toString(params.get("sparql"), ""));
    } catch (RuntimeException failure) {
      LOG.warn("[KnowledgeGraph] no card for {}: {}", response.operation(), failure.getMessage());
    }
    return card;
  }

  private KnowledgeGraphCard cardOf(
      final CatalogSecurityContext securityContext, final Response response, final String sparql) {
    return switch (response) {
      case FindResponse find -> KnowledgeGraphCards.ofCall(
          Operation.FIND, find.run(), find.nodes(), List.of(), shownBeside(find));
      case ExpandResponse expand -> KnowledgeGraphCards.ofCall(
          Operation.EXPAND, expand.run(), expand.nodes(), expand.expansions(), List.of());
      case QueryResponse query -> KnowledgeGraphCards.ofQuery(
          query.run(),
          sparql,
          query.rows() == null ? 0 : query.rows().returned(),
          query.rows() == null ? 0 : query.rows().total());
      case CiteResponse cite -> runCard(securityContext, Operation.CITE, cite.run());
      case TraceResponse trace -> runCard(securityContext, Operation.TRACE, trace.run());
    };
  }

  /** The whole run, with the cited nodes' cards read once more for their trust and memories. */
  private KnowledgeGraphCard runCard(
      final CatalogSecurityContext securityContext, final Operation operation, final UUID run) {
    final KnowledgeGraphExplorer explorer = explorer(securityContext, repository());
    final RunTrace trace = explorer.trace(run);
    final List<String> cited =
        trace.nodes().stream()
            .filter(node -> node.citedStep() != null)
            .map(TraceNode::id)
            .limit(KnowledgeGraphCards.MAX_MEMORIES)
            .toList();
    return KnowledgeGraphCards.ofRun(operation, trace, explorer.cards(cited));
  }

  private static List<KnowledgeGraphCard.Memory> shownBeside(final FindResponse find) {
    final List<KnowledgeGraphCard.Memory> shown = new ArrayList<>();
    if (find.notes() != null) {
      find.notes()
          .shown()
          .forEach(memory -> shown.add(KnowledgeGraphCards.memory(memory, null, "notes")));
    }
    if (find.preferences() != null) {
      find.preferences()
          .forEach(memory -> shown.add(KnowledgeGraphCards.memory(memory, null, "preference")));
    }
    return shown;
  }

  @Override
  protected Response executeAuthorized(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final Map<String, Object> params)
      throws IOException {
    final KnowledgeGraphCall call = KnowledgeGraphCall.parse(params);
    final RdfRepository repository = repository();
    final RunContext run = new RunContext(call.runOrNew(), principalOf(securityContext));
    final boolean firstCall = call.run() == null;
    return switch (call.request()) {
      case KnowledgeGraphCall.Find find -> find(
          authorizer, securityContext, repository, new Turn(run, budget(), firstCall), find);
      case KnowledgeGraphCall.Expand expand -> expand(
          securityContext, repository, new Turn(run, budget(), firstCall), expand);
      case KnowledgeGraphCall.Query query -> query(
          securityContext, repository, new Turn(run, budget(), firstCall), query);
      case KnowledgeGraphCall.Trace ignored -> trace(securityContext, repository, run);
      case KnowledgeGraphCall.Cite cite -> cite(securityContext, repository, run, cite);
    };
  }

  @Override
  protected Response executeAuthorized(
      final CatalogSecurityContext securityContext, final Map<String, Object> params) {
    throw new UnsupportedOperationException("knowledge_graph needs the authorizer for find");
  }

  /** A call's run, the characters its answer may fill, and whether it is the run's first. */
  private record Turn(RunContext run, long budget, boolean firstCall) {}

  /**
   * The caller's preferences on the first call of a run and on no other
   * (ADR:2026-11-17-one-knowledge-graph-tool-explores-through-node-cards, v19).
   */
  private static List<MemoryEntry> preferences(
      final Turn turn,
      final CatalogSecurityContext securityContext,
      final KnowledgeGraphExplorer explorer,
      final String baseUri) {
    final User caller = DefaultAuthorizer.getSubjectContext(securityContext).user();
    List<MemoryEntry> preferences = List.of();
    if (turn.firstCall() && caller != null && caller.getId() != null) {
      try {
        preferences =
            explorer
                .memories()
                .preferences(RdfUtils.entityUri(baseUri, Entity.USER, caller.getId()));
      } catch (QueryTimeoutException | OutputLimitExceededException failure) {
        LOG.warn(
            "[KnowledgeGraph] answered without the caller's preferences: {}", failure.getMessage());
      }
    }
    return preferences;
  }

  /** The characters a part of an answer takes, with its field name. */
  private static long chars(final Object part) {
    return part == null ? 0 : JsonUtils.pojoToJson(part).length() + FIELD_CHARS;
  }

  /** The note on memories, once, when the answer carries any. */
  private static String aboutMemories(final boolean carriesMemories) {
    return carriesMemories ? MemoryNote.TEXT : null;
  }

  private static List<MemoryEntry> orNull(final List<MemoryEntry> entries) {
    return entries.isEmpty() ? null : entries;
  }

  private FindResponse find(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final RdfRepository repository,
      final Turn turn,
      final KnowledgeGraphCall.Find find)
      throws IOException {
    final RunContext run = turn.run();
    requireReadyProjectionForNonAdmin(securityContext);
    final CatalogHits catalog = catalogHits(authorizer, securityContext, find);
    final KnowledgeGraphExplorer explorer = explorer(securityContext, repository);
    final List<MemoryEntry> preferences =
        preferences(turn, securityContext, explorer, repository.getBaseUri());
    final GraphMemories.Matches matches = explorer.memories().matching(find.question());
    final long offered = chars(orNull(preferences)) + chars(matches.beside(Set.of()).listing());
    final long memoryChars = offered == 0 ? 0 : offered + NOTE_CHARS;
    final FindResult found =
        explorer.find(
            run,
            new FindRequest(
                find.question(), find.termKinds(), find.limit(), turn.budget() - memoryChars),
            catalog.hits());
    final GraphMemories.Notes notes =
        matches.beside(found.nodes().stream().map(NodeCard::id).collect(Collectors.toSet()));
    return findResponse(run, find, found, catalog, new FindMemories(notes, preferences));
  }

  /** What a find carries of memories beside its cards. */
  private record FindMemories(GraphMemories.Notes notes, List<MemoryEntry> preferences) {}

  private static FindResponse findResponse(
      final RunContext run,
      final KnowledgeGraphCall.Find find,
      final FindResult found,
      final CatalogHits catalog,
      final FindMemories memories) {
    final int matched = found.nodes().size() + found.moreMatched();
    final MemoryListing notes = memories.notes().listing();
    return new FindResponse(
        run.run(),
        Operation.FIND,
        found.nodes(),
        found.properties(),
        matched,
        found.moreMatched(),
        findRest(matched, found.nodes().size(), find.limit()),
        found.termLookupSkipped(),
        catalog.semanticSearchSkipped(),
        notes,
        memories.notes().skipped(),
        orNull(memories.preferences()),
        aboutMemories(
            found.carriesMemories() || notes != null || !memories.preferences().isEmpty()));
  }

  private static String findRest(final int matched, final int shown, final int limit) {
    final String larger =
        limit < KnowledgeGraphCall.MAX_FIND_LIMIT
            ? "call find with a larger limit (up to %d), "
                .formatted(KnowledgeGraphCall.MAX_FIND_LIMIT)
            : "";
    return matched > shown
        ? ("find collected %d matches and shows cards for the best %d; the other %d are not shown"
                + " and may hold what you need. To see them, %sname entityTypes, or ask a more"
                + " specific question.")
            .formatted(matched, shown, matched - shown, larger)
        : null;
  }

  private CatalogHits catalogHits(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final KnowledgeGraphCall.Find find)
      throws IOException {
    return find.searchesCatalog()
        ? startingPoints.search(authorizer, securityContext, find)
        : CatalogHits.of(List.of());
  }

  private ExpandResponse expand(
      final CatalogSecurityContext securityContext,
      final RdfRepository repository,
      final Turn turn,
      final KnowledgeGraphCall.Expand expand) {
    requireReadyProjectionForNonAdmin(securityContext);
    final RunContext run = turn.run();
    final KnowledgeGraphExplorer explorer = explorer(securityContext, repository);
    final List<MemoryEntry> preferences =
        preferences(turn, securityContext, explorer, repository.getBaseUri());
    final long memoryChars = preferences.isEmpty() ? 0 : chars(preferences) + NOTE_CHARS;
    final ExpandPage page =
        explorer.expand(run, expandRequest(expand, turn.budget() - memoryChars));
    return new ExpandResponse(
        run.run(),
        Operation.EXPAND,
        page.expansions(),
        page.nodes(),
        page.properties(),
        page.nextOffset() == null ? null : PageCursor.encodeOffset(page.nextOffset()),
        orNull(preferences),
        aboutMemories(page.carriesMemories() || !preferences.isEmpty()));
  }

  /**
   * The characters every find, expand and query answer may fill: {@link #DEFAULT_RESPONSE_CHARS},
   * never more than the server's own budget (80% of the MCP server's {@code maxResponseChars}), less
   * the envelope's room. A caller cannot change it.
   */
  static long budget() {
    return Math.min(DEFAULT_RESPONSE_CHARS, ResponseBudget.defaultBudgetChars()) - ENVELOPE_RESERVE;
  }

  private static ExpandRequest expandRequest(
      final KnowledgeGraphCall.Expand expand, final long budget) {
    final OntologyVocabulary vocabulary = OntologyVocabulary.canonical();
    return new ExpandRequest(
        expand.nodes(),
        expand.relationships().stream().map(vocabulary::expand).toList(),
        expand.directions(),
        expand.limit(),
        expand.offset(),
        budget);
  }

  private QueryResponse query(
      final CatalogSecurityContext securityContext,
      final RdfRepository repository,
      final Turn turn,
      final KnowledgeGraphCall.Query query)
      throws IOException {
    final RunContext run = turn.run();
    final QueryPrefixes.Completed sparql =
        OntologyVocabulary.canonical().completePrefixes(query.sparql());
    final SparqlQueryTool.Result result =
        sparqlQuery.executeWhole(securityContext, sparql.sparql());
    final KnowledgeGraphExplorer explorer = explorer(securityContext, repository);
    final List<MemoryEntry> preferences =
        preferences(turn, securityContext, explorer, repository.getBaseUri());
    final long budget =
        turn.budget() - (preferences.isEmpty() ? 0 : chars(preferences) + NOTE_CHARS);
    final QueryRows.Fitted fitted =
        QueryRows.fit(
                result,
                candidate -> queryResponse(run, candidate, null, sparql),
                size -> size <= budget)
            .orElseGet(() -> new QueryRows.Fitted(bounded(result, budget), null));
    explorer.recordQuery(run, sparql.sparql(), fitted.result().body());
    final NoRows noRows =
        fitted.rows() != null && fitted.rows().isEmpty()
            ? explorer.explainNoRows(sparql.sparql())
            : null;
    return withPreferences(queryResponse(run, fitted, noRows, sparql), preferences);
  }

  private static QueryResponse withPreferences(
      final QueryResponse response, final List<MemoryEntry> preferences) {
    return preferences.isEmpty()
        ? response
        : new QueryResponse(
            response.run(),
            response.operation(),
            response.result(),
            response.rows(),
            response.noRows(),
            response.prefixesAdded(),
            preferences,
            MemoryNote.TEXT);
  }

  /** A body that is not SELECT rows, such as an ASK or a graph, cut at the answer's size. */
  private static SparqlQueryTool.Result bounded(
      final SparqlQueryTool.Result result, final long budget) {
    final RdfBody.Bounded body = RdfBody.bound(result.body(), (int) budget);
    return new SparqlQueryTool.Result(
        result.format(),
        result.queryType(),
        body.value(),
        result.truncated() || body.truncated(),
        result.byteCount(),
        result.warning(),
        result.completeness());
  }

  private static QueryResponse queryResponse(
      final RunContext run,
      final QueryRows.Fitted fitted,
      final NoRows noRows,
      final QueryPrefixes.Completed sparql) {
    return new QueryResponse(
        run.run(),
        Operation.QUERY,
        fitted.result(),
        fitted.rows(),
        noRows,
        sparql.added(),
        null,
        null);
  }

  private CiteResponse cite(
      final CatalogSecurityContext securityContext,
      final RdfRepository repository,
      final RunContext run,
      final KnowledgeGraphCall.Cite cite) {
    final CiteResult result = explorer(securityContext, repository).cite(run, cite.citation());
    return new CiteResponse(run.run(), Operation.CITE, result.cited(), result.refused());
  }

  private TraceResponse trace(
      final CatalogSecurityContext securityContext,
      final RdfRepository repository,
      final RunContext run) {
    final RunTrace trace = explorer(securityContext, repository).trace(run.run());
    return new TraceResponse(
        trace.run(), Operation.TRACE, trace.nodes(), trace.edges(), trace.steps());
  }

  /** Every graph read runs inside the caller's admission slot over asserted data, bounded. */
  private KnowledgeGraphExplorer explorer(
      final CatalogSecurityContext securityContext, final RdfRepository repository) {
    final RdfSparqlService sparql =
        new RdfSparqlService(repository, new SparqlFederationGuard(repository.getConfig()));
    return new KnowledgeGraphExplorer(
        query -> guardedRead(securityContext, () -> sparql.selectJsonWithoutInference(query)),
        OntologyVocabulary.canonical(),
        repository.getBaseUri(),
        traces.get(),
        clock);
  }

  private static String principalOf(final CatalogSecurityContext securityContext) {
    return securityContext.getUserPrincipal() == null
        ? null
        : securityContext.getUserPrincipal().getName();
  }
}
