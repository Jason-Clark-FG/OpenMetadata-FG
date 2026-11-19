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
import static org.assertj.core.api.Assertions.tuple;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.utils.JsonUtils;

class NodeCardReaderTest {

  static final String BASE = "https://open-metadata.org/";
  static final String ORDERS = BASE + "entity/table/0b0b0b0b-0000-0000-0000-000000000001";
  static final String DAILY = BASE + "entity/table/0b0b0b0b-0000-0000-0000-000000000002";
  static final String RAW = BASE + "entity/table/0b0b0b0b-0000-0000-0000-000000000003";

  static final String GRAPH =
      """
      <%1$s> a om:Table ;
          rdfs:label "orders" ;
          om:fullyQualifiedName "warehouse.shop.public.orders" ;
          dct:description "%4$s" ;
          om:hasOwner e:team-growth ;
          om:hasTag e:tag-pii , e:tag-finance ;
          om:hasTier e:tag-tier1 ;
          om:belongsToService e:svc-warehouse ;
          om:usageWeeklyCount "42"^^xsd:long ;
          om:upstream <%3$s> .
      <%2$s> a om:Table ; rdfs:label "daily_orders" ; om:upstream <%1$s> .
      <%3$s> a om:Table ; rdfs:label "raw_orders" .
      e:team-growth a om:Team ; rdfs:label "growth" .
      e:tag-pii a om:Tag ; om:tagFQN "PII.Sensitive" .
      e:tag-finance a om:Tag ; om:tagFQN "Domain.Finance" .
      e:tag-tier1 a om:Tag ; om:tagFQN "Tier.Tier1" .
      e:svc-warehouse a om:DatabaseService ; rdfs:label "warehouse" .
      """
          .formatted(ORDERS, DAILY, RAW, "Orders placed in the web shop. ".repeat(20).trim());

  static NodeCardReader reader(final GraphReader graph) {
    return new NodeCardReader(graph, OntologyVocabulary.canonical(), BASE);
  }

  @Test
  void aCardListsOnlyPopulatedRelationshipsWithCountsInBothDirections() {
    final NodeCard card = reader(InMemoryGraph.ofTurtle(GRAPH)).read(List.of(ORDERS)).getFirst();

    assertThat(card.reachable())
        .extracting(Reachable::relationship, Reachable::direction, Reachable::count)
        .containsExactlyInAnyOrder(
            tuple("om:hasOwner", Direction.OUTGOING, 1L),
            tuple("om:hasTag", Direction.OUTGOING, 2L),
            tuple("om:hasTier", Direction.OUTGOING, 1L),
            tuple("om:belongsToService", Direction.OUTGOING, 1L),
            tuple("om:upstream", Direction.OUTGOING, 1L),
            tuple("om:upstream", Direction.INCOMING, 1L));
  }

  @Test
  void reachableEntriesNameTheRelationshipAndLeaveItsDescriptionToTheResponse() {
    final NodeCard card = reader(InMemoryGraph.ofTurtle(GRAPH)).read(List.of(ORDERS)).getFirst();
    final String ownerComment =
        OntologyVocabulary.canonical()
            .about("https://open-metadata.org/ontology/hasOwner")
            .comment();

    assertThat(card.reachable()).extracting(Reachable::relationship).contains("om:hasOwner");
    assertThat(ownerComment).isNotBlank();
    assertThat(JsonUtils.pojoToJson(card)).doesNotContain(ownerComment);
  }

  @Test
  void aCardIdentifiesTheCatalogEntityItStandsFor() {
    final NodeCard card = reader(InMemoryGraph.ofTurtle(GRAPH)).read(List.of(ORDERS)).getFirst();

    assertThat(card.id()).isEqualTo(ORDERS);
    assertThat(card.entityType()).isEqualTo("table");
    assertThat(card.fullyQualifiedName()).isEqualTo("warehouse.shop.public.orders");
    assertThat(card.label()).isEqualTo("orders");
    assertThat(card.types()).containsExactly("om:Table");
    assertThat(card.inGraph()).isTrue();
  }

  @Test
  void aCardCarriesTheKeyFactsTheGraphHolds() {
    final KeyFacts facts =
        reader(InMemoryGraph.ofTurtle(GRAPH)).read(List.of(ORDERS)).getFirst().facts();

    assertThat(facts.tier()).isEqualTo("Tier.Tier1");
    assertThat(facts.owners()).containsExactly("growth");
    assertThat(facts.service()).isEqualTo("warehouse");
    assertThat(facts.usage().weeklyCount()).isEqualTo(42L);
    assertThat(facts.description()).startsWith("Orders placed in the web shop.").endsWith("…");
    assertThat(facts.description().length()).isLessThanOrEqualTo(KeyFacts.DESCRIPTION_LIMIT + 1);
  }

