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
package org.openmetadata.service.aicontext.harness;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.service.search.SearchClient.GLOBAL_SEARCH_ALIAS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.entity.data.Metric;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.harness.ContextItem.AssetRef;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.MetricRepository;
import org.openmetadata.service.search.SearchAggregation;
import org.openmetadata.service.search.SearchAggregationNode;
import org.openmetadata.service.search.SearchListFilter;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/**
 * Finds the data assets that answer the glossary terms and metrics a compile selected. A term is
 * answered by the assets tagged with it, a metric by the assets it declares in its {@code assets}
 * relationship. Both lookups run as the caller, so an asset the caller cannot search is never
 * named, and each is one aggregation per certification group for all items, not one per item.
 */
@Slf4j
final class KnowledgeAssetLookup {
  static final int MAX_ITEMS = 20;
  private static final int MAX_ASSETS_PER_METRIC = 10;
  private static final String TERMS = "knowledge_keys";
  private static final String HITS = "knowledge_assets";
  private static final String TAGS_FQN = "tags.tagFQN";
  private static final String ID_KEYWORD = "id.keyword";
  private static final String CERTIFICATION = "certification.tagLabel.tagFQN";
  // Columns carry their table's tags and certification in the index; naming one hides the table.
  private static final List<String> NOT_DATA_ASSETS =
      List.of(Entity.GLOSSARY_TERM, Entity.TABLE_COLUMN, Entity.DATA_PRODUCT);
  private static final List<String> SOURCE_FIELDS =
      List.of("fullyQualifiedName", "entityType", "certification", "entityStatus");

  private final SearchRepository searchRepository;
  private final SubjectContext caller;

  KnowledgeAssetLookup(final SearchRepository searchRepository, final SecurityContext security) {
    this.searchRepository = searchRepository;
    this.caller = DefaultAuthorizer.getSubjectContext(security);
  }

  /** The assets for each item, keyed by the item's entity type and fully qualified name. */
  Map<AssetRef, List<AssetUse>> usesOf(final Collection<AssetRef> items) {
    final List<AssetRef> bounded = items.stream().distinct().limit(MAX_ITEMS).toList();
    final Map<AssetRef, List<AssetUse>> uses = new HashMap<>();
    uses.putAll(termUses(ofType(bounded, Entity.GLOSSARY_TERM)));
    uses.putAll(metricUses(ofType(bounded, Entity.METRIC)));
    return uses;
  }

  private static List<AssetRef> ofType(final List<AssetRef> items, final String entityType) {
    return items.stream().filter(item -> entityType.equals(item.entityType())).toList();
  }

  private Map<AssetRef, List<AssetUse>> termUses(final List<AssetRef> terms) {
    final Map<String, List<AssetUse>> byTag =
        topAssets(TAGS_FQN, terms.stream().map(term -> lower(term.fullyQualifiedName())).toList());
    final Map<AssetRef, List<AssetUse>> uses = new LinkedHashMap<>();
    terms.forEach(
        term ->
            uses.put(
                term,
                AssetUse.choose(byTag.getOrDefault(lower(term.fullyQualifiedName()), List.of()))));
    return uses;
  }

  private Map<AssetRef, List<AssetUse>> metricUses(final List<AssetRef> metrics) {
    final Map<AssetRef, List<String>> assetIds = new LinkedHashMap<>();
    metrics.forEach(metric -> assetIds.put(metric, assetIdsOf(metric)));
    final Map<String, List<AssetUse>> byId =
        topAssets(ID_KEYWORD, assetIds.values().stream().flatMap(List::stream).distinct().toList());
    final Map<AssetRef, List<AssetUse>> uses = new LinkedHashMap<>();
    assetIds.forEach(
        (metric, ids) ->
            uses.put(
                metric,
                AssetUse.choose(
                    ids.stream()
                        .flatMap(id -> byId.getOrDefault(id, List.of()).stream())
                        .toList())));
    return uses;
  }

  private static List<String> assetIdsOf(final AssetRef metric) {
    List<String> ids = List.of();
    try {
      final Metric loaded =
          (Metric)
              Entity.getEntityByName(
                  Entity.METRIC, metric.fullyQualifiedName(), "", Include.NON_DELETED);
      ids =
          ((MetricRepository) Entity.getEntityRepository(Entity.METRIC))
                  .listAssets(loaded.getId(), MAX_ASSETS_PER_METRIC, 0, null, null, null)
                  .getData()
                  .stream()
                  .map(direction -> lower(direction.getAsset().getId().toString()))
                  .toList();
    } catch (EntityNotFoundException exception) {
      LOG.debug("Selected metric {} no longer exists", metric.fullyQualifiedName());
    } catch (RuntimeException exception) {
      LOG.warn(
          "Could not read the assets of {}: {}",
          metric.fullyQualifiedName(),
          exception.getMessage());
    }
    return ids;
  }

