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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openmetadata.schema.api.rdf.RdfProjectionState;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.rdf.OntologyDocument;
import org.openmetadata.service.rdf.RdfRepository;
import org.openmetadata.service.rdf.SparqlQueryExecutionGuard;
import org.openmetadata.service.rdf.SparqlQueryLimits;
import org.openmetadata.service.rdf.explore.Direction;
import org.openmetadata.service.rdf.explore.ExpansionGroup;
import org.openmetadata.service.rdf.explore.ExplorationState;
import org.openmetadata.service.rdf.explore.NoRows;
import org.openmetadata.service.rdf.explore.NodeCard;
import org.openmetadata.service.rdf.explore.OntologyVocabulary;
import org.openmetadata.service.rdf.explore.Operation;
import org.openmetadata.service.rdf.explore.RunStep;
import org.openmetadata.service.rdf.explore.RunTraceStore;
import org.openmetadata.service.rdf.explore.SearchHit;
import org.openmetadata.service.rdf.explore.TraceEdge;
import org.openmetadata.service.rdf.explore.TraceNode;
import org.openmetadata.service.rdf.explore.TraceStep;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

class KnowledgeGraphToolTest {

  private static final String E = JenaBackedRepository.BASE + "entity/";
  private static final UUID ORDERS_ID = UUID.fromString("0b0b0b0b-0000-0000-0000-000000000001");
  private static final UUID CUSTOMERS_ID = UUID.fromString("0b0b0b0b-0000-0000-0000-000000000002");
  private static final String ORDERS = E + "table/" + ORDERS_ID;
  private static final String CUSTOMERS = E + "table/" + CUSTOMERS_ID;
  private static final String GRAPH =
      """
      <%1$s> a om:Table ; rdfs:label "orders" ;
          om:fullyQualifiedName "warehouse.shop.public.orders" ;
          om:hasOwner e:team-growth ; om:hasTag e:tag-pii , e:tag-finance .
      <%2$s> a om:Table ; rdfs:label "customers" ;
          om:hasOwner e:team-crm ; om:hasTag e:tag-pii .
      e:team-growth a om:Team ; rdfs:label "growth" .
      e:team-crm a om:Team ; rdfs:label "crm" .
      e:tag-pii a om:Tag ; om:tagFQN "PII.Sensitive" .
      e:tag-finance a om:Tag ; om:tagFQN "Domain.Finance" .
      """
          .formatted(ORDERS, CUSTOMERS);
  private static final CatalogSecurityContext CALLER = RdfToolAuthorization.caller("analyst");
  private static final Authorizer GRANTED = mock(Authorizer.class);

  private final RecordingTraces traces = new RecordingTraces();
  private final RdfRepository repository = JenaBackedRepository.ofTurtle(GRAPH);
  private MockedStatic<DefaultAuthorizer> subjects;

  @AfterEach
  void releaseTheCallerResolution() {
    if (subjects != null) {
      subjects.close();
    }
  }

  private KnowledgeGraphTool tool(final Supplier<RdfProjectionState> projectionState) {
    return new KnowledgeGraphTool(
        () -> repository,
        projectionState,
        (authorizer, caller, find) ->
            CatalogHits.of(
                List.of(
                    new SearchHit("table", "warehouse.shop.public.orders", ORDERS_ID),
                    new SearchHit("table", "warehouse.shop.public.customers", CUSTOMERS_ID))),
        () -> traces,
        Clock.systemUTC());
  }

