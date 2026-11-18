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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QuerySolution;
import org.openmetadata.service.rdf.OntologyActiveGraph;
import org.openmetadata.service.rdf.SparqlQueryExecutionGuard.QueryTimeoutException;
import org.openmetadata.service.rdf.SparqlQueryLimits.OutputLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ontology terms of the active releases whose {@code rdfs:label} or {@code skos:altLabel} meets the
 * question word for word ({@link TermMatch}), most specific first. The graph read only narrows the
 * candidates to labels that contain one of the question's words; each word is bound as a literal,
 * never written into the query text. Only the release graphs are read. A lookup the graph cannot
 * answer in time or within the output bound is reported as skipped, so the catalog half of find
 * still answers.
 */
final class OntologyTerms {

  static final int MAX_WORDS = 12;
  static final int MAX_CANDIDATE_LABELS = 500;

  private static final Logger LOG = LoggerFactory.getLogger(OntologyTerms.class);
  private static final String TERM = "term";
  private static final String LABEL = "label";
  private static final String KEY = "key";
  private static final String RELEASE_GRAPHS = "releaseGraphs";
  private static final String QUERY =
      """
      PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      SELECT DISTINCT ?term ?label WHERE {
        GRAPH ?graph {
          VALUES ?type { %s }
          ?term rdf:type ?type .
          ?term rdfs:label|skos:altLabel ?label .
        }
        FILTER(STRSTARTS(STR(?graph), ?releaseGraphs))
        FILTER(STRSTARTS(STR(?term), "http://") || STRSTARTS(STR(?term), "https://"))
        BIND(LCASE(STR(?label)) AS ?lower)
        FILTER(%s)
      } ORDER BY ?term ?label LIMIT %d
      """;

  private final GraphReader graph;

  OntologyTerms(final GraphReader graph) {
    this.graph = Objects.requireNonNull(graph);
  }

  /** At most {@code limit} terms; none, without reading the graph, when nothing is asked for. */
  TermLookup matching(final String question, final Set<OntologyTermKind> kinds, final int limit) {
    final List<String> words = TermWords.of(question).stream().limit(MAX_WORDS).toList();
    try {
      return TermLookup.found(
          kinds.isEmpty() || words.isEmpty() ? List.of() : ranked(words, kinds, limit));
    } catch (QueryTimeoutException | OutputLimitExceededException failure) {
      LOG.warn("[KnowledgeGraph] find answered without ontology terms: {}", failure.getMessage());
      return TermLookup.skipped(
          "Ontology terms were not looked for (%s); concepts and properties matching the question may exist."
              .formatted(failure.getMessage()));
    }
  }

  private List<String> ranked(
      final List<String> words, final Set<OntologyTermKind> kinds, final int limit) {
    final BinaryOperator<TermMatch> moreSpecific =
        (left, right) -> TermMatch.MOST_SPECIFIC_FIRST.compare(left, right) <= 0 ? left : right;
    return SparqlRows.parse(graph.select(query(words, kinds))).stream()
        .map(row -> match(row, words))
        .flatMap(Optional::stream)
        .collect(Collectors.toMap(TermMatch::term, match -> match, moreSpecific))
        .values()
        .stream()
        .sorted(TermMatch.MOST_SPECIFIC_FIRST)
        .map(TermMatch::term)
        .limit(limit)
        .toList();
  }

  private static Optional<TermMatch> match(final QuerySolution row, final List<String> words) {
    final String term = SparqlRows.iri(row, TERM);
    return ExplorationQueries.isQueryable(term)
        ? TermMatch.of(term, row.getLiteral(LABEL).getLexicalForm(), words)
        : Optional.empty();
  }

  private static String query(final List<String> words, final Set<OntologyTermKind> kinds) {
    final String anyKey =
        IntStream.range(0, words.size())
            .mapToObj(index -> "CONTAINS(?lower, ?" + KEY + index + ")")
            .collect(Collectors.joining(" || "));
    final ParameterizedSparqlString query =
        new ParameterizedSparqlString(QUERY.formatted(types(kinds), anyKey, MAX_CANDIDATE_LABELS));
    IntStream.range(0, words.size())
        .forEach(index -> query.setLiteral(KEY + index, TermWords.candidateKey(words.get(index))));
    query.setLiteral(RELEASE_GRAPHS, OntologyActiveGraph.GRAPH_PREFIX);
    return query.toString();
  }

  private static String types(final Set<OntologyTermKind> kinds) {
    return ExplorationQueries.iris(
        kinds.stream().sorted().flatMap(kind -> kind.typeIris().stream()).toList());
  }
}
