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
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.mcp.util.PageCursor;
import org.openmetadata.mcp.util.ResponseBudget;
import org.openmetadata.schema.api.configuration.MCPConfiguration;
import org.openmetadata.schema.api.rdf.RdfProjectionState;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.agents.SubAgentDefinition;
import org.openmetadata.service.aicontext.agents.SubAgentRegistry;
import org.openmetadata.service.aicontext.agents.SubAgentResult;
import org.openmetadata.service.aicontext.harness.AgentHarnessCompiler;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.rdf.RdfProjectionStateResolver;
import org.openmetadata.service.rdf.RdfRepository;
import org.openmetadata.service.rdf.RdfSparqlService;
import org.openmetadata.service.rdf.RdfUtils;
import org.openmetadata.service.rdf.SparqlQueryExecutionGuard;
import org.openmetadata.service.rdf.SparqlQueryExecutionGuard.QueryTimeoutException;
import org.openmetadata.service.rdf.SparqlQueryLimits.OutputLimitExceededException;
import org.openmetadata.service.rdf.explore.Citation;
import org.openmetadata.service.rdf.explore.CiteOutcome;
import org.openmetadata.service.rdf.explore.ExpandPage;
import org.openmetadata.service.rdf.explore.ExpandRequest;
import org.openmetadata.service.rdf.explore.ExpansionGroup;
import org.openmetadata.service.rdf.explore.FindRequest;
import org.openmetadata.service.rdf.explore.FindResult;
import org.openmetadata.service.rdf.explore.GraphMemories;
import org.openmetadata.service.rdf.explore.KnowledgeGraphCard;
import org.openmetadata.service.rdf.explore.KnowledgeGraphCards;
import org.openmetadata.service.rdf.explore.KnowledgeGraphExplorer;
import org.openmetadata.service.rdf.explore.LearnedOutcome;
import org.openmetadata.service.rdf.explore.MemoryEntry;
import org.openmetadata.service.rdf.explore.MemoryListing;
import org.openmetadata.service.rdf.explore.MemoryNote;
import org.openmetadata.service.rdf.explore.MemoryWriter;
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
import org.openmetadata.service.security.auth.SecurityConfigurationManager;

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

  /** Room kept for the card a host draws, which travels in the same response. */
  private static final long CARD_RESERVE = 6_000;

  /** Room for a field's name and separators. */
  private static final long FIELD_CHARS = 24;

  /** Room for the note on memories, kept whenever notes or preferences are sent. */
  private static final long NOTE_CHARS = MemoryNote.TEXT.length() + FIELD_CHARS;

  private final SparqlQueryTool sparqlQuery;
  private final StartingPoints startingPoints;
  private final Supplier<RunTraceStore> traces;
  private final Clock clock;
  private final MemoryWriters writers;
  private final Harnesses harnesses;
  private final SubAgentDelegation delegation;

  /** What the caller's effective agent harness lets this tool do. */
  @FunctionalInterface
  interface Harnesses {
    KnowledgeGraphHarness forCaller(Authorizer authorizer, CatalogSecurityContext securityContext);
  }

  /** The writer that keeps what a turn learnt, for one caller. */
  @FunctionalInterface
  interface MemoryWriters {
    MemoryWriter forCaller(
        Authorizer authorizer, CatalogSecurityContext securityContext, String baseUri);
  }

  /** A read-only server keeps nothing a turn learnt; the rest of the tool still answers. */
  static final String READ_ONLY = "this MCP server is read-only, so nothing is saved.";

  public KnowledgeGraphTool() {
    this(null);
  }

  /** The tool as the MCP server runs it, with the entity limits a saved memory counts against. */
  public KnowledgeGraphTool(final Limits limits) {
    this(
        RdfRepository::getInstanceOrNull,
        RdfProjectionStateResolver::resolveConfigured,
        new CatalogStartingPoints(),
        () ->
            new SqlRunTraceStore(
                Entity.getCollectionDAO().rdfExplorationRunDAO(), Clock.systemUTC()),
        Clock.systemUTC(),
        (authorizer, caller, baseUri) ->
            isReadOnly() || limits == null
                ? MemoryWriter.refusing(READ_ONLY)
                : new MemoryCapture(authorizer, limits, caller, baseUri),
        KnowledgeGraphTool::harnessOf);
  }

  /** The caller's harness settings; the defaults when no harness can be read. */
  private static KnowledgeGraphHarness harnessOf(
      final Authorizer authorizer, final CatalogSecurityContext caller) {
    try {
      final AgentHarnessCompiler compiler = GetContextTool.compiler(authorizer);
      return KnowledgeGraphHarness.of(
          compiler.effectiveFor(caller),
          personaPrompt(compiler, caller),
          SubAgentRegistry.getInstance());
    } catch (RuntimeException unreadable) {
      LOG.warn("[KnowledgeGraph] answered with the default harness: {}", unreadable.getMessage());
      return KnowledgeGraphHarness.DEFAULTS;
    }
  }

  private static String personaPrompt(
      final AgentHarnessCompiler compiler, final CatalogSecurityContext caller) {
    try {
      return compiler.personaPromptFor(caller);
    } catch (RuntimeException unreadable) {
      LOG.warn("[KnowledgeGraph] answered without the persona prompt: {}", unreadable.getMessage());
      return null;
    }
  }

  private static boolean isReadOnly() {
    final MCPConfiguration config = SecurityConfigurationManager.getCurrentMcpConfig();
    return config != null && Boolean.TRUE.equals(config.getReadOnly());
  }

  KnowledgeGraphTool(
      final Supplier<RdfRepository> repositorySupplier,
      final Supplier<RdfProjectionState> projectionStateSupplier,
      final StartingPoints startingPoints,
      final Supplier<RunTraceStore> traces,
      final Clock clock) {
    this(
        repositorySupplier,
        projectionStateSupplier,
        startingPoints,
        traces,
        clock,
        (authorizer, caller, baseUri) -> MemoryWriter.refusing(READ_ONLY));
  }

  KnowledgeGraphTool(
      final Supplier<RdfRepository> repositorySupplier,
      final Supplier<RdfProjectionState> projectionStateSupplier,
      final StartingPoints startingPoints,
      final Supplier<RunTraceStore> traces,
      final Clock clock,
      final MemoryWriters writers) {
    this(
        repositorySupplier,
        projectionStateSupplier,
        startingPoints,
        traces,
        clock,
        writers,
        (authorizer, caller) -> KnowledgeGraphHarness.DEFAULTS);
  }

  KnowledgeGraphTool(
      final Supplier<RdfRepository> repositorySupplier,
      final Supplier<RdfProjectionState> projectionStateSupplier,
      final StartingPoints startingPoints,
      final Supplier<RunTraceStore> traces,
      final Clock clock,
      final MemoryWriters writers,
      final Harnesses harnesses) {
    super(repositorySupplier, projectionStateSupplier);
    this.writers = Objects.requireNonNull(writers);
    this.harnesses = Objects.requireNonNull(harnesses);
    this.delegation =
        new SubAgentDelegation(SubAgentRegistry.getInstance(), SubAgentDelegation.WAIT, clock);
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
      permits FindResponse,
          ExpandResponse,
          QueryResponse,
          TraceResponse,
          CiteResponse,
          DelegateResponse,
          Opening,
          WithCard {
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

  /**
   * What a cite recorded, what it refused because the run never reached it, and what became of
   * each thing the turn learnt ({@code learned}, absent when it carried none).
   */
  public record CiteResponse(
      UUID run,
      Operation operation,
      Citation cited,
      Citation refused,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<LearnedOutcome> learned)
      implements Response {}

  /** What a sub-agent answered, or that it is still running under {@code result.handle}. */
  public record DelegateResponse(UUID run, Operation operation, SubAgentResult result)
      implements Response {}

  /**
   * The first answer of a run, with the sub-agents the caller's harness enables: {@code delegate}
   * hands one of them a task.
   */
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record Opening(
      @JsonUnwrapped Response answer,
      KnowledgeGraphHarness.Guidance harness,
      List<SubAgentDefinition> subAgents,
      String aboutSubAgents)
      implements Response {
    static final String ABOUT =
        "When one of these sub-agents fits the task (its 'whenToUse' says when; for analytics, the"
            + " person wants numbers from the warehouse), hand the task to it with delegate instead"
            + " of writing SQL or SPARQL yourself, and say in the answer that the agent ran it."
            + " delegate takes 'agent', 'task' and optional card 'nodes'; it runs as the person,"
            + " with their access. A 'running' result carries a 'handle': call delegate with it"
            + " later.";

    static Response of(final Response answer, final KnowledgeGraphHarness harness) {
      final boolean agents = !harness.agents().isEmpty();
      return agents || harness.guidance() != null
          ? new Opening(answer, harness.guidance(), harness.agents(), agents ? ABOUT : null)
          : answer;
    }

    @Override
    public UUID run() {
      return answer.run();
    }

    @Override
    public Operation operation() {
      return answer.operation();
    }
  }

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
  /**
   * The answer with its card as one member ({@code card}), so a host's model reads the whole answer
   * whichever part of the result it takes. A card that would not fit the response is left out.
   */
  public Map<String, Object> executeWithCard(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final Map<String, Object> params)
      throws IOException {
    final Response response = execute(authorizer, securityContext, params);
    final KnowledgeGraphCard card = card(securityContext, response, params);
    final boolean fits = card != null && chars(response) + chars(card) <= responseLimit();
    return JsonUtils.getMap(fits ? new WithCard(response, card) : response);
  }

  /** An answer and the card a host draws from it. */
  public record WithCard(@JsonUnwrapped Response answer, KnowledgeGraphCard card)
      implements Response {
    @Override
    public UUID run() {
      return answer.run();
    }

    @Override
    public Operation operation() {
      return answer.operation();
    }
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
      case CiteResponse cite -> KnowledgeGraphCards.withSaved(
          runCard(securityContext, Operation.CITE, cite.run()), cite.learned());
      case TraceResponse trace -> runCard(securityContext, Operation.TRACE, trace.run());
      case DelegateResponse delegate -> runCard(
          securityContext, Operation.DELEGATE, delegate.run());
      case Opening opening -> cardOf(securityContext, opening.answer(), sparql);
      case WithCard carded -> carded.card();
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
    final KnowledgeGraphHarness harness = harnesses.forCaller(authorizer, securityContext);
    final Response answer =
        switch (call.request()) {
          case KnowledgeGraphCall.Find find -> find(
              authorizer, securityContext, repository, new Turn(run, budget(), firstCall), find);
          case KnowledgeGraphCall.Expand expand -> expand(
              securityContext, repository, new Turn(run, budget(), firstCall), expand);
          case KnowledgeGraphCall.Query query -> query(
              securityContext, repository, new Turn(run, budget(), firstCall), harness, query);
          case KnowledgeGraphCall.Trace ignored -> trace(securityContext, repository, run);
          case KnowledgeGraphCall.Cite cite -> cite(
              authorizer, securityContext, repository, run, harness, cite);
          case KnowledgeGraphCall.Delegate delegate -> new DelegateResponse(
              run.run(),
              Operation.DELEGATE,
              delegation.delegate(securityContext, harness, run, traces.get(), delegate));
        };
    return firstCall ? Opening.of(answer, harness) : answer;
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
                find.question(),
                find.termKinds(),
                find.limit(),
                turn.budget() - memoryChars,
                matches,
                preferences),
            catalog.hits());
    return findResponse(run, find, found, catalog, new FindMemories(found.notes(), preferences));
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
        explorer.expand(run, expandRequest(expand, turn.budget() - memoryChars, preferences));
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
    return responseLimit() - ENVELOPE_RESERVE - CARD_RESERVE;
  }

  private static long responseLimit() {
    return Math.min(DEFAULT_RESPONSE_CHARS, ResponseBudget.defaultBudgetChars());
  }

  private static ExpandRequest expandRequest(
      final KnowledgeGraphCall.Expand expand,
      final long budget,
      final List<MemoryEntry> preferences) {
    final OntologyVocabulary vocabulary = OntologyVocabulary.canonical();
    return new ExpandRequest(
        expand.nodes(),
        expand.relationships().stream().map(vocabulary::expand).toList(),
        expand.directions(),
        expand.limit(),
        expand.offset(),
        budget,
        preferences);
  }

  private QueryResponse query(
      final CatalogSecurityContext securityContext,
      final RdfRepository repository,
      final Turn turn,
      final KnowledgeGraphHarness harness,
      final KnowledgeGraphCall.Query query)
      throws IOException {
    if (!harness.rawQueries()) {
      throw new IllegalArgumentException(KnowledgeGraphHarness.RAW_QUERIES_OFF);
    }
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
    explorer.recordQuery(run, sparql.sparql(), fitted.result().body(), preferences);
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
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final RdfRepository repository,
      final RunContext run,
      final KnowledgeGraphHarness harness,
      final KnowledgeGraphCall.Cite cite) {
    final MemoryWriter writer =
        harness.learnRefusal() == null
            ? writers.forCaller(authorizer, securityContext, repository.getBaseUri())
            : MemoryWriter.refusing(harness.learnRefusal());
    final CiteOutcome result =
        explorer(securityContext, repository).cite(run, cite.citation(), cite.learned(), writer);
    return new CiteResponse(
        run.run(), Operation.CITE, result.cited(), result.refused(), result.learned());
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
