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

package org.openmetadata.service.rdf.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnDataType;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.rdf.RdfQualityFixtures;
import org.openmetadata.service.rdf.SparqlQueryExecutionGuard;
import org.openmetadata.service.rdf.SparqlQueryLimits;
import org.openmetadata.service.rdf.translator.JsonLdTranslator;

class KnowledgeGraphExplorerTest {

  private static final String BASE = NodeCardReaderTest.BASE;
  private static final String OM = "https://open-metadata.org/ontology/";
  private static final String ORDERS = NodeCardReaderTest.ORDERS;
  private static final String RAW = NodeCardReaderTest.RAW;
  private static final String DAILY = NodeCardReaderTest.DAILY;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

  private static final long UNBOUNDED = Long.MAX_VALUE;
  private static final int MAX_CARDS = 10;
  private static final long MCP_RESPONSE_BUDGET = 80_000;

  private final InMemoryRunTraceStore traces = new InMemoryRunTraceStore();
  private final RunContext run = new RunContext(UUID.randomUUID(), "analyst");

  private KnowledgeGraphExplorer explorer(final GraphReader graph) {
    return new KnowledgeGraphExplorer(graph, OntologyVocabulary.canonical(), BASE, traces, CLOCK);
  }

  private static SearchHit hit(final String iri) {
    return new SearchHit("table", null, UUID.fromString(iri.substring(iri.lastIndexOf('/') + 1)));
  }

  private static FindRequest catalog(final String question, final long maxChars) {
    return new FindRequest(question, Set.of(), MAX_CARDS, maxChars);
  }

  private static FindRequest everything(final String question, final int limit) {
    return new FindRequest(question, EnumSet.allOf(OntologyTermKind.class), limit, UNBOUNDED);
  }

  private static List<SearchHit> tables(final int count) {
    return IntStream.range(0, count)
        .mapToObj(index -> new SearchHit("table", null, UUID.randomUUID()))
        .toList();
  }

  private static String tableIri(final SearchHit hit) {
    return BASE + "entity/table/" + hit.id();
  }

  @Test
  void aTermWhoseLabelMatchesIsAmongTheCardsWhenTheCatalogAloneFillsTheLimit() {
    final List<SearchHit> hits = tables(5);

    final FindResult found =
        explorer(OntologyTermsTest.releaseAndCatalog())
            .find(run, everything("subscriber", 5), hits);

    assertThat(found.nodes())
        .extracting(NodeCard::id)
        .containsExactly(
            OntologyTermsTest.SUBSCRIBER,
            tableIri(hits.get(0)),
            tableIri(hits.get(1)),
            tableIri(hits.get(2)),
            tableIri(hits.get(3)));
    assertThat(found.moreMatched()).isEqualTo(1);
    assertThat(found.termLookupSkipped()).isNull();
  }

  @Test
  void termsAndCatalogHitsAlternateStartingWithATermAndEitherFillsWhatTheOtherLeaves() {
    final List<SearchHit> three = tables(3);
    final List<SearchHit> one = tables(1);
    final KnowledgeGraphExplorer explorer = explorer(OntologyTermsTest.releaseAndCatalog());

    final String question = "subscriber subscription plan";

    final FindResult alternated = explorer.find(run, everything(question, 4), three);
    final FindResult filled = explorer.find(run, everything(question, 5), one);

    assertThat(alternated.nodes())
        .extracting(NodeCard::id)
        .containsExactly(
            OntologyTermsTest.SUBSCRIPTION_PLAN,
            tableIri(three.get(0)),
            OntologyTermsTest.SUBSCRIBER,
            tableIri(three.get(1)));
    assertThat(alternated.moreMatched()).isEqualTo(1);
    assertThat(filled.nodes())
        .extracting(NodeCard::id)
        .containsExactly(
            OntologyTermsTest.SUBSCRIPTION_PLAN,
            tableIri(one.get(0)),
            OntologyTermsTest.SUBSCRIBER);
    assertThat(filled.moreMatched()).isZero();
  }

  @Test
  void aMatchedTermIsAnOrdinaryCardUnderItsOwnIriThatExpandContinuesFrom() {
    final KnowledgeGraphExplorer explorer = explorer(OntologyTermsTest.releaseAndCatalog());

    final NodeCard subscriber =
        explorer.find(run, everything("Subscriber", 5), List.of()).nodes().getFirst();
    final ExpandPage properties =
        explorer.expand(
            run,
            new ExpandRequest(
                List.of(subscriber.id()),
                List.of(OntologyVocabulary.canonical().expand("rdfs:domain")),
                Set.of(Direction.INCOMING),
                10,
                0,
                UNBOUNDED));

    assertThat(subscriber.id()).isEqualTo(OntologyTermsTest.SUBSCRIBER);
    assertThat(subscriber.label()).isEqualTo("Subscriber");
    assertThat(subscriber.inGraph()).isTrue();
    assertThat(subscriber.reachable())
        .extracting(Reachable::relationship, Reachable::direction, Reachable::count)
        .contains(tuple("rdfs:domain", Direction.INCOMING, 1L));
    assertThat(properties.nodes())
        .extracting(NodeCard::id)
        .containsExactly(OntologyTermsTest.POSTAL_CODE);
  }

