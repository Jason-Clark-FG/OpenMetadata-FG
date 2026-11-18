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

package org.openmetadata.mcp.tools;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.openmetadata.mcp.util.McpResponseTrim;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.rdf.explore.SearchHit;
import org.openmetadata.service.rdf.explore.TermWords;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.search.vector.HybridSearchException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Runs the catalog search the server already has, as the caller. With semantic search on and an
 * OpenSearch backend, one hybrid search matches the question and its words by words and the
 * question by meaning over the types asked (the data asset types when none are), fused with the
 * configured RRF weights, so columns (which match only by words) come back beside tables and
 * terms. With semantic search on and no hybrid search (Elasticsearch), {@code semantic_search} and
 * the word search both run and their hits alternate, meaning first. With semantic search off, the
 * word search runs alone. When semantic search fails, the word search answers alone and the hits
 * say so. Every hit is one the caller may view.
 */
final class CatalogStartingPoints implements StartingPoints {

  private static final String QUERY = "query";
  private static final String SIZE = "size";
  private static final String FILTERS = "filters";
  private static final String FIELDS = "fields";
  private static final String ENTITY_TYPE = "entityType";
  private static final String QUERY_FILTER = "queryFilter";
  private static final String RESULTS = "results";
  private static final String ID = "id";
  private static final String PARENT_ID = "parentId";
  private static final String FULLY_QUALIFIED_NAME = "fullyQualifiedName";
  private static final int MAX_WORDS = 8;

  /** How many hybrid hits per card asked for are ranked, so each kind can take its turn. */
  private static final int KIND_POOL = 3;

  private static final String TABLE = "table";
  private static final String TYPE = "type";
  private static final String COLUMN_INDEX = "tableColumn";

  private final BooleanSupplier vectorSearchEnabled;
  private final Supplier<McpTool> semanticSearch;
  private final Supplier<McpTool> keywordSearch;
  private final HybridCatalogSearch hybridSearch;
  private final SemanticSearchTool.HitVisibility hitVisibility;

  /**
   * One hybrid search as the caller over {@code entityTypes} (the server's default scope when
   * empty), or empty when this server cannot run one.
   */
  @FunctionalInterface
  interface HybridCatalogSearch {
    Optional<List<Map<String, Object>>> search(
        String question,
        List<String> words,
        List<String> entityTypes,
        int size,
        CatalogSecurityContext caller);
  }

  CatalogStartingPoints() {
    this(
        () -> Entity.getSearchRepository().isVectorEmbeddingEnabled(),
        SemanticSearchTool::new,
        SearchMetadataTool::new,
        CatalogStartingPoints::serverHybridSearch,
        SemanticSearchTool::isViewable);
  }

  CatalogStartingPoints(
      final BooleanSupplier vectorSearchEnabled,
      final Supplier<McpTool> semanticSearch,
      final Supplier<McpTool> keywordSearch,
      final HybridCatalogSearch hybridSearch,
      final SemanticSearchTool.HitVisibility hitVisibility) {
    this.vectorSearchEnabled = Objects.requireNonNull(vectorSearchEnabled);
    this.semanticSearch = Objects.requireNonNull(semanticSearch);
    this.keywordSearch = Objects.requireNonNull(keywordSearch);
    this.hybridSearch = Objects.requireNonNull(hybridSearch);
    this.hitVisibility = Objects.requireNonNull(hitVisibility);
  }

  /** Filtered by the caller's search access policies as keyword search is. */
  private static Optional<List<Map<String, Object>>> serverHybridSearch(
      final String question,
      final List<String> words,
      final List<String> entityTypes,
      final int size,
      final CatalogSecurityContext caller) {
    final SearchRepository search = Entity.getSearchRepository();
    return search.getVectorIndexService() == null
        ? Optional.empty()
        : search.hybridSearch(
            question, words, entityTypes, size, DefaultAuthorizer.getSubjectContext(caller));
  }