  @Test
  void aFindForCatalogTypesSearchesThemAndOneForOntologyTermsOnlySearchesNothing()
      throws IOException {
    callersAreAdministrators(false);
    final List<KnowledgeGraphCall.Find> searched = new ArrayList<>();
    final KnowledgeGraphTool tool =
        new KnowledgeGraphTool(
            () -> repository,
            () -> RdfProjectionState.READY,
            (authorizer, caller, find) -> {
              searched.add(find);
              return CatalogHits.of(
                  List.of(new SearchHit("table", "warehouse.shop.public.orders", ORDERS_ID)));
            },
            () -> traces,
            Clock.systemUTC());

    final KnowledgeGraphTool.FindResponse tables =
        (KnowledgeGraphTool.FindResponse)
            tool.execute(
                GRANTED,
                CALLER,
                Map.of("operation", "find", "question", "orders", "entityTypes", List.of("table")));
    final KnowledgeGraphTool.FindResponse terms =
        (KnowledgeGraphTool.FindResponse)
            tool.execute(
                GRANTED,
                CALLER,
                Map.of(
                    "operation",
                    "find",
                    "question",
                    "orders",
                    "entityTypes",
                    List.of("ontologyConcept")));

    assertThat(tables.nodes()).extracting(NodeCard::id).containsExactly(ORDERS);
    assertThat(searched)
        .extracting(KnowledgeGraphCall.Find::catalogTypes)
        .containsExactly(List.of("table"));
    assertThat(terms.nodes()).isEmpty();
  }

  private KnowledgeGraphTool.Response call(final Map<String, Object> arguments) throws IOException {
    return tool(() -> RdfProjectionState.READY).execute(GRANTED, CALLER, arguments);
  }

  private void callersAreAdministrators(final boolean administrators) {
    subjects = RdfToolAuthorization.resolvingCallersAs(administrators);
  }

  @Test
  void findReturnsCardsForTheSearchHitsAndMintsARun() throws IOException {
    callersAreAdministrators(false);

    final KnowledgeGraphTool.FindResponse response =
        (KnowledgeGraphTool.FindResponse) call(Map.of("operation", "find", "question", "orders"));

    assertThat(response.run()).isNotNull();
    assertThat(response.moreMatched()).isZero();
    assertThat(response.termLookupSkipped()).isNull();
    assertThat(JsonUtils.pojoToJson(response)).doesNotContain("termLookupSkipped");
    assertThat(response.nodes())
        .extracting(NodeCard::id, NodeCard::label, NodeCard::inGraph)
        .containsExactly(tuple(ORDERS, "orders", true), tuple(CUSTOMERS, "customers", true));
  }

  @Test
  void aDeprecatedTableFindReturnsFirstSaysSoOnItsCard() throws IOException {
    callersAreAdministrators(false);
    final RdfRepository withDeprecated =
        JenaBackedRepository.ofTurtle(
            GRAPH + "<%s> om:entityStatus \"Deprecated\" .".formatted(ORDERS));
    final KnowledgeGraphTool tool =
        new KnowledgeGraphTool(
            () -> withDeprecated,
            () -> RdfProjectionState.READY,
            (authorizer, caller, find) ->
                CatalogHits.of(
                    List.of(
                        new SearchHit("table", "warehouse.shop.public.orders", ORDERS_ID),
                        new SearchHit("table", "warehouse.shop.public.customers", CUSTOMERS_ID))),
            () -> traces,
            Clock.systemUTC());

    final KnowledgeGraphTool.FindResponse found =
        (KnowledgeGraphTool.FindResponse)
            tool.execute(GRANTED, CALLER, Map.of("operation", "find", "question", "orders"));

    assertThat(found.nodes().getFirst().id()).isEqualTo(ORDERS);
    assertThat(found.nodes().getFirst().facts().status()).isEqualTo("Deprecated");
  }

  @Test
  void aFindThatShowsFewerCardsThanItMatchedSaysSoInWordsAndHowToSeeTheRest() throws IOException {
    callersAreAdministrators(false);

    final KnowledgeGraphTool.FindResponse found =
        (KnowledgeGraphTool.FindResponse)
            call(Map.of("operation", "find", "question", "orders", "limit", 1));

    assertThat(found.nodes()).hasSize(1);
    assertThat(found.matched()).isEqualTo(2);
    assertThat(found.moreMatched()).isEqualTo(1);
    assertThat(found.rest())
        .contains(
            "2 matches", "best 1", "not shown", "larger limit", "entityTypes", "more specific");
  }

