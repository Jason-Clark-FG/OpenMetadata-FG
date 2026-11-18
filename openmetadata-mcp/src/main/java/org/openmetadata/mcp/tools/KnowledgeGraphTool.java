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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.openmetadata.mcp.util.PageCursor;
import org.openmetadata.mcp.util.ResponseBudget;
import org.openmetadata.schema.api.rdf.RdfProjectionState;
import org.openmetadata.service.Entity;
import org.openmetadata.service.rdf.RdfProjectionStateResolver;
import org.openmetadata.service.rdf.RdfRepository;
import org.openmetadata.service.rdf.RdfSparqlService;
import org.openmetadata.service.rdf.SparqlQueryExecutionGuard;
import org.openmetadata.service.rdf.explore.Citation;
import org.openmetadata.service.rdf.explore.CiteResult;
import org.openmetadata.service.rdf.explore.ExpandPage;
import org.openmetadata.service.rdf.explore.ExpandRequest;
import org.openmetadata.service.rdf.explore.ExpansionGroup;
import org.openmetadata.service.rdf.explore.FindRequest;
import org.openmetadata.service.rdf.explore.FindResult;
import org.openmetadata.service.rdf.explore.KnowledgeGraphExplorer;
import org.openmetadata.service.rdf.explore.NodeCard;
import org.openmetadata.service.rdf.explore.OntologyVocabulary;
import org.openmetadata.service.rdf.explore.Operation;
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
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * One tool over the knowledge graph: {@code find} starting nodes, {@code expand} relationships,
 * {@code query} with SPARQL, and {@code trace} a run. Access, readiness and read bounds are those
 * of the sibling RDF tools; {@code query} is {@code sparql_query} itself. See
 * ADR:2026-11-17-one-knowledge-graph-tool-explores-through-node-cards.
 */
public class KnowledgeGraphTool extends RdfMcpTool<KnowledgeGraphTool.Response> {

  private static final String SPARQL_QUERY_ARGUMENT = "query";

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
  public record FindResponse(
      UUID run,
      Operation operation,
      List<NodeCard> nodes,
      Map<String, RelationshipAbout> properties,
      int moreMatched,
      String termLookupSkipped,
      String semanticSearchSkipped)
      implements Response {}

  /** {@code properties} as in find; {@code nextCursor} is null on the last page. */
  public record ExpandResponse(
      UUID run,
      Operation operation,
      List<ExpansionGroup> expansions,
      List<NodeCard> nodes,
      Map<String, RelationshipAbout> properties,
      String nextCursor)
      implements Response {}

  public record QueryResponse(UUID run, Operation operation, SparqlQueryTool.Result result)
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

  @Override
  protected Response executeAuthorized(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final Map<String, Object> params)
      throws IOException {
    final KnowledgeGraphCall call = KnowledgeGraphCall.parse(params);
    final RdfRepository repository = repository();
    final RunContext run = new RunContext(call.runOrNew(), principalOf(securityContext));
    return switch (call.request()) {
      case KnowledgeGraphCall.Find find -> find(authorizer, securityContext, repository, run, find);
      case KnowledgeGraphCall.Expand expand -> expand(securityContext, repository, run, expand);
      case KnowledgeGraphCall.Query query -> query(securityContext, repository, run, query);
      case KnowledgeGraphCall.Trace ignored -> trace(securityContext, repository, run);
      case KnowledgeGraphCall.Cite cite -> cite(securityContext, repository, run, cite);
    };
  }

  @Override
  protected Response executeAuthorized(
      final CatalogSecurityContext securityContext, final Map<String, Object> params) {
    throw new UnsupportedOperationException("knowledge_graph needs the authorizer for find");
  }

  private FindResponse find(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final RdfRepository repository,
      final RunContext run,
      final KnowledgeGraphCall.Find find)
      throws IOException {
    requireReadyProjectionForNonAdmin(securityContext);
    final CatalogHits catalog = catalogHits(authorizer, securityContext, find);
    final FindResult found =
        explorer(securityContext, repository)
            .find(
                run,
                new FindRequest(
                    find.question(),
                    find.termKinds(),
                    find.limit(),
                    ResponseBudget.defaultBudgetChars()),
                catalog.hits());
    return new FindResponse(
        run.run(),
        Operation.FIND,
        found.nodes(),
        found.properties(),
        found.moreMatched(),
        found.termLookupSkipped(),
        catalog.semanticSearchSkipped());
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
      final RunContext run,
      final KnowledgeGraphCall.Expand expand) {
    requireReadyProjectionForNonAdmin(securityContext);
    final ExpandPage page =
        explorer(securityContext, repository).expand(run, expandRequest(expand));
    return new ExpandResponse(
        run.run(),
        Operation.EXPAND,
        page.expansions(),
        page.nodes(),
        page.properties(),
        page.nextOffset() == null ? null : PageCursor.encodeOffset(page.nextOffset()));
  }

  private static ExpandRequest expandRequest(final KnowledgeGraphCall.Expand expand) {
    final OntologyVocabulary vocabulary = OntologyVocabulary.canonical();
    return new ExpandRequest(
        expand.nodes(),
        expand.relationships().stream().map(vocabulary::expand).toList(),
        expand.directions(),
        expand.limit(),
        expand.offset(),
        ResponseBudget.defaultBudgetChars());
  }

  private QueryResponse query(
      final CatalogSecurityContext securityContext,
      final RdfRepository repository,
      final RunContext run,
      final KnowledgeGraphCall.Query query)
      throws IOException {
    final SparqlQueryTool.Result result =
        sparqlQuery.executeAuthorized(
            securityContext, Map.of(SPARQL_QUERY_ARGUMENT, query.sparql()));
    explorer(securityContext, repository).recordQuery(run, query.sparql(), result.body());
    return new QueryResponse(run.run(), Operation.QUERY, result);
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