  @Test
  void aNodeTheGraphDoesNotHoldIsMarkedAbsentRatherThanIsolated() {
    final String missing = BASE + "entity/table/0b0b0b0b-0000-0000-0000-00000000dead";

    final NodeCard card = reader(InMemoryGraph.ofTurtle(GRAPH)).read(List.of(missing)).getFirst();

    assertThat(card.inGraph()).isFalse();
    assertThat(card.reachable()).isEmpty();
  }

  @Test
  void cardsForSeveralNodesComeFromThreeGraphReadsInRequestOrder() {
    final InMemoryGraph graph = InMemoryGraph.ofTurtle(GRAPH);

    final List<NodeCard> cards = reader(graph).read(List.of(RAW, ORDERS));

    assertThat(cards).extracting(NodeCard::id).containsExactly(RAW, ORDERS);
    assertThat(graph.queries()).hasSize(3);
  }

  static final String TESTED =
      """
      <%1$s> a om:Table ; rdfs:label "orders" ; om:entityStatus "Deprecated" .
      <%2$stestCase/fresh> a om:TestCase ; om:testsEntity <%1$s> ;
          om:latestResultStatus "Failed" ; om:hasOpenIncident <%2$sincident/1> .
      <%2$stestCase/unique> a om:TestCase ; om:testsEntity <%1$s> ; om:latestResultStatus "Failed" .
      <%2$stestCase/rows> a om:TestCase ; om:testsEntity <%1$s> ; om:latestResultStatus "Success" .
      <%2$stestCase/never> a om:TestCase ; om:testsEntity <%1$s> .
      <%2$sincident/1> a om:TestCaseIncident ; om:incidentState "Assigned" .
      """
          .formatted(ORDERS, BASE + "entity/");

  @Test
  void aRelationshipCountsTheNodesOnItsOtherSideByTheStatusTheOntologyMarksForThem() {
    final InMemoryGraph graph = InMemoryGraph.ofTurtle(TESTED);
    final List<NodeCard> cards =
        reader(graph).read(List.of(ORDERS, BASE + "entity/testCase/fresh"));

    assertThat(cards.getFirst().reachable())
        .filteredOn(entry -> entry.relationship().equals("om:testsEntity"))
        .singleElement()
        .satisfies(
            tests -> {
              assertThat(tests.direction()).isEqualTo(Direction.INCOMING);
              assertThat(tests.count()).isEqualTo(4L);
              assertThat(tests.status())
                  .containsExactly(
                      Map.entry("om:hasOpenIncident", 1L),
                      Map.entry("om:latestResultStatus=Failed", 2L),
                      Map.entry("om:latestResultStatus=Success", 1L));
            });
    assertThat(cards.getLast().reachable())
        .filteredOn(entry -> entry.relationship().equals("om:hasOpenIncident"))
        .singleElement()
        .extracting(Reachable::status)
        .isEqualTo(Map.of("om:incidentState=Assigned", 1L));
    assertThat(cards.getLast().reachable())
        .filteredOn(entry -> entry.relationship().equals("om:testsEntity"))
        .singleElement()
        .extracting(Reachable::status)
        .isEqualTo(Map.of());
    assertThat(PropertyDescriptions.namesOf(cards.getFirst()))
        .contains("om:latestResultStatus", "om:hasOpenIncident");
    assertThat(graph.queries()).hasSize(3);
  }

  @Test
  void aCardSaysHowTrustedItsNodeIsAmongItsFacts() {
    final NodeCard card = reader(InMemoryGraph.ofTurtle(TESTED)).read(List.of(ORDERS)).getFirst();

    assertThat(card.facts().status()).isEqualTo("Deprecated");
    assertThat(card.values()).extracting(CardValue::property).doesNotContain("om:entityStatus");
  }

  @Test
  void anyClassTheOntologyGivesAStatusPropertyIsCountedByIt() {
    final Model ontology = ModelFactory.createDefaultModel();
    ontology.read(
        new StringReader(
            """
            @prefix om: <https://open-metadata.org/ontology/> .
            @prefix ex: <https://example.org/> .
            ex:Ticket om:statusProperty ex:state .
            """),
        null,
        "TTL");
    final String table = BASE + "entity/table/0b0b0b0b-0000-0000-0000-000000000009";
    final InMemoryGraph graph =
        InMemoryGraph.ofTurtle(
            """
            <%s> a om:Table .
            <https://example.org/t1> a <https://example.org/Ticket> ;
                <https://example.org/state> "open" ; om:about <%s> .
            """
                .formatted(table, table));

    final NodeCard card =
        new NodeCardReader(graph, new OntologyVocabulary(ontology), BASE)
            .read(List.of(table))
            .getFirst();

    assertThat(card.reachable())
        .singleElement()
        .extracting(Reachable::status)
        .isEqualTo(Map.of("ex:state=open", 1L));
  }
}