  @Test
  void aFindThatShowsEveryMatchSaysNothingMore() throws IOException {
    callersAreAdministrators(false);

    final KnowledgeGraphTool.FindResponse found =
        (KnowledgeGraphTool.FindResponse) call(Map.of("operation", "find", "question", "orders"));

    assertThat(found.matched()).isEqualTo(2);
    assertThat(found.rest()).isNull();
  }

  private static final int ASKED_FOR_MORE = 80_000;

  /**
   * A model cannot know its client's limit: it lowered the size and read fragments (round 3), and
   * raised it to 80,000 and lost an answer its client withheld (round 4). The size is the server's.
   */
  @Test
  void aFindAQueryAndAnExpandSentWithMaxCharsAreFittedToTheDefault() throws IOException {
    callersAreAdministrators(false);
    final List<UUID> ids = IntStream.range(0, 10).mapToObj(index -> UUID.randomUUID()).toList();
    final KnowledgeGraphTool wide = wideCatalog(ids);
    final KnowledgeGraphTool tested = testedTable();

    final List<KnowledgeGraphTool.Response> answers =
        List.of(
            wide.execute(
                GRANTED,
                CALLER,
                Map.of(
                    "operation",
                    "find",
                    "question",
                    "wide tables",
                    "limit",
                    10,
                    "maxChars",
                    ASKED_FOR_MORE)),
            wide.execute(
                GRANTED,
                CALLER,
                Map.of(
                    "operation",
                    "query",
                    "sparql",
                    "SELECT ?s ?p ?o WHERE { ?s ?p ?o } ORDER BY ?s ?p ?o",
                    "maxChars",
                    ASKED_FOR_MORE)),
            tested.execute(
                GRANTED,
                CALLER,
                Map.of(
                    "operation",
                    "expand",
                    "nodes",
                    List.of(ORDERS),
                    "relationships",
                    List.of("om:testsEntity"),
                    "direction",
                    "incoming",
                    "limit",
                    50,
                    "maxChars",
                    ASKED_FOR_MORE)));

    assertThat(answers)
        .allSatisfy(
            answer ->
                assertThat((long) JsonUtils.pojoToJson(answer).length())
                    .isLessThanOrEqualTo(KnowledgeGraphTool.DEFAULT_RESPONSE_CHARS));
    assertThat(
            ((KnowledgeGraphTool.ExpandResponse) answers.get(2)).expansions().getFirst().listed())
        .hasSize(30);
  }

  /** orders with 30 tests whose cards together are about 90,000 characters. */
  private KnowledgeGraphTool testedTable() {
    final StringBuilder turtle = new StringBuilder(GRAPH);
    for (int index = 0; index < 30; index++) {
      turtle.append(
          "<%stestCase/t%d> a om:TestCase ; rdfs:label \"orders_test_%d\" ; om:testsEntity <%s> ; om:latestResultStatus \"Success\" ; <http://purl.org/dc/terms/description> \"%s\" .%n"
              .formatted(
                  E, index, index, ORDERS, "What this test checks, at length. ".repeat(80).trim()));
    }
    final RdfRepository repositoryOfTests = JenaBackedRepository.ofTurtle(turtle.toString());
    return new KnowledgeGraphTool(
        () -> repositoryOfTests,
        () -> RdfProjectionState.READY,
        (authorizer, caller, find) -> CatalogHits.of(List.of()),
        () -> traces,
        Clock.systemUTC());
  }

  @Test
  void aRunHandlePassedBackIsTheRunOfTheResponse() throws IOException {
    callersAreAdministrators(false);
    final UUID run = UUID.randomUUID();

    final KnowledgeGraphTool.Response response =
        call(Map.of("operation", "find", "question", "orders", "run", run.toString()));

    assertThat(response.run()).isEqualTo(run);
  }

