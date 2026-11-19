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
package org.openmetadata.it.tests.mcp.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openmetadata.it.tests.mcp.McpTestBase;
import org.openmetadata.it.tests.mcp.McpTestUtils;

/**
 * The citation card is an MCP Apps view: {@code finish_answer} names it in its tool metadata, and
 * a host reads it as a {@code ui://} resource over the same endpoint.
 */
@Execution(ExecutionMode.CONCURRENT)
class McpAppsResourceIT extends McpTestBase {
  private static final String CARD_URI = "ui://openmetadata/answer-citation-v1.html";
  private static final String ASSET_URI = "ui://openmetadata/entity-v1.html";
  private static final String MIME_TYPE = "text/html;profile=mcp-app";

  @BeforeAll
  static void setup() throws Exception {
    initAuth();
  }

  @Test
  void initializeAdvertisesResources() throws Exception {
    final JsonNode result =
        executeMcpRequest(McpTestUtils.createInitializeRequest()).path("result");

    assertThat(result.path("capabilities").has("resources")).isTrue();
  }

  @Test
  void resourcesListNamesTheCardWithTheMcpAppsMimeType() throws Exception {
    final JsonNode resources =
        executeMcpRequest(McpTestUtils.createJsonRpcRequest("resources/list", Map.of()))
            .path("result")
            .path("resources");

    final Map<String, String> mimeTypes = new java.util.HashMap<>();
    for (final JsonNode resource : resources) {
      mimeTypes.put(resource.path("uri").asText(), resource.path("mimeType").asText());
    }
    assertThat(mimeTypes).containsEntry(CARD_URI, MIME_TYPE).containsEntry(ASSET_URI, MIME_TYPE);
  }

  @Test
  void resourcesReadReturnsTheCardHtmlAsTheCaller() throws Exception {
    final JsonNode result =
        executeMcpRequest(
                McpTestUtils.createJsonRpcRequest("resources/read", Map.of("uri", CARD_URI)))
            .path("result");

    final JsonNode contents = result.path("contents").get(0);
    assertThat(contents.path("uri").asText()).isEqualTo(CARD_URI);
    assertThat(contents.path("mimeType").asText()).isEqualTo(MIME_TYPE);
    assertThat(contents.path("text").asText()).startsWith("<!doctype html>");
    assertThat(contents.path("_meta").path("ui").path("prefersBorder").asBoolean(true)).isFalse();
  }

  @Test
  void toolsListCarriesTheCardsOnFinishAnswerAndEntityDetailsOnly() throws Exception {
    final JsonNode tools =
        executeMcpRequest(McpTestUtils.createJsonRpcRequest("tools/list", Map.of()))
            .path("result")
            .path("tools");

    final Map<String, String> withView = new java.util.TreeMap<>();
    for (final JsonNode tool : tools) {
      final String uri = tool.path("_meta").path("ui").path("resourceUri").asText("");
      if (!uri.isEmpty()) {
        withView.put(tool.path("name").asText(), uri);
      }
    }
    assertThat(withView)
        .isEqualTo(Map.of("finish_answer", CARD_URI, "get_entity_details", ASSET_URI));
  }
}
