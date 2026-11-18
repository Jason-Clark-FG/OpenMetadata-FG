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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.openmetadata.mcp.util.McpResponseTrim;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.rdf.explore.SearchHit;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Runs the catalog search the server already has, as the caller: {@code semantic_search} when
 * vector embeddings are enabled, {@code search_metadata} otherwise. Each tool applies its own
 * visibility rules, so the hits are exactly what the caller would get from it.
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

  private final BooleanSupplier vectorSearchEnabled;
  private final Supplier<McpTool> semanticSearch;
  private final Supplier<McpTool> keywordSearch;

  CatalogStartingPoints() {
    this(
        () -> Entity.getSearchRepository().isVectorEmbeddingEnabled(),
        SemanticSearchTool::new,
        SearchMetadataTool::new);
  }

  CatalogStartingPoints(
      final BooleanSupplier vectorSearchEnabled,
      final Supplier<McpTool> semanticSearch,
      final Supplier<McpTool> keywordSearch) {
    this.vectorSearchEnabled = Objects.requireNonNull(vectorSearchEnabled);
    this.semanticSearch = Objects.requireNonNull(semanticSearch);
    this.keywordSearch = Objects.requireNonNull(keywordSearch);
  }

  @Override
  public List<SearchHit> search(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final KnowledgeGraphCall.Find find)
      throws IOException {
    final boolean semantic = vectorSearchEnabled.getAsBoolean();
    final Map<String, Object> response =
        semantic
            ? semanticSearch.get().execute(authorizer, securityContext, semanticArguments(find))
            : keywordSearch.get().execute(authorizer, securityContext, keywordArguments(find));
    return hits(requireSucceeded(response), semantic ? PARENT_ID : ID);
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

  private static Map<String, Object> keywordArguments(final KnowledgeGraphCall.Find find) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put(QUERY, find.question());
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