  @Test
  void aFindForCatalogTypesOnlyLooksForNoTerms() {
    final List<SearchHit> hits = tables(1);
    final InMemoryGraph graph = OntologyTermsTest.releaseAndCatalog();

    final FindResult found = explorer(graph).find(run, catalog("subscriber", UNBOUNDED), hits);

    assertThat(found.nodes()).extracting(NodeCard::id).containsExactly(tableIri(hits.getFirst()));
    assertThat(graph.queries()).noneMatch(query -> query.contains("skos:altLabel"));
  }

  @Test
  void aTermLookupTheGraphCannotAnswerLeavesTheCatalogCardsAndSaysTermsWereNotLookedFor() {
    final List<SearchHit> hits = tables(2);
    final InMemoryGraph graph = OntologyTermsTest.releaseAndCatalog();
    final List<RuntimeException> failures =
        List.of(
            new SparqlQueryExecutionGuard.QueryTimeoutException(1_000, null),
            new SparqlQueryLimits.OutputLimitExceededException());

    for (final RuntimeException failure : failures) {
      final GraphReader failingTermLookup =
          query -> {
            if (query.contains("skos:altLabel")) {
              throw failure;
            }
            return graph.select(query);
          };
      final FindResult found =
          explorer(failingTermLookup).find(run, everything("subscriber", 5), hits);
      assertThat(found.nodes())
          .extracting(NodeCard::id)
          .containsExactly(tableIri(hits.get(0)), tableIri(hits.get(1)));
      assertThat(found.termLookupSkipped()).contains(failure.getMessage());
    }
  }

  @Test
  void findMapsSearchHitsToTheIrisTheProjectionMints() {
    EntityInterface.CANONICAL_ENTITY_NAME_MAP.put("table", Entity.TABLE);
    final UUID id = UUID.randomUUID();
    final Table table =
        new Table()
            .withId(id)
            .withName("orders")
            .withFullyQualifiedName("warehouse.shop.public.orders")
            .withColumns(
                List.of(
                    new Column()
                        .withName("id")
                        .withDataType(ColumnDataType.INT)
                        .withFullyQualifiedName("warehouse.shop.public.orders.id")));
    final Model projected = new JsonLdTranslator(new ObjectMapper(), BASE).toRdf(table);

    final FindResult found =
        explorer(InMemoryGraph.of(projected))
            .find(
                run,
                catalog("orders", UNBOUNDED),
                List.of(
                    new SearchHit("table", table.getFullyQualifiedName(), id),
                    new SearchHit(Entity.TABLE_COLUMN, "warehouse.shop.public.orders.id", null)));

    assertThat(found.nodes())
        .extracting(NodeCard::fullyQualifiedName, NodeCard::inGraph)
        .containsExactly(
            tuple("warehouse.shop.public.orders", true),
            tuple("warehouse.shop.public.orders.id", true));
    assertThat(found.nodes().getFirst().id()).isEqualTo(BASE + "entity/table/" + id);
  }