  /**
   * Nothing is searched for a question with no meaningful words. Semantic search reads the whole
   * question. Keyword search needs every word of its query, so a question of several words is
   * searched whole and then word by word, and the hits are ranked by {@link CatalogRanking}.
   */
  @Override
  public CatalogHits search(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final KnowledgeGraphCall.Find find)
      throws IOException {
    final List<String> words = TermWords.of(find.question()).stream().limit(MAX_WORDS).toList();
    return words.isEmpty()
        ? CatalogHits.of(List.of())
        : hitsFor(authorizer, securityContext, find, words);
  }

  private CatalogHits hitsFor(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final KnowledgeGraphCall.Find find,
      final List<String> words)
      throws IOException {
    return vectorSearchEnabled.getAsBoolean()
        ? meaningAndWords(authorizer, securityContext, find, words)
        : CatalogHits.of(keywordHits(authorizer, securityContext, find, words));
  }

  private CatalogHits meaningAndWords(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final KnowledgeGraphCall.Find find,
      final List<String> words)
      throws IOException {
    try {
      final Optional<List<Map<String, Object>>> hybrid =
          hybridSearch.search(
              find.question(),
              words.size() > 1 ? words : List.of(),
              find.catalogTypes(),
              find.limit() * KIND_POOL,
              securityContext);
      return hybrid.isPresent()
          ? CatalogHits.of(byKind(viewable(authorizer, securityContext, hybrid.get())))
          : merged(authorizer, securityContext, find, words);
    } catch (HybridSearchException failed) {
      return CatalogHits.skipped(
          keywordHits(authorizer, securityContext, find, words), failed.getMessage());
    }
  }

  /**
   * Each kind's best hit, in rank order, then each kind's second, and so on: a term, a table and a
   * column are different ways into one question, and a run of one kind would crowd the others out.
   */
  static List<SearchHit> byKind(final List<SearchHit> ranked) {
    final Map<String, Integer> seen = new HashMap<>();
    final List<Integer> rounds =
        ranked.stream().map(hit -> seen.merge(hit.entityType(), 1, Integer::sum)).toList();
    return IntStream.range(0, ranked.size())
        .boxed()
        .sorted(Comparator.comparing(rounds::get))
        .map(ranked::get)
        .toList();
  }

  /** Without hybrid search: meaning hits and word hits alternate, meaning first, each once. */
  private CatalogHits merged(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final KnowledgeGraphCall.Find find,
      final List<String> words)
      throws IOException {
    final List<SearchHit> byWords = keywordHits(authorizer, securityContext, find, words);
    try {
      final List<SearchHit> byMeaning =
          hits(
              requireSucceeded(
                  semanticSearch
                      .get()
                      .execute(authorizer, securityContext, semanticArguments(find))),
              PARENT_ID);
      return CatalogHits.of(alternate(byMeaning, byWords));
    } catch (IllegalStateException failed) {
      return CatalogHits.skipped(byWords, failed.getMessage());
    }
  }

  private static List<SearchHit> alternate(
      final List<SearchHit> first, final List<SearchHit> second) {
    return IntStream.range(0, Math.max(first.size(), second.size()))
        .boxed()
        .flatMap(
            index ->
                Stream.of(first, second)
                    .filter(hits -> index < hits.size())
                    .map(hits -> hits.get(index)))
        .distinct()
        .toList();
  }

  private List<SearchHit> viewable(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final List<Map<String, Object>> rows) {
    return rows.stream()
        .filter(row -> hitVisibility.isViewable(authorizer, securityContext, visibleAs(row)))
        .flatMap(row -> hit(row, PARENT_ID))
        .toList();
  }

  /** What a hit's visibility is judged by: a column by its table, any other hit by itself. */
  static Map<String, Object> visibleAs(final Map<String, Object> hit) {
    return COLUMN_INDEX.equals(hit.get(ENTITY_TYPE)) && hit.get(TABLE) instanceof Map<?, ?> table
        ? Map.of(
            ENTITY_TYPE,
            Objects.toString(table.get(TYPE), TABLE),
            PARENT_ID,
            Objects.toString(table.get(ID), ""),
            FULLY_QUALIFIED_NAME,
            Objects.toString(table.get(FULLY_QUALIFIED_NAME), ""))
        : hit;
  }