  @Test
  void expandFollowsTwoRelationshipsFromTwoNodesAndPagesWithTheCursor() throws IOException {
    callersAreAdministrators(false);
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("operation", "expand");
    arguments.put("nodes", List.of(ORDERS, CUSTOMERS));
    arguments.put("relationships", List.of("om:hasOwner", "om:hasTag"));
    arguments.put("limit", 3);

    final KnowledgeGraphTool.ExpandResponse first =
        (KnowledgeGraphTool.ExpandResponse) call(arguments);
    arguments.put("cursor", first.nextCursor());
    arguments.put("run", first.run().toString());
    final KnowledgeGraphTool.ExpandResponse second =
        (KnowledgeGraphTool.ExpandResponse) call(arguments);

    final List<String> walked = new ArrayList<>();
    List.of(first, second)
        .forEach(
            page ->
                page.expansions()
                    .forEach(
                        group -> group.nodes().forEach(far -> walked.add(group.node() + far))));
    assertThat(walked)
        .containsExactlyInAnyOrder(
            ORDERS + E + "team-growth",
            ORDERS + E + "tag-finance",
            ORDERS + E + "tag-pii",
            CUSTOMERS + E + "team-crm",
            CUSTOMERS + E + "tag-pii");
    assertThat(second.nextCursor()).isNull();
    assertThat(first.expansions())
        .filteredOn(group -> !group.nodes().isEmpty())
        .extracting(ExpansionGroup::direction)
        .containsOnly(Direction.OUTGOING);
    assertThat(first.expansions())
        .filteredOn(group -> group.direction() == Direction.INCOMING)
        .allSatisfy(group -> assertThat(group.total()).isZero());
  }

  @Test
  void queryReturnsWhatTheSparqlQueryToolReturns() throws IOException {
    callersAreAdministrators(false);
    final String sparql =
        "PREFIX om: <https://open-metadata.org/ontology/> "
            + "SELECT ?owner WHERE { ?table om:hasOwner ?owner } ORDER BY ?owner LIMIT 10";

    final KnowledgeGraphTool.QueryResponse response =
        (KnowledgeGraphTool.QueryResponse) call(Map.of("operation", "query", "sparql", sparql));
    final SparqlQueryTool.Result direct =
        new SparqlQueryTool(
                () -> repository,
                SparqlQueryExecutionGuard.shared()::execute,
                () -> RdfProjectionState.READY)
            .execute(GRANTED, CALLER, Map.of("query", sparql));

    assertThat(response.result()).isEqualTo(direct);
    assertThat(response.result().body()).contains(E + "team-crm", E + "team-growth");
  }

  private static final String OM_PREFIX = "PREFIX om: <https://open-metadata.org/ontology/> ";

  @Test
  void aQueryAnswerOverTheResponseLimitKeepsTheRowsThatFitAndSaysHowToReadTheRest()
      throws IOException {
    callersAreAdministrators(false);
    final List<UUID> ids = IntStream.range(0, 10).mapToObj(index -> UUID.randomUUID()).toList();

    final KnowledgeGraphTool.QueryResponse answer =
        (KnowledgeGraphTool.QueryResponse)
            wideCatalog(ids)
                .execute(
                    GRANTED,
                    CALLER,
                    Map.of(
                        "operation",
                        "query",
                        "sparql",
                        "SELECT ?s ?p ?o WHERE { ?s ?p ?o } ORDER BY ?s ?p ?o"));

    assertThat((long) JsonUtils.pojoToJson(answer).length())
        .isLessThanOrEqualTo(KnowledgeGraphTool.DEFAULT_RESPONSE_CHARS);
    assertThat(answer.rows().returned()).isPositive().isLessThan(answer.rows().total());
    assertThat(answer.rows().rest()).contains("OFFSET " + answer.rows().returned());
    assertThat(JsonUtils.readTree(answer.result().body()).path("results").path("bindings").size())
        .isEqualTo(answer.rows().returned());
    assertThat(JsonUtils.readTree(answer.result().body()).path("head").path("vars").toString())
        .isEqualTo("[\"s\",\"p\",\"o\"]");
  }

  @Test
  void aQueryWithNoRowsSaysWhatTheToolCanKnowAboutWhy() throws IOException {
    callersAreAdministrators(false);

    final KnowledgeGraphTool.QueryResponse answer =
        (KnowledgeGraphTool.QueryResponse)
            call(
                Map.of(
                    "operation",
                    "query",
                    "sparql",
                    OM_PREFIX + "SELECT ?owner WHERE { ?table om:owner ?owner }"));

    assertThat(answer.rows().total()).isZero();
    assertThat(answer.noRows().undeclared())
        .containsExactly(new NoRows.Term("om:owner", "om:hasOwner"));
    assertThat(answer.noRows().means()).contains("not proof");
  }

