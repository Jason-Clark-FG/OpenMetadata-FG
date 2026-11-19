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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.jena.query.ParameterizedSparqlString;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.rdf.SparqlQueryExecutionGuard.QueryTimeoutException;
import org.openmetadata.service.rdf.SparqlQueryLimits.OutputLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Context memories as the graph holds them: those anchored to a node ({@code om:appliedTo} from
 * the node), those matching a question, and an owner's preferences. A memory in a retired stage is
 * left out; one not yet approved is marked as awaiting review. Who may read a memory is not
 * decided here (ADR:2026-11-17-one-knowledge-graph-tool-explores-through-node-cards, v19).
 */
public final class GraphMemories {

  static final int SHOWN = 5;
  static final int PREFERENCE_BUDGET = 2_000;
  static final int MAX_CANDIDATES = 500;
  static final int MAX_WORDS = OntologyTerms.MAX_WORDS;

  private static final Logger LOG = LoggerFactory.getLogger(GraphMemories.class);
  private static final int TEXT_READ = 600;
  private static final int PREFERENCE_READ = PREFERENCE_BUDGET + 1;
  private static final String KEY = "key";
  private static final String CARD_REST =
      "Showing the %d most recently changed of %d memories about this asset; the other %d are"
          + " older. Expand om:appliedTo (outgoing) from this card to list them all.";
  private static final String NOTES_REST =
      "Showing the %d best matches of %d memories; ask a more specific question to narrow them.";
  private static final String PREFIXES =
      """
      PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      PREFIX om: <https://open-metadata.org/ontology/>
      PREFIX dct: <http://purl.org/dc/terms/>
      PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
      """;
  private static final String COLUMNS =
      """
      (SAMPLE(?t) AS ?title) (SAMPLE(?q) AS ?question) (MAX(?ql) AS ?questionLength)
      (SAMPLE(?a) AS ?answer) (MAX(?al) AS ?answerLength) (SAMPLE(?s) AS ?stage)
      (MAX(?c) AS ?changed) (MAX(STR(?m)) AS ?modified)""";
  private static final String FIELDS =
      """
      ?memory rdf:type om:ContextMemory .
      OPTIONAL { ?memory om:title ?t }
      OPTIONAL { ?memory om:question ?fullQuestion
                 BIND(SUBSTR(STR(?fullQuestion), 1, %1$d) AS ?q)
                 BIND(STRLEN(STR(?fullQuestion)) AS ?ql) }
      OPTIONAL { ?memory om:answer ?fullAnswer
                 BIND(SUBSTR(STR(?fullAnswer), 1, %1$d) AS ?a)
                 BIND(STRLEN(STR(?fullAnswer)) AS ?al) }
      OPTIONAL { ?memory om:entityStatus ?s }
      OPTIONAL { ?memory om:lastContentUpdatedAt ?c }
      OPTIONAL { ?memory dct:modified ?m FILTER(DATATYPE(?m) = xsd:dateTime
                 && REGEX(STR(?m), "^[0-9]{4}-")) }
      FILTER(!BOUND(?s) || ?s IN (%2$s))
      """;
  private static final String ON_CARDS =
      """
      SELECT ?anchor ?memory %s WHERE {
        VALUES ?anchor { %s }
        ?anchor om:appliedTo ?memory .
        %s
      } GROUP BY ?anchor ?memory
      """;
  private static final String MATCHING =
      """
      SELECT ?memory %s (MAX(?h) AS ?hits)
             (GROUP_CONCAT(DISTINCT STR(?anchor); separator=" ") AS ?anchors) WHERE {
        %s
        OPTIONAL { ?anchor om:appliedTo ?memory }
        OPTIONAL { ?memory om:memoryType ?type }
        FILTER(!BOUND(?type) || ?type != "Preference")
        BIND(LCASE(CONCAT(COALESCE(STR(?t), ""), " ", COALESCE(STR(?fullQuestion), ""), " ",
             COALESCE(STR(?fullAnswer), ""))) AS ?text)
        BIND(%s AS ?h)
        FILTER(?h > 0)
      } GROUP BY ?memory ORDER BY ?memory LIMIT %d
      """;
  private static final String OWNED =
      """
      SELECT ?memory %s (MAX(?p) AS ?pinned) WHERE {
        ?memory om:hasOwner %s .
        %s
        OPTIONAL { ?memory om:memoryType ?type }
        OPTIONAL { ?memory om:pinned ?p }
        FILTER((BOUND(?type) && ?type = "Preference") || (BOUND(?p) && ?p = true))
      } GROUP BY ?memory
      """;

