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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Explores the knowledge graph for one caller: finds starting nodes, expands relationships, and
 * records every step of the run so its subgraph can be drawn later. One instance per call; the
 * graph reader carries the caller's admission slot.
 */
public final class KnowledgeGraphExplorer {

  private static final String BOTH_DIRECTIONS = "both";

  private final GraphReader graph;
  private final OntologyVocabulary vocabulary;
  private final NodeCardReader cards;
  private final OntologyTerms terms;
  private final PropertyDescriptions descriptions;
  private final NodeIris nodeIris;
  private final RunTraceStore traces;
  private final Clock clock;

  public KnowledgeGraphExplorer(
      final GraphReader graph,
      final OntologyVocabulary vocabulary,
      final String baseUri,
      final RunTraceStore traces,
      final Clock clock) {
    this.graph = Objects.requireNonNull(graph);
    this.vocabulary = Objects.requireNonNull(vocabulary);
    this.cards = new NodeCardReader(graph, vocabulary, baseUri);
    this.terms = new OntologyTerms(graph);
    this.descriptions = new PropertyDescriptions(vocabulary);
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
        cardsFor(run).read(matched.subList(0, Math.min(request.limit(), matched.size())));
    final List<NodeCard> found =
        read.subList(
            0,
            PageBudget.cardsThatFit(
                read, descriptions, request.maxChars() - MemoryNote.reserveFor(read)));
    final GraphMemories.Notes notes =
        request.matches().beside(found.stream().map(NodeCard::id).collect(Collectors.toSet()));
    record(
        run,
        Operation.FIND,
        RunStep.Input.find(request.question()),
        Stream.of(
                RunSteps.served(found),
                RunSteps.memories(notes.listing()),
                RunSteps.memories(request.preferences()))
            .flatMap(List::stream)
            .toList(),
        List.of());
    return new FindResult(
        found,
        descriptions.describeCards(found),
        NotCoveredGroups.of(found),
        matched.size() - found.size(),
        lookup.skipped(),
        notes);
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

  /** The memories the graph holds, read through this caller's graph reader. */
  public GraphMemories memories() {
    return new GraphMemories(graph);
  }

  public ExpandPage expand(final RunContext run, final ExpandRequest request) {
    final ExpandPage page = new GraphExpansion(graph, cardsFor(run), vocabulary).expand(request);
    record(
        run,
        Operation.EXPAND,
        RunStep.Input.expand(
            request.nodes(),
            request.relationships().stream().map(vocabulary::compact).toList(),
            direction(request.directions()),
            request.limit(),
            request.offset()),
        Stream.of(
                RunSteps.served(page.nodes()),
                RunSteps.listed(page),
                RunSteps.memories(request.preferences()))
            .flatMap(List::stream)
            .toList(),
        RunSteps.expanded(page));
    return page;
  }

  /**
   * A query step keeps its text and the nodes it served, so the trace can draw them and an answer
   * can cite them.
   */
  public void recordQuery(final RunContext run, final String sparql, final String resultBody) {
    recordQuery(run, sparql, resultBody, List.of());
  }

  /** As above, with the caller's preferences the answer carried, recorded as shown. */
  public void recordQuery(
      final RunContext run,
      final String sparql,
      final String resultBody,
      final List<MemoryEntry> preferences) {
    record(
        run,
        Operation.QUERY,
        RunStep.Input.query(sparql),
        Stream.concat(
                QueriedNodes.of(sparql, resultBody).stream().map(RunSteps::queried),
                RunSteps.memories(preferences).stream())
            .toList(),
        List.of());
  }

  /**
   * Why a query may have returned no rows: the terms it names that the ontology does not declare,
   * with the closest declared one, and the terms no triple in the graph uses.
   */
  public NoRows explainNoRows(final String sparql) {
    final QueryTerms terms = QueryTerms.of(sparql);
    final List<NoRows.Term> undeclared =
        Stream.concat(undeclared(terms.properties(), false), undeclared(terms.classes(), true))
            .toList();
    final Set<String> named =
        undeclared.stream().map(NoRows.Term::term).collect(Collectors.toSet());
    final List<String> checkable =
        Stream.concat(terms.properties().stream(), terms.classes().stream())
            .filter(term -> !named.contains(vocabulary.compact(term)))
            .filter(ExplorationQueries::isQueryable)
            .toList();
    return NoRows.of(undeclared, unused(terms, checkable));
  }

  private Stream<NoRows.Term> undeclared(final Set<String> terms, final boolean isClass) {
    return terms.stream()
        .filter(term -> vocabulary.isUndeclared(term, isClass))
        .sorted()
        .map(
            term ->
                new NoRows.Term(
                    vocabulary.compact(term),
                    vocabulary
                        .closestDeclared(term, isClass)
                        .map(vocabulary::compact)
                        .orElse(null)));
  }

  private List<String> unused(final QueryTerms terms, final List<String> checkable) {
    if (checkable.isEmpty()) {
      return List.of();
    }
    final Set<String> used =
        SparqlRows.parse(
                graph.select(
                    ExplorationQueries.used(
                        terms.properties().stream().filter(checkable::contains).toList(),
                        terms.classes().stream().filter(checkable::contains).toList())))
            .stream()
            .map(row -> SparqlRows.iri(row, "term"))
            .collect(Collectors.toSet());
    return checkable.stream()
        .filter(term -> !used.contains(term))
        .map(vocabulary::compact)
        .sorted()
        .toList();
  }

  /**
   * Records what an answer relied on. Only nodes the run served and edges its expansions returned
   * are accepted; the rest come back refused. Nothing is recorded when nothing is accepted.
   */
  public CiteResult cite(final RunContext run, final Citation citation) {
    final CiteOutcome outcome = cite(run, citation, List.of(), MemoryWriter.refusing(""));
    return new CiteResult(outcome.cited(), outcome.refused());
  }

  /**
   * The final step of a turn: records what the answer relied on, as {@link #cite(RunContext,
   * Citation)} does, and keeps what the turn learnt through {@code writer}. A step that cites
   * nothing is still recorded when it carried candidates.
   */
  public CiteOutcome cite(
      final RunContext run,
      final Citation citation,
      final List<LearnedCandidate> learned,
      final MemoryWriter writer) {
    final List<RunStep> steps = steps(run.run());
    final Citations.Checked checked =
        Citations.check(
            RunTraceFolder.fold(run.run(), steps),
            citation,
            name -> vocabulary.compact(vocabulary.expand(name)));
    final List<LearnedOutcome> outcomes =
        learned.isEmpty()
            ? List.of()
            : new TurnLearning(steps, nodeIris).keep(learned, writer, run.run());
    if (checked.citesAnything() || !outcomes.isEmpty()) {
      traces.append(
          run.run(),
          new RunStep(
              Operation.CITE,
              clock.millis(),
              run.principal(),
              RunStep.Input.cite(checked.result().cited().nodes()),
              List.of(),
              checked.edges(),
              outcomes));
    }
    return new CiteOutcome(
        checked.result().cited(),
        checked.result().refused(),
        outcomes.stream().map(LearnedOutcome::withoutCandidate).toList());
  }

  /** A run's recorded steps; a run that recorded none cannot be cited. */
  private List<RunStep> steps(final UUID run) {
    final List<RunStep> steps = traces.steps(run);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException(
          "No steps are recorded for run %s. A run keeps its trace for %d hours; pass the 'run' a knowledge_graph call returned."
              .formatted(run, RunTraceStore.RETENTION.toHours()));
    }
    return steps;
  }

  /** The card reader for {@code run}: its cards also carry the memories the run saved. */
  private NodeCardReader cardsFor(final RunContext run) {
    final Map<String, List<MemoryEntry>> saved =
        new TurnLearning(traces.steps(run.run()), nodeIris).savedByAnchor();
    return saved.isEmpty() ? cards : cards.withSaved(saved);
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