  @Test
  void aQueryUsingTheCardPrefixesWithoutDeclaringThemRunsWithTheirDeclarations()
      throws IOException {
    callersAreAdministrators(false);

    final KnowledgeGraphTool.QueryResponse answer =
        (KnowledgeGraphTool.QueryResponse)
            call(
                Map.of(
                    "operation",
                    "query",
                    "sparql",
                    "SELECT ?owner WHERE { ?table om:hasOwner ?owner } ORDER BY ?owner"));

    assertThat(answer.prefixesAdded()).containsExactly("om");
    assertThat(answer.result().body()).contains(E + "team-crm", E + "team-growth");
    assertThat(answer.noRows()).isNull();
  }

  @Test
  void aFindThenExpandRunIsTracedAsNodesEdgesStatesAndSteps() throws IOException {
    callersAreAdministrators(false);
    final UUID run = call(Map.of("operation", "find", "question", "orders")).run();
    call(
        Map.of(
            "operation", "expand",
            "run", run.toString(),
            "nodes", List.of(ORDERS),
            "relationships", List.of("om:hasOwner"),
            "direction", "outgoing"));

    final KnowledgeGraphTool.TraceResponse trace =
        (KnowledgeGraphTool.TraceResponse)
            call(Map.of("operation", "trace", "run", run.toString()));

    assertThat(trace.steps())
        .extracting(TraceStep::sequence, TraceStep::operation)
        .containsExactly(tuple(1, Operation.FIND), tuple(2, Operation.EXPAND));
    assertThat(trace.nodes())
        .extracting(TraceNode::id, TraceNode::state)
        .containsExactly(
            tuple(ORDERS, ExplorationState.EXPLORED),
            tuple(CUSTOMERS, ExplorationState.EXPLORED),
            tuple(E + "team-growth", ExplorationState.EXPLORED));
    assertThat(trace.edges())
        .extracting(TraceEdge::node, TraceEdge::relationship, TraceEdge::other, TraceEdge::state)
        .contains(
            tuple(ORDERS, "om:hasOwner", null, ExplorationState.EXPLORED),
            tuple(ORDERS, "om:hasOwner", E + "team-growth", ExplorationState.EXPLORED),
            tuple(ORDERS, "om:hasTag", null, ExplorationState.OFFERED),
            tuple(CUSTOMERS, "om:hasOwner", null, ExplorationState.OFFERED));
    assertThat(traces.principals()).containsOnly("analyst");
  }

  @Test
  void aCiteRecordsWhatTheAnswerReliedOnAndTraceReportsItCited() throws IOException {
    callersAreAdministrators(false);
    final UUID run = call(Map.of("operation", "find", "question", "orders")).run();
    final String unknown = E + "table/" + UUID.randomUUID();

    final KnowledgeGraphTool.CiteResponse cited =
        (KnowledgeGraphTool.CiteResponse)
            call(
                Map.of(
                    "operation", "cite", "run", run.toString(), "nodes", List.of(ORDERS, unknown)));
    final KnowledgeGraphTool.TraceResponse trace =
        (KnowledgeGraphTool.TraceResponse)
            call(Map.of("operation", "trace", "run", run.toString()));

    assertThat(cited.cited().nodes()).containsExactly(ORDERS);
    assertThat(cited.refused().nodes()).containsExactly(unknown);
    assertThat(trace.nodes())
        .extracting(TraceNode::id, TraceNode::state)
        .contains(
            tuple(ORDERS, ExplorationState.CITED), tuple(CUSTOMERS, ExplorationState.EXPLORED));
  }

