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
package org.openmetadata.service.aicontext.capture;

import static org.openmetadata.service.search.SearchClient.GLOBAL_SEARCH_ALIAS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.security.DefaultAuthorizer;

/**
 * Resolves the table and metric names a captured fact mentions to the assets they name, as the
 * caller: a name the caller cannot search is never found. A name is linked only when it matches
 * exactly one asset, by fully qualified name or by name, so a name two tables share links neither.
 */
@Slf4j
public final class PromptAssetLookup {
  /** One asset the lookup found. */
  public record Found(String entityType, String fullyQualifiedName) {}

  private static final int MAX_HITS = 50;
  private static final List<String> TYPES = List.of(Entity.TABLE, Entity.METRIC);

  private PromptAssetLookup() {}

  /** The assets {@code names} each match exactly once, in the order the names were given. */
  public static List<Found> find(final List<String> names, final SecurityContext security) {
    List<Found> found = List.of();
    if (!names.isEmpty()) {
      try {
        final SearchRequest request =
            new SearchRequest()
                .withQuery("*")
                .withIndex(Entity.getSearchRepository().getIndexOrAliasName(GLOBAL_SEARCH_ALIAS))
                .withQueryFilter(filter(names))
                .withSize(MAX_HITS)
                .withFetchSource(true);
        final Response response =
            Entity.getSearchRepository()
                .search(request, DefaultAuthorizer.getSubjectContext(security));
        found = choose(names, String.valueOf(response.getEntity()));
      } catch (Exception failure) {
        LOG.warn("Could not look up the assets a captured fact names: {}", failure.getMessage());
      }
    }
    return found;
  }

  /** Reads the search response; separate so the choice needs no search engine. */
  static List<Found> choose(final List<String> names, final String responseJson) {
    final List<Found> hits = new ArrayList<>();
    final JsonNode hitList = JsonUtils.readTree(responseJson).path("hits").path("hits");
    for (final JsonNode hit : hitList) {
      final JsonNode source = hit.path("_source");
      final String type = source.path("entityType").asText(null);
      final String fqn = source.path("fullyQualifiedName").asText(null);
      if (type != null && fqn != null) {
        hits.add(new Found(type, fqn));
      }
    }
    final List<Found> chosen = new ArrayList<>();
    for (final String name : names) {
      final List<Found> matching =
          hits.stream().filter(hit -> isNamed(hit.fullyQualifiedName(), name)).distinct().toList();
      if (matching.size() == 1 && !chosen.contains(matching.getFirst())) {
        chosen.add(matching.getFirst());
      }
    }
    return chosen;
  }

  private static boolean isNamed(final String fqn, final String name) {
    final String lowered = fqn.toLowerCase(Locale.ROOT);
    final String wanted = name.toLowerCase(Locale.ROOT);
    return lowered.equals(wanted) || lowered.endsWith("." + wanted);
  }

  private static String filter(final List<String> names) {
    final ObjectNode root = JsonUtils.getObjectMapper().createObjectNode();
    final ObjectNode bool = root.putObject("query").putObject("bool");
    final ArrayNode should = bool.putArray("should");
    should.addObject().putObject("terms").set("name.keyword", JsonUtils.valueToTree(names));
    should
        .addObject()
        .putObject("terms")
        .set("fullyQualifiedName.keyword", JsonUtils.valueToTree(names));
    bool.put("minimum_should_match", 1);
    final ArrayNode must = bool.putArray("must");
    must.addObject().putObject("terms").set("entityType", JsonUtils.valueToTree(TYPES));
    must.addObject().putObject("term").put("deleted", false);
    return root.toString();
  }
}
