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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.utils.JsonUtils;

/** Memories read from the graph: on the card of their anchor, in find, and as preferences. */
class GraphMemoriesTest {

  private static final String BASE = NodeCardReaderTest.BASE;
  private static final String ORDERS = NodeCardReaderTest.ORDERS;
  private static final String DAILY = NodeCardReaderTest.DAILY;
  private static final String MEMORY = BASE + "entity/contextMemory/";
  private static final String ALICE = BASE + "entity/user/0a0a0a0a-0000-0000-0000-00000000000a";
  private static final String BOB = BASE + "entity/user/0a0a0a0a-0000-0000-0000-00000000000b";
  private static final long DAY = 86_400_000L;
  private static final String LONG_ANSWER =
      "Leave the test tickets out of every total. ".repeat(12);

  /** A memory node as the projection writes it, changed {@code day} days after the epoch. */
  private static String memory(
      final String name,
      final String anchor,
      final String stage,
      final int day,
      final String answer) {
    return """
        <%1$s%2$s> a om:ContextMemory ; om:title "%2$s" ;
            om:question "Which orders count?" ; om:answer "%3$s" ;
            om:entityStatus "%4$s" ; om:memoryType "Note" ;
            om:lastContentUpdatedAt "%5$d"^^xsd:long .
        %6$s
        """
        .formatted(
            MEMORY,
            name,
            answer,
            stage,
            day * DAY,
            anchor == null ? "" : "<%s> om:appliedTo <%s%s> .".formatted(anchor, MEMORY, name));
  }

  private static final String ANCHORED =
      NodeCardReaderTest.GRAPH
          + IntStream.rangeClosed(1, 5)
              .mapToObj(day -> memory("rule" + day, ORDERS, "Approved", day, "Rule " + day + "."))
              .collect(Collectors.joining())
          + memory("pending", ORDERS, "Draft", 9, LONG_ANSWER)
          + memory("rejected", ORDERS, "Rejected", 10, "A rejected rule.")
          + memory("elsewhere", DAILY, "Approved", 11, "About daily orders only.");

  @Test
  void aCardCarriesTheMemoriesAnchoredToItsNodeNewestContentFirstWithTheRealTotal() {
    final NodeCard card =
        NodeCardReaderTest.reader(InMemoryGraph.ofTurtle(ANCHORED))
            .read(List.of(ORDERS))
            .getFirst();

    final MemoryListing memories = card.memories();
    assertThat(memories.total()).isEqualTo(6);
    assertThat(memories.shown())
        .extracting(MemoryEntry::title)
        .containsExactly("pending", "rule5", "rule4", "rule3", "rule2");
    assertThat(memories.rest()).contains("5 most recently changed of 6", "om:appliedTo");
    final MemoryEntry pending = memories.shown().getFirst();
    assertThat(pending.id()).isEqualTo(MEMORY + "pending");
    assertThat(pending.stage()).isEqualTo("Draft");
    assertThat(pending.awaitingReview()).isTrue();
    assertThat(pending.changedAt()).isEqualTo(Instant.ofEpochMilli(9 * DAY).toString());
    assertThat(pending.statement())
        .startsWith("Which orders count? → Leave the test")
        .endsWith("…");
    assertThat(pending.statement().length()).isLessThanOrEqualTo(CardValue.VALUE_LIMIT + 1);
    assertThat(pending.length())
        .isEqualTo("Which orders count? → ".length() + LONG_ANSWER.length());
    final MemoryEntry approved = memories.shown().get(1);
    assertThat(approved.statement()).isEqualTo("Which orders count? → Rule 5.");
    assertThat(approved.length()).isNull();
    assertThat(approved.awaitingReview()).isNull();
  }

  @Test
  void aCardWithoutMemoriesIsTheCardItWasBeforeAndCostsNoFurtherRead() {
    final InMemoryGraph plain = InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH);
    final InMemoryGraph withOthers = InMemoryGraph.ofTurtle(ANCHORED);

    final NodeCard raw =
        NodeCardReaderTest.reader(plain).read(List.of(NodeCardReaderTest.RAW)).getFirst();
    final NodeCard rawAmongMemories =
        NodeCardReaderTest.reader(withOthers).read(List.of(NodeCardReaderTest.RAW)).getFirst();

