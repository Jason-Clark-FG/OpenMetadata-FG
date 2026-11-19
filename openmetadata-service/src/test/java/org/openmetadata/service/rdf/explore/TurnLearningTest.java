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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** cite's final step: what a turn learnt is checked against the run, then written. */
class TurnLearningTest {

  private static final String BASE = NodeCardReaderTest.BASE;
  private static final String ORDERS = NodeCardReaderTest.ORDERS;
  private static final String DAILY = NodeCardReaderTest.DAILY;
  private static final String MEMORY = BASE + "entity/contextMemory/";
  private static final String TICKETS = MEMORY + "0c0c0c0c-0000-0000-0000-000000000001";
  private static final String GRAPH =
      NodeCardReaderTest.GRAPH
          + """
            <%1$s> a om:ContextMemory ; om:title "Test tickets" ;
                om:question "Which orders are test tickets?" ; om:answer "Orders with no lines." ;
                om:entityStatus "Approved" .
            <%2$s> om:appliedTo <%1$s> .
            """
              .formatted(TICKETS, ORDERS);
  private static final LearnedCandidate.Evidence SAID =
      new LearnedCandidate.Evidence(
          EvidenceSource.USER, "orders with one line are tests too", null);

  private final InMemoryRunTraceStore traces = new InMemoryRunTraceStore();
  private final RunContext run = new RunContext(UUID.randomUUID(), "analyst");
  private final List<LearnedCandidate> written = new ArrayList<>();
  private final List<Set<String>> shownAtWrite = new ArrayList<>();

  private KnowledgeGraphExplorer explorer() {
    return new KnowledgeGraphExplorer(
        InMemoryGraph.ofTurtle(GRAPH),
        OntologyVocabulary.canonical(),
        BASE,
        traces,
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }

  private MemoryWriter saving() {
    return (candidate, shownMemories, ignored) -> {
      written.add(candidate);
      shownAtWrite.add(shownMemories);
      return new MemoryWriter.Written.Saved(
          new MemoryEntry(
              MEMORY + "0c0c0c0c-0000-0000-0000-0000000000ff",
              candidate.title(),
              candidate.question() + " → " + candidate.answer(),
              null,
              "Unprocessed",
              true,
              Instant.EPOCH.toString()));
    };
  }

  private static LearnedCandidate correction(final String about, final String corrects) {
    return new LearnedCandidate(
        "Orders with at most one line are test tickets",
        "Which orders are test tickets?",
        "Orders with zero or one line are test tickets; leave them out of order counts.",
        LearnedKind.CORRECTION,
        about,
        SAID,
        corrects);
  }

  private void findOrders() {
    explorer()
        .find(
            run,
            new FindRequest("orders", Set.of(), 5, Long.MAX_VALUE),
            List.of(new SearchHit("table", null, id(ORDERS))));
  }

  @Test
  void aCorrectionAboutAShownCardOfAShownMemoryIsWrittenAndTheRunSeesItAtOnce() {
    findOrders();

    final CiteOutcome cited =
        explorer()
            .cite(
                run,
                new Citation(List.of(ORDERS), List.of()),
                List.of(correction(ORDERS, TICKETS)),
                saving());

    assertThat(cited.learned())
        .singleElement()
        .satisfies(
            outcome -> {
              assertThat(outcome.saved().title())
                  .isEqualTo("Orders with at most one line are test tickets");
              assertThat(outcome.about()).isEqualTo(ORDERS);
              assertThat(outcome.reason()).isNull();
            });
    assertThat(shownAtWrite.getFirst()).containsExactly(TICKETS);
    final NodeCard card =
        explorer()
            .find(
                run,
                new FindRequest("orders", Set.of(), 5, Long.MAX_VALUE),
                List.of(new SearchHit("table", null, id(ORDERS))))
            .nodes()
            .getFirst();
    assertThat(card.memories().shown())
        .extracting(MemoryEntry::title)
        .startsWith("Orders with at most one line are test tickets");
    assertThat(card.memories().total()).isEqualTo(2);
  }

  @Test
  void whatTheRunNeverShowedOrWithoutEvidenceIsNotWrittenAndSaysWhy() {
    findOrders();
    final LearnedCandidate unseen = correction(DAILY, null);
    final LearnedCandidate stranger =
        correction(ORDERS, MEMORY + "0c0c0c0c-0000-0000-0000-00000000dead");
    final LearnedCandidate unsupported =
        new LearnedCandidate("t", "q", "a", LearnedKind.DEFINITION, ORDERS, null, null);

    final CiteOutcome cited =
        explorer()
            .cite(
                run,
                new Citation(List.of(), List.of()),
                List.of(unseen, stranger, unsupported),
                saving());

    assertThat(written).isEmpty();
    assertThat(cited.learned())
        .extracting(LearnedOutcome::reason)
        .satisfiesExactly(
            reason -> assertThat(reason).contains("'about'", "card this run showed", DAILY),
            reason -> assertThat(reason).contains("'corrects'", "memory this run showed"),
            reason -> assertThat(reason).contains("evidence"));
  }

  @Test
  void aRepeatNamesTheMemoryThatAlreadySaysItAndTheTraceKeepsEveryCandidate() {
    findOrders();
    final MemoryWriter repeating =
        (candidate, shown, ignored) -> new MemoryWriter.Written.Repeat(TICKETS, "Test tickets");

    final CiteOutcome cited =
        explorer()
            .cite(
                run,
                new Citation(List.of(), List.of()),
                List.of(correction(ORDERS, null)),
                repeating);

    assertThat(cited.learned().getFirst().sameAs()).isEqualTo(TICKETS);
    assertThat(cited.learned().getFirst().reason()).contains(TICKETS, "Test tickets", "already");
    final RunStep cite = traces.steps(run.run()).getLast();
    assertThat(cite.operation()).isEqualTo(Operation.CITE);
    assertThat(cite.learned()).singleElement().extracting(LearnedOutcome::candidate).isNotNull();
  }

  private static UUID id(final String iri) {
    return UUID.fromString(iri.substring(iri.lastIndexOf('/') + 1));
  }
}
