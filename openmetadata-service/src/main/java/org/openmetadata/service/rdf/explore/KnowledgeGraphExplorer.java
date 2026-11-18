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

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Explores the knowledge graph for one caller: finds starting nodes, expands relationships, and
 * records every step of the run so its subgraph can be drawn later. One instance per call; the
 * graph reader carries the caller's admission slot.
 */
public final class KnowledgeGraphExplorer {

  private final OntologyVocabulary vocabulary;
  private final NodeCardReader cards;
  private final OntologyTerms terms;
  private final GraphExpansion expansion;
  private final NodeIris nodeIris;
  private final RunTraceStore traces;
  private final Clock clock;

  public KnowledgeGraphExplorer(
      final GraphReader graph,
      final OntologyVocabulary vocabulary,
      final String baseUri,
      final RunTraceStore traces,
      final Clock clock) {
    this.vocabulary = Objects.requireNonNull(vocabulary);
    this.cards = new NodeCardReader(graph, vocabulary, baseUri);
    this.terms = new OntologyTerms(graph);
    this.expansion = new GraphExpansion(graph, cards, vocabulary);
    this.nodeIris = new NodeIris(baseUri);
    this.traces = Objects.requireNonNull(traces);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Cards for the ontology terms whose label matches the question and the search hits, under the
   * IRIs the graph holds them by. The two sources alternate, a term first, and either fills the
   * places the other leaves; hits of one entity (semantic search returns one per chunk) become one
   * card. At most {@code limit} cards are read and the leading ones that fit {@code maxChars} are
   * returned; {@code moreMatched} counts the matched nodes left out.
   */
  public FindResult find(
      final RunContext run, final FindRequest request, final List<SearchHit> hits) {
    final List<String> matched =
        alternate(
            terms.matching(request.question(), request.termKinds(), request.limit()),
            hits.stream().map(nodeIris::of).distinct().toList());
    final List<NodeCard> read =
        cards.read(matched.subList(0, Math.min(request.limit(), matched.size())));
    final List<NodeCard> found = read.subList(0, PageBudget.cardsThatFit(read, request.maxChars()));
    record(
        run,
        Operation.FIND,
        new RunStep.Input(request.question(), List.of(), List.of(), null),
        found,
        List.of());
    return new FindResult(found, matched.size() - found.size());
  }

  private static List<String> alternate(final List<String> first, final List<String> second) {
    return IntStream.range(0, Math.max(first.size(), second.size()))
        .boxed()
        .flatMap(
            index ->
                Stream.of(first, second)
                    .filter(nodes -> index < nodes.size())
                    .map(nodes -> nodes.get(index)))
        .distinct()
        .toList();
  }

  public ExpandPage expand(final RunContext run, final ExpandRequest request) {
    final ExpandPage page = expansion.expand(request);
    record(
        run,
        Operation.EXPAND,
        new RunStep.Input(
            null,
            request.nodes(),
            request.relationships().stream().map(vocabulary::compact).toList(),
            null),
        page.nodes(),
        RunSteps.expanded(page));
    return page;
  }

  public void recordQuery(final RunContext run, final String sparql) {
    record(
        run,
        Operation.QUERY,
        new RunStep.Input(null, List.of(), List.of(), sparql),
        List.of(),
        List.of());
  }

  public RunTrace trace(final UUID run) {
    final List<RunStep> steps = traces.steps(run);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException(
          "No steps are recorded for run %s. A run keeps its trace for %d hours; pass the 'run' a knowledge_graph call returned."
              .formatted(run, RunTraceStore.RETENTION.toHours()));
    }
    return RunTraceFolder.fold(run, steps);
  }

  private void record(
      final RunContext run,
      final Operation operation,
      final RunStep.Input input,
      final List<NodeCard> served,
      final List<RunStep.Edge> edges) {
    traces.append(
        run.run(),
        new RunStep(
            operation, clock.millis(), run.principal(), input, RunSteps.served(served), edges));
  }
}