  @Test
  void callersWithoutTheSparqlGrantAreRefusedBeforeTheGraphIsTouched() {
    final RdfRepository untouched = mock(RdfRepository.class);
    final KnowledgeGraphTool tool =
        new KnowledgeGraphTool(
            () -> untouched,
            () -> RdfProjectionState.READY,
            (authorizer, caller, find) -> CatalogHits.of(List.of()),
            () -> traces,
            Clock.systemUTC());
    final Authorizer denying = RdfToolAuthorization.denyingAuthorizer();

    assertThatThrownBy(
            () -> tool.execute(denying, CALLER, Map.of("operation", "find", "question", "x")))
        .isInstanceOf(AuthorizationException.class);
    RdfToolAuthorization.assertSparqlGrantRequested(denying, CALLER);
    verifyNoInteractions(untouched);
  }

  @Test
  void nonAdministratorsAreAnsweredOnlyFromAReadyProjection() {
    callersAreAdministrators(false);

    assertThatThrownBy(
            () ->
                tool(() -> RdfProjectionState.REBUILDING)
                    .execute(GRANTED, CALLER, Map.of("operation", "find", "question", "orders")))
        .isInstanceOf(RdfRetryLaterException.class)
        .hasMessageContaining("PROJECTION_NOT_READY");
  }

  @Test
  void aServerWithoutRdfSaysSoInsteadOfAnsweringEmpty() {
    callersAreAdministrators(true);
    final KnowledgeGraphTool tool =
        new KnowledgeGraphTool(
            () -> null,
            () -> RdfProjectionState.READY,
            (authorizer, caller, find) -> CatalogHits.of(List.of()),
            () -> traces,
            Clock.systemUTC());

    assertThatThrownBy(
            () -> tool.execute(GRANTED, CALLER, Map.of("operation", "find", "question", "x")))
        .isInstanceOf(RdfNotEnabledException.class);
  }

