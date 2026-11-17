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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.utils.JsonUtils;

class GraphExpansionTest {

  private static final String OM = "https://open-metadata.org/ontology/";
  private static final String E = "https://open-metadata.org/entity/";
  private static final String ORDERS = E + "table/0b0b0b0b-0000-0000-0000-000000000001";
  private static final String CUSTOMERS = E + "table/0b0b0b0b-0000-0000-0000-000000000002";
  private static final String REPORT = E + "table/0b0b0b0b-0000-0000-0000-000000000003";
  private static final String HAS_OWNER = OM + "hasOwner";
  private static final String HAS_TAG = OM + "hasTag";
  private static final String UPSTREAM = OM + "upstream";

  private static final String GRAPH =
      """
      <%1$s> a om:Table ; rdfs:label "orders" ;
          om:hasOwner e:team-growth ; om:hasTag e:tag-pii , e:tag-finance .
      <%2$s> a om:Table ; rdfs:label "customers" ;
          om:hasOwner e:team-crm ; om:hasTag e:tag-pii .
      <%3$s> a om:Table ; rdfs:label "report" ; om:upstream <%1$s> .
      e:team-growth a om:Team ; rdfs:label "growth" .
      e:team-crm a om:Team ; rdfs:label "crm" .
      e:tag-pii a om:Tag ; om:tagFQN "PII.Sensitive" .
      e:tag-finance a om:Tag ; om:tagFQN "Domain.Finance" .
      """
          .formatted(ORDERS, CUSTOMERS, REPORT);

  private static GraphExpansion expansion() {
    final InMemoryGraph graph = InMemoryGraph.ofTurtle(GRAPH);
    return new GraphExpansion(
        graph, NodeCardReaderTest.reader(graph), OntologyVocabulary.canonical());
  }

  private static final long UNBOUNDED = Long.MAX_VALUE;

  private static ExpandRequest request(final int limit, final int offset) {
    return request(limit, offset, UNBOUNDED);
  }

  private static ExpandRequest request(final int limit, final int offset, final long maxChars) {
    return new ExpandRequest(
        List.of(ORDERS, CUSTOMERS),
        List.of(HAS_OWNER, HAS_TAG),
        Set.of(Direction.OUTGOING, Direction.INCOMING),
        limit,
        offset,
        maxChars);
  }

  @Test
  void expandReturnsTheFarNodesForEveryNodeAndRelationshipInOneCall() {
    final ExpandPage page = expansion().expand(request(10, 0));

    assertThat(page.expansions())
        .extracting(
            ExpansionGroup::node,
            ExpansionGroup::relationship,
            ExpansionGroup::direction,
            ExpansionGroup::nodes)
        .containsExactlyInAnyOrder(
            tuple(ORDERS, "om:hasOwner", Direction.OUTGOING, List.of(E + "team-growth")),
            tuple(
                ORDERS, "om:hasTag", Direction.OUTGOING, List.of(E + "tag-finance", E + "tag-pii")),
            tuple(CUSTOMERS, "om:hasOwner", Direction.OUTGOING, List.of(E + "team-crm")),
            tuple(CUSTOMERS, "om:hasTag", Direction.OUTGOING, List.of(E + "tag-pii")));
    assertThat(page.nextOffset()).isNull();
  }

  @Test
  void farNodesComeBackAsCardsOncePerNode() {
    final ExpandPage page = expansion().expand(request(10, 0));

    assertThat(page.nodes())
        .extracting(NodeCard::id)
        .containsExactlyInAnyOrder(
            E + "team-growth", E + "tag-finance", E + "tag-pii", E + "team-crm");
    assertThat(page.nodes())
        .filteredOn(card -> card.id().equals(E + "tag-pii"))
        .singleElement()
        .satisfies(
            card ->
                assertThat(card.reachable())
                    .extracting(Reachable::relationship, Reachable::direction, Reachable::count)
                    .containsExactly(tuple("om:hasTag", Direction.INCOMING, 2L)));
  }

  @Test
  void pagesWalkEveryFarNodeExactlyOnce() {
    final GraphExpansion expansion = expansion();
    final List<String> walked = new ArrayList<>();
    Integer offset = 0;
    int pages = 0;
    while (offset != null) {
      final ExpandPage page = expansion.expand(request(2, offset));
      page.expansions()
          .forEach(group -> group.nodes().forEach(far -> walked.add(group.node() + far)));
      offset = page.nextOffset();
      pages++;
    }

    assertThat(pages).isEqualTo(3);
    assertThat(walked)
        .containsExactlyInAnyOrder(
            ORDERS + E + "team-growth",
            ORDERS + E + "tag-finance",
            ORDERS + E + "tag-pii",
            CUSTOMERS + E + "team-crm",
            CUSTOMERS + E + "tag-pii");
  }

  @Test
  void aDirectionNarrowsTheExpansionToOneEndOfTheRelationship() {
    final ExpandPage page =
        expansion()
            .expand(
                new ExpandRequest(
                    List.of(ORDERS),
                    List.of(UPSTREAM),
                    Set.of(Direction.INCOMING),
                    10,
                    0,
                    UNBOUNDED));

    assertThat(page.expansions())
        .extracting(ExpansionGroup::relationship, ExpansionGroup::direction, ExpansionGroup::nodes)
        .containsExactly(tuple("om:upstream", Direction.INCOMING, List.of(REPORT)));
  }

  @Test
  void aPageThatWouldNotFitTheResponseBudgetEndsEarlyAndTheCursorResumesThere() {
    final GraphExpansion expansion = expansion();
    final ExpandPage unbounded = expansion.expand(request(10, 0));
    final long budget = JsonUtils.pojoToJson(unbounded).length() / 2;
    final List<String> walked = new ArrayList<>();
    final List<ExpandPage> pages = new ArrayList<>();
    Integer offset = 0;
    while (offset != null) {
      final ExpandPage page = expansion.expand(request(10, offset, budget));
      page.expansions()
          .forEach(group -> group.nodes().forEach(far -> walked.add(group.node() + far)));
      pages.add(page);
      offset = page.nextOffset();
    }

    assertThat(pages.getFirst().nextOffset()).isNotNull();
    assertThat(pages)
        .allSatisfy(
            page ->
                assertThat(rows(page) == 1 || JsonUtils.pojoToJson(page).length() <= budget)
                    .isTrue());
    assertThat(walked).hasSize(5).doesNotHaveDuplicates();
  }

  private static int rows(final ExpandPage page) {
    return page.expansions().stream().mapToInt(group -> group.nodes().size()).sum();
  }

  @Test
  void aSingleRowLargerThanTheBudgetStillMakesProgress() {
    final ExpandPage page = expansion().expand(request(10, 0, 1));

    assertThat(page.expansions()).hasSize(1);
    assertThat(page.nextOffset()).isEqualTo(1);
  }
}