    assertThat(raw.memories()).isNull();
    assertThat(JsonUtils.pojoToJson(rawAmongMemories)).isEqualTo(JsonUtils.pojoToJson(raw));
    assertThat(JsonUtils.pojoToJson(raw)).doesNotContain("memories");
    assertThat(withOthers.queries()).hasSize(3);
  }

  @Test
  void findOffersMatchingMemoriesNotAnchoredToACardShownBestMatchFirst() {
    final String graph =
        ANCHORED
            + memory(
                "unanchored", null, "Approved", 2, "Average order value leaves out test tickets.")
            + memory("other", DAILY, "Unprocessed", 3, "Average order value is net of refunds.")
            + memory("retired", null, "Superseded", 4, "Average order value was gross.")
            + """
              <%1$sstyle> a om:ContextMemory ; om:title "style" ; om:memoryType "Preference" ;
                  om:answer "Show average order value in euros." ; om:entityStatus "Approved" .
              """
                .formatted(MEMORY);
    final GraphMemories memories = new GraphMemories(InMemoryGraph.ofTurtle(graph));

    final MemoryListing notes =
        memories.notes("What is the average value without test tickets?", Set.of(ORDERS)).listing();

    assertThat(notes.total()).isEqualTo(2);
    assertThat(notes.shown())
        .extracting(MemoryEntry::title, MemoryEntry::awaitingReview)
        .containsExactly(tuple("unanchored", null), tuple("other", true));
    assertThat(notes.rest()).isNull();
  }

  @Test
  void aQuestionThatMatchesNoMemoryHasNoNotes() {
    final GraphMemories memories = new GraphMemories(InMemoryGraph.ofTurtle(ANCHORED));

    assertThat(memories.notes("customer lifetime revenue", Set.of()).listing()).isNull();
    assertThat(memories.notes("the and of", Set.of()).listing()).isNull();
  }

  @Test
  void theCallersPreferencesComePinnedFirstThenNewestWithinTheBudgetSkippingWhatDoesNotFit() {
    final String graph =
        """
        <%1$scurrency> a om:ContextMemory ; om:title "currency" ; om:memoryType "Preference" ;
            om:answer "Report amounts in euros." ; om:entityStatus "Approved" ;
            om:hasOwner <%2$s> ; om:lastContentUpdatedAt "%3$d"^^xsd:long .
        <%1$saliases> a om:ContextMemory ; om:title "aliases" ; om:memoryType "Preference" ;
            om:answer "%5$s" ; om:entityStatus "Approved" ;
            om:hasOwner <%2$s> ; om:lastContentUpdatedAt "%4$d"^^xsd:long .
        <%1$spinned> a om:ContextMemory ; om:title "pinned" ; om:memoryType "Note" ;
            om:pinned true ; om:answer "Fiscal year starts in April." ; om:entityStatus "Unprocessed" ;
            om:hasOwner <%2$s> ; om:lastContentUpdatedAt "1"^^xsd:long .
        <%1$snote> a om:ContextMemory ; om:title "note" ; om:memoryType "Note" ;
            om:answer "Not a preference." ; om:entityStatus "Approved" ; om:hasOwner <%2$s> .
        <%1$sold> a om:ContextMemory ; om:title "old" ; om:memoryType "Preference" ;
            om:answer "Report amounts in dollars." ; om:entityStatus "Superseded" ; om:hasOwner <%2$s> .
        <%1$sbobs> a om:ContextMemory ; om:title "bobs" ; om:memoryType "Preference" ;
            om:answer "Bob likes pie charts." ; om:entityStatus "Approved" ; om:hasOwner <%6$s> .
        """
            .formatted(MEMORY, ALICE, 5 * DAY, 9 * DAY, "x".repeat(2_500), BOB);
    final GraphMemories memories = new GraphMemories(InMemoryGraph.ofTurtle(graph));

    final List<MemoryEntry> preferences = memories.preferences(ALICE);

    assertThat(preferences).extracting(MemoryEntry::title).containsExactly("pinned", "currency");
    assertThat(preferences.getFirst().awaitingReview()).isTrue();
    assertThat(preferences.get(1).statement()).isEqualTo("Report amounts in euros.");
    assertThat(memories.preferences(ALICE)).isEqualTo(preferences);
  }

  @Test
  void theMemoriesOfACardShownArePaidForBeforeAFurtherCard() {
    final List<NodeCard> both =
        NodeCardReaderTest.reader(InMemoryGraph.ofTurtle(ANCHORED)).read(List.of(ORDERS, DAILY));
    final long firstWithMemories = JsonUtils.pojoToJson(both.getFirst()).length() + 200L;
    final KnowledgeGraphExplorer explorer =
        new KnowledgeGraphExplorer(
            InMemoryGraph.ofTurtle(ANCHORED),
            OntologyVocabulary.canonical(),
            BASE,
            new InMemoryRunTraceStore(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    final FindResult found =
        explorer.find(
            new RunContext(UUID.randomUUID(), "analyst"),
            new FindRequest("orders", Set.of(), 2, firstWithMemories),
            List.of(
                new SearchHit("table", "warehouse.shop.public.orders", id(ORDERS)),
                new SearchHit("table", "warehouse.shop.public.daily_orders", id(DAILY))));

    assertThat(found.nodes()).extracting(NodeCard::id).containsExactly(ORDERS);
    assertThat(found.nodes().getFirst().memories().total()).isEqualTo(6);
    assertThat(found.carriesMemories()).isTrue();
  }

  private static UUID id(final String iri) {
    return UUID.fromString(iri.substring(iri.lastIndexOf('/') + 1));
  }
}