  private List<SearchHit> keywordHits(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final KnowledgeGraphCall.Find find,
      final List<String> words)
      throws IOException {
    final CatalogRanking ranking = new CatalogRanking();
    final Set<Integer> everyWord =
        IntStream.range(0, words.size()).boxed().collect(Collectors.toSet());
    ranking.add(keyword(authorizer, securityContext, find, find.question()), everyWord);
    for (int index = 0; words.size() > 1 && index < words.size(); index++) {
      ranking.add(keyword(authorizer, securityContext, find, words.get(index)), Set.of(index));
    }
    return ranking.ordered();
  }

  private List<SearchHit> keyword(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final KnowledgeGraphCall.Find find,
      final String query)
      throws IOException {
    return hits(
        requireSucceeded(
            keywordSearch
                .get()
                .execute(authorizer, securityContext, keywordArguments(find, query))),
        ID);
  }

  private static Map<String, Object> semanticArguments(final KnowledgeGraphCall.Find find) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put(QUERY, find.question());
    arguments.put(SIZE, find.limit());
    final List<String> catalogTypes = find.catalogTypes();
    if (!catalogTypes.isEmpty()) {
      arguments.put(FILTERS, Map.of(ENTITY_TYPE, catalogTypes));
    }
    return arguments;
  }

  private static Map<String, Object> keywordArguments(
      final KnowledgeGraphCall.Find find, final String query) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put(QUERY, query);
    arguments.put(SIZE, find.limit());
    arguments.put(FIELDS, ID);
    final List<String> catalogTypes = find.catalogTypes();
    if (catalogTypes.size() == 1) {
      arguments.put(ENTITY_TYPE, catalogTypes.getFirst());
    } else if (!catalogTypes.isEmpty()) {
      arguments.put(QUERY_FILTER, entityTypesFilter(catalogTypes));
    }
    return arguments;
  }

  private static String entityTypesFilter(final List<String> entityTypes) {
    return JsonUtils.pojoToJson(
        Map.of(
            QUERY,
            Map.of(
                "bool",
                Map.of("filter", List.of(Map.of("terms", Map.of(ENTITY_TYPE, entityTypes)))))));
  }

  private static Map<String, Object> requireSucceeded(final Map<String, Object> response) {
    final Object error = response.get(McpResponseTrim.ERROR_KEY);
    if (error != null) {
      throw new IllegalStateException("Catalog search for find failed: " + error);
    }
    return response;
  }

  private static List<SearchHit> hits(final Map<String, Object> response, final String idField) {
    return response.get(RESULTS) instanceof List<?> results
        ? results.stream().flatMap(result -> hit(result, idField)).toList()
        : List.of();
  }

  /** A hit names a node only with the identity the projection mints it from. */
  private static Stream<SearchHit> hit(final Object result, final String idField) {
    return result instanceof Map<?, ?> fields
            && fields.get(ENTITY_TYPE) instanceof String entityType
        ? Stream.of(
                new SearchHit(
                    entityType, text(fields, FULLY_QUALIFIED_NAME), uuid(text(fields, idField))))
            .filter(CatalogStartingPoints::isMintable)
        : Stream.empty();
  }

  private static boolean isMintable(final SearchHit hit) {
    return Entity.TABLE_COLUMN.equals(hit.entityType())
        ? hit.fullyQualifiedName() != null
        : hit.id() != null;
  }

  private static String text(final Map<?, ?> fields, final String name) {
    return fields.get(name) instanceof String value ? value : null;
  }

  private static UUID uuid(final String value) {
    return Optional.ofNullable(value).map(UUID::fromString).orElse(null);
  }
}