  /**
   * The certified assets and the other assets for each key, in one aggregation per group. The
   * groups are separate so a certified asset is not crowded out of a key's few top hits by others.
   */
  private Map<String, List<AssetUse>> topAssets(final String field, final List<String> keys) {
    final Map<String, List<AssetUse>> byKey = new HashMap<>();
    if (!keys.isEmpty()) {
      try {
        for (final boolean certified : new boolean[] {true, false}) {
          aggregate(field, keys, certified)
              .forEach(
                  (key, hits) -> byKey.computeIfAbsent(key, k -> new ArrayList<>()).addAll(hits));
        }
      } catch (IOException | RuntimeException exception) {
        LOG.warn("Could not look up the assets of selected knowledge: {}", exception.getMessage());
        byKey.clear();
      }
    }
    return byKey;
  }

  private Map<String, List<AssetUse>> aggregate(
      final String field, final List<String> keys, final boolean certified) throws IOException {
    final SearchAggregationNode terms = SearchAggregation.terms(TERMS, field, keys.size(), keys);
    terms.addChild(
        SearchAggregation.topHits(HITS, AssetUse.MAX_PER_ITEM, ID_KEYWORD, "asc", SOURCE_FIELDS));
    final JsonObject response =
        searchRepository.aggregate(
            filter(field, keys, certified),
            GLOBAL_SEARCH_ALIAS,
            SearchAggregation.fromTree(terms),
            new SearchListFilter(),
            caller);
    return buckets(response);
  }

  private static String filter(
      final String field, final List<String> keys, final boolean certified) {
    final ObjectNode bool = JsonUtils.getObjectMapper().createObjectNode();
    final ObjectNode query = bool.putObject("query").putObject("bool");
    final ArrayNode must = query.putArray("must");
    must.addObject().putObject("terms").set(field, JsonUtils.valueToTree(keys));
    must.addObject().putObject("term").put("deleted", false);
    final ArrayNode mustNot = query.putArray("must_not");
    mustNot.addObject().putObject("term").put("entityStatus", "Deprecated");
    mustNot
        .addObject()
        .putObject("terms")
        .set("entityType", JsonUtils.valueToTree(NOT_DATA_ASSETS));
    final ObjectNode exists = JsonUtils.getObjectMapper().createObjectNode();
    exists.putObject("exists").put("field", CERTIFICATION);
    (certified ? must : mustNot).add(exists);
    return bool.toString();
  }

  private static Map<String, List<AssetUse>> buckets(final JsonObject response) {
    final Map<String, List<AssetUse>> byKey = new HashMap<>();
    final JsonObject terms = child(response, TERMS);
    if (terms != null && terms.containsKey("buckets")) {
      for (final JsonValue value : terms.getJsonArray("buckets")) {
        final JsonObject bucket = value.asJsonObject();
        byKey.put(lower(bucket.getString("key")), hits(child(bucket, HITS)));
      }
    }
    return byKey;
  }

  private static List<AssetUse> hits(final JsonObject topHits) {
    final List<AssetUse> uses = new ArrayList<>();
    if (topHits != null) {
      final JsonArray hits = topHits.getJsonObject("hits").getJsonArray("hits");
      for (final JsonValue hit : hits) {
        final JsonNode source =
            JsonUtils.readTree(hit.asJsonObject().getJsonObject("_source").toString());
        uses.add(use(source));
      }
    }
    return uses;
  }

  private static AssetUse use(final JsonNode source) {
    final String certification =
        source.path("certification").path("tagLabel").path("tagFQN").asText(null);
    return new AssetUse(
        source.path("fullyQualifiedName").asText(null),
        source.path("entityType").asText(null),
        nullOrEmpty(certification) ? null : certification,
        "Deprecated".equalsIgnoreCase(source.path("entityStatus").asText("")));
  }

  private static JsonObject child(final JsonObject parent, final String name) {
    return parent == null
        ? null
        : parent.keySet().stream()
            .filter(key -> key.equals(name) || key.endsWith("#" + name))
            .findFirst()
            .map(parent::getJsonObject)
            .orElse(null);
  }

  private static String lower(final String value) {
    return value.toLowerCase(Locale.ROOT);
  }
}