  private final GraphReader graph;

  public GraphMemories(final GraphReader graph) {
    this.graph = Objects.requireNonNull(graph);
  }

  /** The memories anchored to each of {@code nodes}; a node with none is absent. */
  Map<String, MemoryListing> onCards(final Collection<String> nodes) {
    final List<String> queryable = nodes.stream().filter(ExplorationQueries::isQueryable).toList();
    final Map<String, List<StoredMemory>> byAnchor = new LinkedHashMap<>();
    if (!queryable.isEmpty()) {
      SparqlRows.parse(graph.select(onCardsQuery(queryable)))
          .forEach(
              row ->
                  byAnchor
                      .computeIfAbsent(SparqlRows.iri(row, "anchor"), ignored -> new ArrayList<>())
                      .add(StoredMemory.of(row)));
    }
    final Map<String, MemoryListing> listings = new LinkedHashMap<>();
    byAnchor.forEach(
        (anchor, memories) ->
            listings.put(anchor, listing(memories, StoredMemory.NEWEST_FIRST, CARD_REST)));
    return listings;
  }

  /** As {@link #matching} then {@link Matches#beside}. */
  public Notes notes(final String question, final Set<String> shownCards) {
    return matching(question).beside(shownCards);
  }

  /**
   * The memories whose title, question or answer holds a word of the question, read once so find
   * can size them before it knows which cards it shows. Preferences are left out; they come once
   * per run instead.
   */
  public Matches matching(final String question) {
    final List<String> words = TermWords.of(question).stream().limit(MAX_WORDS).toList();
    Matches matches = new Matches(List.of(), null);
    try {
      matches = words.isEmpty() ? matches : new Matches(read(words), null);
    } catch (QueryTimeoutException | OutputLimitExceededException failure) {
      LOG.warn("[KnowledgeGraph] find answered without memories: {}", failure.getMessage());
      matches =
          new Matches(
              List.of(),
              "Memories were not looked for (%s); some may match the question."
                  .formatted(failure.getMessage()));
    }
    return matches;
  }

  private List<StoredMemory> read(final List<String> words) {
    return SparqlRows.parse(graph.select(matchingQuery(words))).stream()
        .map(StoredMemory::of)
        .toList();
  }

  /**
   * The memories {@code owner} owns that are preferences or that they pinned: pinned first, then
   * the newest content, then the memory, kept while they fit {@link #PREFERENCE_BUDGET}
   * characters; one that does not fit is skipped, never cut. The question plays no part.
   */
  public List<MemoryEntry> preferences(final String owner) {
    final List<MemoryEntry> kept = new ArrayList<>();
    if (ExplorationQueries.isQueryable(owner)) {
      long used = 0;
      for (final MemoryEntry entry : ownedStanding(owner)) {
        final long cost = JsonUtils.pojoToJson(entry).length() + 1L;
        if (used + cost <= PREFERENCE_BUDGET) {
          kept.add(entry);
          used += cost;
        }
      }
    }
    return List.copyOf(kept);
  }

  private List<MemoryEntry> ownedStanding(final String owner) {
    return SparqlRows.parse(graph.select(ownedQuery(owner))).stream()
        .map(StoredMemory::of)
        .sorted(StoredMemory.PINNED_FIRST)
        .map(StoredMemory::whole)
        .flatMap(Optional::stream)
        .toList();
  }