  @Test
  void aRelationshipNameWithAnUnknownPrefixIsACorrection() {
    callersAreAdministrators(false);

    assertThatThrownBy(
            () ->
                call(
                    Map.of(
                        "operation", "expand",
                        "nodes", List.of(ORDERS),
                        "relationships", List.of("owner"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'owner'")
        .hasMessageContaining("reachable");
  }

  @Test
  void aFindThatCouldNotLookForOntologyTermsSaysSoBesideTheCatalogCards() throws IOException {
    callersAreAdministrators(false);
    doThrow(new SparqlQueryLimits.OutputLimitExceededException())
        .when(repository)
        .executeAssertedSparqlQuery(contains("skos:altLabel"), anyString());

    final KnowledgeGraphTool.FindResponse response =
        (KnowledgeGraphTool.FindResponse) call(Map.of("operation", "find", "question", "orders"));

    assertThat(response.nodes()).extracting(NodeCard::id).containsExactly(ORDERS, CUSTOMERS);
    assertThat(response.termLookupSkipped()).contains("maximum response size");
    assertThat(JsonUtils.pojoToJson(response)).contains("\"termLookupSkipped\"");
  }

  @Test
  void aFindWhoseSemanticSearchDidNotTakePartSaysSo() throws IOException {
    callersAreAdministrators(false);
    final KnowledgeGraphTool tool =
        new KnowledgeGraphTool(
            () -> repository,
            () -> RdfProjectionState.READY,
            (authorizer, caller, find) ->
                CatalogHits.skipped(
                    List.of(new SearchHit("table", "warehouse.shop.public.orders", ORDERS_ID)),
                    "embedding backend down"),
            () -> traces,
            Clock.systemUTC());

    final KnowledgeGraphTool.FindResponse response =
        (KnowledgeGraphTool.FindResponse)
            tool.execute(GRANTED, CALLER, Map.of("operation", "find", "question", "orders"));

    assertThat(response.nodes()).extracting(NodeCard::id).containsExactly(ORDERS);
    assertThat(response.semanticSearchSkipped()).contains("embedding backend down");
    assertThat(JsonUtils.pojoToJson(response)).contains("\"semanticSearchSkipped\"");
    assertThat(JsonUtils.pojoToJson(call(Map.of("operation", "find", "question", "orders"))))
        .doesNotContain("semanticSearchSkipped");
  }

  /** Ten tables with 30 described relationships each, as a real catalog's tables are. */
  private static String wideTables(final List<UUID> ids) {
    final Model ontology = OntologyDocument.loadCanonicalModel();
    final List<String> predicates =
        ontology.listSubjectsWithProperty(RDF.type, OWL2.ObjectProperty).toList().stream()
            .filter(Resource::isURIResource)
            .map(Resource::getURI)
            .filter(iri -> iri.startsWith("https://open-metadata.org/ontology/"))
            .filter(
                iri ->
                    ontology
                        .createResource(iri)
                        .hasProperty(
                            ontology.createProperty(
                                "https://open-metadata.org/ontology/projectionStatus"),
                            ontology.createResource("https://open-metadata.org/ontology/Stored")))
            .sorted()
            .limit(30)
            .toList();
    ontology.close();
    final StringBuilder turtle = new StringBuilder();
    for (final UUID id : ids) {
      turtle.append("<%stable/%s> a om:Table ; rdfs:label \"table %s\" .%n".formatted(E, id, id));
      for (int index = 0; index < predicates.size(); index++) {
        turtle.append(
            "<%stable/%s> <%s> e:target-%s-%d .%n"
                .formatted(E, id, predicates.get(index), id, index));
      }
    }
    return turtle.toString();
  }

  private KnowledgeGraphTool wideCatalog(final List<UUID> ids) {
    final RdfRepository wide = JenaBackedRepository.ofTurtle(wideTables(ids));
    return new KnowledgeGraphTool(
        () -> wide,
        () -> RdfProjectionState.READY,
        (authorizer, caller, find) ->
            CatalogHits.of(ids.stream().map(id -> new SearchHit("table", null, id)).toList()),
        () -> traces,
        Clock.systemUTC());
  }

  @Test
  void aDefaultFindFitsTheSmallestClientLimitWhateverLimitTheCallerAsks() throws IOException {
    callersAreAdministrators(false);
    final List<UUID> ids = IntStream.range(0, 10).mapToObj(index -> UUID.randomUUID()).toList();

    final KnowledgeGraphTool.FindResponse found =
        (KnowledgeGraphTool.FindResponse)
            wideCatalog(ids)
                .execute(
                    GRANTED,
                    CALLER,
                    Map.of("operation", "find", "question", "wide tables", "limit", 10));

    assertThat((long) JsonUtils.pojoToJson(found).length())
        .isLessThanOrEqualTo(KnowledgeGraphTool.DEFAULT_RESPONSE_CHARS);
    assertThat(found.moreMatched()).isEqualTo(10 - found.nodes().size());
    assertThat(found.nodes()).hasSizeGreaterThanOrEqualTo(8);
  }

  @Test
  void theResponseSerializesForTheTransport() throws IOException {
    callersAreAdministrators(false);

    final String json = JsonUtils.pojoToJson(call(Map.of("operation", "find", "question", "o")));

    assertThat(json)
        .contains("\"operation\":\"find\"", "\"reachable\"", "\"direction\":\"outgoing\"");
    final String ownerComment =
        OntologyVocabulary.canonical()
            .about("https://open-metadata.org/ontology/hasOwner")
            .comment();
    assertThat(json).contains("\"properties\":{", "\"om:hasOwner\":{");
    assertThat(json.split(Pattern.quote(ownerComment), -1)).hasSize(2);
  }

  /** Keeps steps serialized, as the SQL store does, so a step that does not round-trip fails. */
  private static final class RecordingTraces implements RunTraceStore {
    private final Map<UUID, List<String>> steps = new HashMap<>();

    @Override
    public void append(final UUID run, final RunStep step) {
      steps.computeIfAbsent(run, ignored -> new ArrayList<>()).add(JsonUtils.pojoToJson(step));
    }

    @Override
    public List<RunStep> steps(final UUID run) {
      return steps.getOrDefault(run, List.of()).stream()
          .map(json -> JsonUtils.readValue(json, RunStep.class))
          .toList();
    }

    List<String> principals() {
      return steps.values().stream()
          .flatMap(List::stream)
          .map(json -> JsonUtils.readValue(json, RunStep.class).principal())
          .toList();
    }
  }
}