  @Test
  void aFindThenExpandRunYieldsATraceOfNodesEdgesStatesAndSteps() {
    final KnowledgeGraphExplorer explorer =
        explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));

    explorer.find(run, catalog("orders", UNBOUNDED), List.of(hit(ORDERS)));
    explorer.expand(
        run,
        new ExpandRequest(
            List.of(ORDERS),
            List.of(OM + "upstream"),
            Set.of(Direction.OUTGOING),
            10,
            0,
            Long.MAX_VALUE));
    final RunTrace trace = explorer.trace(run.run());

    assertThat(trace.steps().get(1).input().relationships()).containsExactly("om:upstream");
    assertThat(trace.steps())
        .extracting(TraceStep::sequence, TraceStep::operation, TraceStep::nodes)
        .containsExactly(
            tuple(1, Operation.FIND, List.of(ORDERS)), tuple(2, Operation.EXPAND, List.of(RAW)));
    assertThat(trace.nodes())
        .extracting(TraceNode::id, TraceNode::state, TraceNode::firstStep)
        .containsExactly(
            tuple(ORDERS, ExplorationState.EXPLORED, 1), tuple(RAW, ExplorationState.EXPLORED, 2));
    assertThat(trace.edges())
        .extracting(
            TraceEdge::node,
            TraceEdge::relationship,
            TraceEdge::direction,
            TraceEdge::other,
            TraceEdge::state)
        .contains(
            tuple(ORDERS, "om:upstream", Direction.OUTGOING, null, ExplorationState.EXPLORED),
            tuple(ORDERS, "om:upstream", Direction.OUTGOING, RAW, ExplorationState.EXPLORED),
            tuple(ORDERS, "om:upstream", Direction.INCOMING, null, ExplorationState.OFFERED),
            tuple(ORDERS, "om:hasOwner", Direction.OUTGOING, null, ExplorationState.OFFERED))
        .doesNotContain(
            tuple(ORDERS, "om:upstream", Direction.INCOMING, DAILY, ExplorationState.EXPLORED));
  }

  @Test
  void anOfferedRelationshipCarriesTheCountItWasOfferedWith() {
    final KnowledgeGraphExplorer explorer =
        explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));

    explorer.find(run, catalog("orders", UNBOUNDED), List.of(hit(ORDERS)));

    assertThat(explorer.trace(run.run()).edges())
        .filteredOn(edge -> edge.relationship().equals("om:hasTag"))
        .singleElement()
        .extracting(TraceEdge::count, TraceEdge::firstStep)
        .containsExactly(2L, 1);
  }

  @Test
  void aFindOfTenTablesWithThirtyDescribedRelationshipsEachFitsTheResponseBudget() {
    final List<String> predicates = DescribedRelationships.predicates(30);
    final List<SearchHit> hits = tables(10);
    final StringBuilder turtle = new StringBuilder();
    hits.forEach(hit -> turtle.append(DescribedRelationships.wideTable(hit.id(), predicates)));

    final FindResult found =
        explorer(InMemoryGraph.ofTurtle(turtle.toString()))
            .find(run, catalog("wide", MCP_RESPONSE_BUDGET), hits);

    assertThat(predicates).hasSize(30);
    assertThat(found.nodes()).hasSize(10);
    assertThat(found.moreMatched()).isZero();
  }

  @Test
  void aFindDescribesEachRelationshipOnceAndItsCardsReferToItByName() {
    final List<String> predicates = DescribedRelationships.predicates(30);
    final List<SearchHit> hits = tables(2);
    final StringBuilder turtle = new StringBuilder();
    hits.forEach(hit -> turtle.append(DescribedRelationships.wideTable(hit.id(), predicates)));

    final FindResult found =
        explorer(InMemoryGraph.ofTurtle(turtle.toString()))
            .find(run, catalog("wide", UNBOUNDED), hits);

    assertDescribedOnce(found.properties(), JsonUtils.pojoToJson(found), predicates);
  }

  @Test
  void anExpandDescribesEachRelationshipOnceAcrossItsCardsAndItsGroups() {
    final List<String> predicates = DescribedRelationships.predicates(30);
    final List<SearchHit> far = tables(20);
    final StringBuilder turtle = new StringBuilder();
    far.forEach(
        hit ->
            turtle
                .append(DescribedRelationships.wideTable(hit.id(), predicates))
                .append("e:hub om:upstream <%s> .%n".formatted(tableIri(hit))));

    final ExpandPage page =
        explorer(InMemoryGraph.ofTurtle(turtle.toString()))
            .expand(
                run,
                new ExpandRequest(
                    List.of(BASE + "entity/hub"),
                    List.of(OM + "upstream"),
                    Set.of(Direction.OUTGOING),
                    20,
                    0,
                    UNBOUNDED));

    assertThat(page.nodes()).hasSize(20);
    assertThat(page.properties()).containsKey("om:upstream");
    assertDescribedOnce(page.properties(), JsonUtils.pojoToJson(page), predicates);
  }

  private static void assertDescribedOnce(
      final Map<String, RelationshipAbout> described,
      final String json,
      final List<String> predicates) {
    final OntologyVocabulary vocabulary = OntologyVocabulary.canonical();
    final Map<String, Long> predicatesPerComment =
        predicates.stream()
            .collect(
                Collectors.groupingBy(
                    predicate -> vocabulary.about(predicate).comment(), Collectors.counting()));
    for (final String predicate : predicates) {
      final RelationshipAbout about = vocabulary.about(predicate);
      assertThat(described).containsEntry(vocabulary.compact(predicate), about);
      final String comment = JsonUtils.pojoToJson(about.comment());
      assertThat((long) occurrences(json, comment.substring(1, comment.length() - 1)))
          .as("times the description of %s is sent", predicate)
          .isEqualTo(predicatesPerComment.get(about.comment()));
    }
  }

  private static int occurrences(final String text, final String part) {
    return text.split(Pattern.quote(part), -1).length - 1;
  }

  @Test
  void theValuesOnCardsAreDescribedOnceAndKeptOutOfTheStoredStep() {
    EntityInterface.CANONICAL_ENTITY_NAME_MAP.put("table", Entity.TABLE);
    final Table orders = RdfQualityFixtures.profiledTable(100.0, 4.0);
    final Model projected = new JsonLdTranslator(new ObjectMapper(), BASE).toRdf(orders);
    final OntologyVocabulary vocabulary = OntologyVocabulary.canonical();

    final FindResult found =
        explorer(InMemoryGraph.of(projected))
            .find(
                run,
                catalog("orders", UNBOUNDED),
                List.of(new SearchHit("table", orders.getFullyQualifiedName(), orders.getId())));

    assertThat(found.properties())
        .containsEntry("om:profileRowCount", vocabulary.aboutNamed("om:profileRowCount"));
    assertThat(vocabulary.aboutNamed("om:profileRowCount").comment()).isNotBlank();
    assertThat(traces.storedJson(run.run()).getFirst())
        .doesNotContain("profileRowCount")
        .doesNotContain("2025-11-13T02:13:20Z");
  }

  @Test
  void aQueryIsAStepOfTheRun() {
    final KnowledgeGraphExplorer explorer =
        explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));

    explorer.recordQuery(run, "SELECT ?s WHERE { ?s ?p ?o } LIMIT 1");

    assertThat(explorer.trace(run.run()).steps())
        .singleElement()
        .satisfies(
            step -> {
              assertThat(step.operation()).isEqualTo(Operation.QUERY);
              assertThat(step.input().sparql()).isEqualTo("SELECT ?s WHERE { ?s ?p ?o } LIMIT 1");
              assertThat(step.at()).isEqualTo(CLOCK.millis());
            });
  }

  @Test
  void runsAreKeptApart() {
    final KnowledgeGraphExplorer explorer =
        explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));
    final RunContext other = new RunContext(UUID.randomUUID(), "analyst");

    explorer.find(run, catalog("orders", UNBOUNDED), List.of(hit(ORDERS)));
    explorer.find(other, catalog("raw", UNBOUNDED), List.of(hit(RAW)));

    assertThat(explorer.trace(run.run()).nodes()).extracting(TraceNode::id).containsExactly(ORDERS);
  }

  @Test
  void aRunWithNoRecordedStepsIsACorrectionNotAnEmptyTrace() {
    final UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> explorer(InMemoryGraph.ofTurtle("")).trace(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(unknown.toString())
        .hasMessageContaining("24 hours");
  }

  @Test
  void findReturnsFewerCardsThanMatchedWhenTheyWouldNotFitTheBudgetAndSaysSo() {
    final KnowledgeGraphExplorer explorer =
        explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));
    final long oneCard =
        JsonUtils.pojoToJson(
                explorer
                    .find(run, catalog("orders", UNBOUNDED), List.of(hit(ORDERS)))
                    .nodes()
                    .getFirst())
            .length();

    final FindResult found =
        explorer.find(
            run, catalog("orders", oneCard + 10), List.of(hit(ORDERS), hit(DAILY), hit(RAW)));

    assertThat(found.nodes()).extracting(NodeCard::id).containsExactly(ORDERS);
    assertThat(found.moreMatched()).isEqualTo(2);
  }

  @Test
  void chunksOfOneEntityBecomeOneCardInSearchOrder() {
    final KnowledgeGraphExplorer explorer =
        explorer(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH));

    final FindResult found =
        explorer.find(
            run,
            catalog("orders", UNBOUNDED),
            List.of(hit(RAW), hit(ORDERS), hit(RAW), hit(ORDERS)));

    assertThat(found.nodes()).extracting(NodeCard::id).containsExactly(RAW, ORDERS);
    assertThat(found.moreMatched()).isZero();
  }

  @Test
  void aFindStepOfTenWideCardsIsStoredCompactly() {
    final StringBuilder turtle = new StringBuilder();
    final List<SearchHit> hits = new ArrayList<>();
    for (int table = 0; table < 10; table++) {
      final UUID id = UUID.randomUUID();
      hits.add(new SearchHit("table", null, id));
      for (int relationship = 0; relationship < 30; relationship++) {
        turtle.append(
            "<%sentity/table/%s> om:relationship%d e:target%d .%n"
                .formatted(BASE, id, relationship, relationship));
      }
    }
    final KnowledgeGraphExplorer explorer = explorer(InMemoryGraph.ofTurtle(turtle.toString()));

    explorer.find(run, catalog("wide", UNBOUNDED), hits);

    final int stored = traces.storedJson(run.run()).getFirst().length();
    assertThat(stored).as("stored find step JSON, chars").isLessThan(20_000);
    assertThat(explorer.trace(run.run()).edges()).hasSize(300);
  }
}