  /**
   * {@code listing} with the memories a run saved that it does not hold yet, newest first: the
   * graph is written through a queue, so a memory saved a moment ago may not be in it.
   */
  static MemoryListing withSaved(final MemoryListing listing, final List<MemoryEntry> saved) {
    final Set<String> held =
        listing == null
            ? Set.of()
            : listing.shown().stream().map(MemoryEntry::id).collect(Collectors.toSet());
    final List<MemoryEntry> added =
        saved.reversed().stream().filter(memory -> !held.contains(memory.id())).toList();
    return added.isEmpty() ? listing : merged(listing, added);
  }

  private static MemoryListing merged(final MemoryListing listing, final List<MemoryEntry> added) {
    final List<MemoryEntry> all = new ArrayList<>(added);
    final int held = listing == null ? 0 : listing.total();
    if (listing != null) {
      all.addAll(listing.shown());
    }
    final List<MemoryEntry> shown = all.stream().limit(SHOWN).toList();
    final int total = held + added.size();
    return new MemoryListing(
        shown,
        total,
        total > shown.size()
            ? CARD_REST.formatted(shown.size(), total, total - shown.size())
            : null);
  }

  private static MemoryListing listing(
      final List<StoredMemory> memories, final Comparator<StoredMemory> order, final String rest) {
    final List<MemoryEntry> shown =
        memories.stream().sorted(order).limit(SHOWN).map(StoredMemory::cut).toList();
    final int total = memories.size();
    return new MemoryListing(
        shown,
        total,
        total > shown.size() ? rest.formatted(shown.size(), total, total - shown.size()) : null);
  }

  private static String onCardsQuery(final List<String> nodes) {
    return PREFIXES
        + ON_CARDS.formatted(COLUMNS, ExplorationQueries.iris(nodes), fields(TEXT_READ));
  }

  private static String matchingQuery(final List<String> words) {
    final String hits =
        IntStream.range(0, words.size())
            .mapToObj(index -> "IF(CONTAINS(?text, ?" + KEY + index + "), 1, 0)")
            .collect(Collectors.joining(" + "));
    final ParameterizedSparqlString query =
        new ParameterizedSparqlString(
            PREFIXES + MATCHING.formatted(COLUMNS, fields(TEXT_READ), hits, MAX_CANDIDATES));
    IntStream.range(0, words.size())
        .forEach(index -> query.setLiteral(KEY + index, TermWords.candidateKey(words.get(index))));
    return query.toString();
  }

  private static String ownedQuery(final String owner) {
    return PREFIXES
        + OWNED.formatted(
            COLUMNS, ExplorationQueries.iris(List.of(owner)), fields(PREFERENCE_READ));
  }

  private static String fields(final int textRead) {
    return FIELDS.formatted(
        textRead,
        StoredMemory.SHOWN_STAGES.stream()
            .map(stage -> "\"" + stage + "\"")
            .collect(Collectors.joining(", ")));
  }

  /** The memories find offers beside its cards, or why they could not be looked for. */
  public record Notes(MemoryListing listing, String skipped) {}

  /** The memories a question matched, or why none were looked for. */
  public record Matches(List<StoredMemory> memories, String skipped) {

    public Matches {
      memories = List.copyOf(memories);
    }

    /**
     * Those not anchored to a card in {@code shownCards}, best match first: the most question
     * words, then the newest content.
     */
    public Notes beside(final Set<String> shownCards) {
      final Predicate<StoredMemory> onAShownCard =
          memory -> memory.anchors().stream().anyMatch(shownCards::contains);
      final List<StoredMemory> offered = memories.stream().filter(onAShownCard.negate()).toList();
      return new Notes(
          offered.isEmpty() ? null : listing(offered, StoredMemory.BEST_MATCH_FIRST, NOTES_REST),
          skipped);
    }
  }
}
