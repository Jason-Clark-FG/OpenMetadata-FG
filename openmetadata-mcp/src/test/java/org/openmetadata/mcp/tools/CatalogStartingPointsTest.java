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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.rdf.explore.SearchHit;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

class CatalogStartingPointsTest {

  private static final Authorizer AUTHORIZER = mock(Authorizer.class);
  private static final CatalogSecurityContext CALLER = RdfToolAuthorization.caller("analyst");
  private static final UUID ORDERS = UUID.randomUUID();

  private final RecordingSearch semantic =
      new RecordingSearch(
          Map.of(
              "results",
              List.of(
                  Map.of(
                      "parentId", ORDERS.toString(),
                      "entityType", "table",
                      "fullyQualifiedName", "warehouse.shop.public.orders"))));
  private final RecordingSearch keyword =
      new RecordingSearch(
          Map.of(
              "results",
              List.of(
                  Map.of(
                      "id", ORDERS.toString(),
                      "entityType", "table",
                      "fullyQualifiedName", "warehouse.shop.public.orders"),
                  Map.of(
                      "entityType", "tableColumn",
                      "fullyQualifiedName", "warehouse.shop.public.orders.id"),
                  Map.of("entityType", "dashboard", "fullyQualifiedName", "bi.revenue"))));

  private CatalogStartingPoints startingPoints(final boolean vectorSearchEnabled) {
    return new CatalogStartingPoints(() -> vectorSearchEnabled, () -> semantic, () -> keyword);
  }

  @Test
  void vectorSearchFindsTheStartingPointsWhenEmbeddingsAreEnabled() throws Exception {
    final List<SearchHit> hits =
        startingPoints(true)
            .search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("orders", List.of("table"), 3));

    assertThat(hits)
        .containsExactly(new SearchHit("table", "warehouse.shop.public.orders", ORDERS));
    assertThat(keyword.calls).isEmpty();
    assertThat(semantic.calls.getFirst())
        .containsEntry("query", "orders")
        .containsEntry("size", 3)
        .containsEntry("filters", Map.of("entityType", List.of("table")));
  }

  @Test
  void keywordSearchFindsTheStartingPointsOtherwiseAndAsksForIds() throws Exception {
    final List<SearchHit> hits =
        startingPoints(false)
            .search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("orders", List.of(), 5));

    assertThat(hits)
        .containsExactly(
            new SearchHit("table", "warehouse.shop.public.orders", ORDERS),
            new SearchHit("tableColumn", "warehouse.shop.public.orders.id", null));
    assertThat(semantic.calls).isEmpty();
    assertThat(keyword.calls.getFirst())
        .containsEntry("query", "orders")
        .containsEntry("size", 5)
        .containsEntry("fields", "id")
        .doesNotContainKey("entityType");
  }

  @Test
  void aHitWithoutTheIdentityItsNodeIsMintedFromIsLeftOut() throws Exception {
    final List<SearchHit> hits =
        startingPoints(false)
            .search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("orders", List.of(), 5));

    assertThat(hits).extracting(SearchHit::entityType).doesNotContain("dashboard");
  }

  @Test
  void keywordSearchNarrowsToSeveralEntityTypesWithAFilter() throws Exception {
    startingPoints(false)
        .search(
            AUTHORIZER,
            CALLER,
            new KnowledgeGraphCall.Find("orders", List.of("table", "dashboard"), 5));

    assertThat(keyword.calls.getFirst().get("queryFilter").toString())
        .contains("\"terms\"", "\"entityType\"", "\"table\"", "\"dashboard\"");
  }

  @Test
  void aFailedSearchIsAFailureNotAnEmptyResult() {
    final CatalogStartingPoints failing =
        new CatalogStartingPoints(
            () -> true,
            () -> new RecordingSearch(Map.of("error", "embedding backend down")),
            () -> keyword);

    assertThatThrownBy(
            () ->
                failing.search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("x", List.of(), 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("embedding backend down");
  }

  private static final class RecordingSearch implements McpTool {
    private final Map<String, Object> response;
    private final List<Map<String, Object>> calls = new ArrayList<>();

    private RecordingSearch(final Map<String, Object> response) {
      this.response = response;
    }

    @Override
    public Map<String, Object> execute(
        final Authorizer authorizer,
        final CatalogSecurityContext securityContext,
        final Map<String, Object> params) {
      calls.add(params);
      return response;
    }

    @Override
    public Map<String, Object> execute(
        final Authorizer authorizer,
        final Limits limits,
        final CatalogSecurityContext securityContext,
        final Map<String, Object> params) {
      throw new UnsupportedOperationException();
    }
  }
}
