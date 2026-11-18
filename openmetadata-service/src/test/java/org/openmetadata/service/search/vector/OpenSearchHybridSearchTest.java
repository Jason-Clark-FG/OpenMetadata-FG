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
package org.openmetadata.service.search.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.search.vector.client.EmbeddingClient;
import os.org.opensearch.client.opensearch.OpenSearchClient;
import os.org.opensearch.client.opensearch.generic.Body;
import os.org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import os.org.opensearch.client.opensearch.generic.Request;
import os.org.opensearch.client.opensearch.generic.Response;

/** The one hybrid query: what it asks the cluster, and how its hits come back. */
class OpenSearchHybridSearchTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String LEXICAL = "{\"match\":{\"name\":\"customer lifetime value\"}}";
  private static final String PIPELINE =
      "{\"phase_results_processors\":[{\"score-ranker-processor\":{}}]}";

  private OpenSearchGenericClient genericClient;
  private OpenSearchVectorService service;
  private final List<Request> requests = new ArrayList<>();

  @BeforeEach
  void setUp() {
    final OpenSearchClient client = mock(OpenSearchClient.class);
    genericClient = mock(OpenSearchGenericClient.class);
    when(client.generic()).thenReturn(genericClient);
    final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    when(embeddings.embedQuery(any())).thenReturn(new float[] {0.25f, 0.5f});
    service = new OpenSearchVectorService(client, embeddings);
  }

  private void clusterAnswers(final int status, final String body) throws IOException {
    final Response response = mock(Response.class);
    final Body content = mock(Body.class);
    when(response.getStatus()).thenReturn(status);
    when(response.getBody()).thenReturn(Optional.of(content));
    when(content.bodyAsString()).thenReturn(body);
    when(content.bodyAsBytes()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
    when(genericClient.execute(any()))
        .thenAnswer(
            invocation -> {
              requests.add(invocation.getArgument(0, Request.class));
              return response;
            });
  }

  private static HybridSearchRequest request() {
    return new HybridSearchRequest(
        "customer lifetime value",
        LEXICAL,
        List.of("dataAssetEmbeddings", "tableColumn"),
        Map.of("entityType", List.of("table", "tableColumn")),
        5,
        null);
  }

  @Test
  void theKnnLegOfAHybridQuerySkipsShardsWithoutEmbeddingsInsteadOfFailingThem() {
    final String knn =
        VectorSearchQueryBuilder.buildHybridQuery(
            new float[] {0.1f},
            new VectorSearchParameters(null, Map.of(), 0, 0, 10, 0.0, null, null, null));
    final String plain = VectorSearchQueryBuilder.buildQuery(new float[] {0.1f}, 10, Map.of(), 0.0);

    assertThat(knn).contains("\"ignore_unmapped\":true");
    assertThat(plain).doesNotContain("ignore_unmapped");
  }

  @Test
  void oneQueryAsksByWordsAndByMeaningOverTheEmbeddingsAndColumnIndexesWithTheInlinePipeline()
      throws IOException {
    clusterAnswers(200, "{\"hits\":{\"hits\":[]}}");

    service.hybridSearch(request(), PIPELINE);

    final Request sent = requests.getFirst();
    assertThat(sent.getEndpoint()).isEqualTo("/dataAssetEmbeddings,tableColumn/_search");
    final JsonNode body = JSON.readTree(sent.getBody().orElseThrow().bodyAsString());
    final JsonNode legs = body.path("query").path("hybrid").path("queries");
    assertThat(legs).hasSize(2);
    assertThat(legs.get(0).toString()).contains("customer lifetime value", "tableColumn");
    assertThat(legs.get(1).path("knn").path("embedding").path("ignore_unmapped").asBoolean())
        .isTrue();
    assertThat(body.path("search_pipeline")).isEqualTo(JSON.readTree(PIPELINE));
    assertThat(body.path("size").asInt()).isGreaterThanOrEqualTo(5);
  }

  @Test
  void hitsComeBackOncePerEntityInRankOrderWithChunksFoldedIntoTheirEntity() throws IOException {
    clusterAnswers(
        200,
        """
        {"hits":{"hits":[
          {"_score":0.9,"_source":{"parentId":"t1","entityType":"table","fullyQualifiedName":"s.customers"}},
          {"_score":0.8,"_source":{"id":"c1","entityType":"tableColumn","fullyQualifiedName":"s.orders.lifetime_spend"}},
          {"_score":0.7,"_source":{"parentId":"t1","entityType":"table","fullyQualifiedName":"s.customers"}},
          {"_score":0.6,"_source":{"id":"g1","entityType":"glossaryTerm","fullyQualifiedName":"g.Lifetime Spend"}}
        ]}}
        """);

    final List<Map<String, Object>> hits = service.hybridSearch(request(), PIPELINE);

    assertThat(hits)
        .extracting(hit -> hit.get("parentId"), hit -> hit.get("entityType"))
        .containsExactly(
            tuple("t1", "table"), tuple("c1", "tableColumn"), tuple("g1", "glossaryTerm"));
  }

  @Test
  void aClusterThatCannotRunTheQueryIsAHybridSearchFailure() throws IOException {
    clusterAnswers(400, "{\"error\":{\"type\":\"illegal_argument_exception\"}}");

    assertThatThrownBy(() -> service.hybridSearch(request(), PIPELINE))
        .isInstanceOf(HybridSearchException.class);
  }
}
