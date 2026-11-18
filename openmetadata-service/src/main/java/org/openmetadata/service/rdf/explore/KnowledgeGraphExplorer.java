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
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Explores the knowledge graph for one caller: finds starting nodes, expands relationships, and
 * records every step of the run so its subgraph can be drawn later. One instance per call; the
 * graph reader carries the caller's admission slot.
 */
public final class KnowledgeGraphExplorer {

  private static final String BOTH_DIRECTIONS = "both";

  private final OntologyVocabulary vocabulary;
  private final NodeCardReader cards;
  private final OntologyTerms terms;
  private final PropertyDescriptions descriptions;
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
    this.descriptions = new PropertyDescriptions(vocabulary);
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
   * returned; {@code moreMatched} counts the matched nodes left out, and {@code termLookupSkipped}
   * says why when the terms could not be looked for.
   */
  public FindResult find(
      final RunContext run, final FindRequest request, final List<SearchHit> hits) {
    final TermLookup lookup =
        terms.matching(request.question(), request.termKinds(), request.limit());
    final List<String> matched =
        alternate(lookup.terms(), hits.stream().map(nodeIris::of).distinct().toList());
    final List<NodeCard> read =
        cards.read(matched.subList(0, Math.min(request.limit(), matched.size())));
    final List<NodeCard> found =
        read.subList(0, PageBudget.cardsThatFit(read, descriptions, request.maxChars()));
    record(
        run,
        Operation.FIND,
        RunStep.Input.find(request.question()),
        RunSteps.served(found),
        List.of());
    return new FindResult(
        found, descriptions.describeCards(found), matched.size() - found.size(), lookup.skipped());
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
        RunStep.Input.expand(
            request.nodes(),
            request.relationships().stream().map(vocabulary::compact).toList(),
            direction(request.directions()),
            request.limit(),
            request.offset()),
        RunSteps.served(page.nodes()),
        RunSteps.expanded(page));
    return page;
  }

  /** A query step keeps its text and the nodes its rows named, so the trace can draw them. */
  public void recordQuery(final RunContext run, final String sparql, final String resultBody) {
    record(
        run,
        Operation.QUERY,
        RunStep.Input.query(sparql),
        QueriedNodes.of(resultBody).stream().map(RunSteps::queried).toList(),
        List.of());
  }

  /**
   * Records what an answer relied on. Only nodes the run served and edges its expansions returned
   * are accepted; the rest come back refused. Nothing is recorded when nothing is accepted.
   */
  public CiteResult cite(final RunContext run, final Citation citation) {
    final Citations.Checked checked =
        Citations.check(
            trace(run.run()), citation, name -> vocabulary.compact(vocabulary.expand(name)));
    if (checked.citesAnything()) {
      record(
          run,
          Operation.CITE,
          RunStep.Input.cite(checked.result().cited().nodes()),
          List.of(),
          checked.edges());
    }
    return checked.result();
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
      final List<RunStep.Node> served,
      final List<RunStep.Edge> edges) {
    traces.append(
        run.run(), new RunStep(operation, clock.millis(), run.principal(), input, served, edges));
  }

  private static String direction(final Set<Direction> directions) {
    return directions.size() == 1 ? directions.iterator().next().value() : BOTH_DIRECTIONS;
  }
}
