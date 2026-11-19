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

package org.openmetadata.it.tests.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openmetadata.it.util.RdfTestUtils;

/**
 * A server started with {@code mcpOfferedTools} set to knowledge_graph ({@code
 * -DmcpOfferedTools=knowledge_graph} for the suite's server) offers that tool alone, tells a client
 * on initialize how to use it, and refuses any other tool as not offered.
 */
public class RdfMcpOfferedToolsIT extends McpTestBase {

  private static final String KNOWLEDGE_GRAPH = "knowledge_graph";

  @BeforeAll
  static void setUp() throws Exception {
    assumeTrue(RdfTestUtils.isRdfEnabled(), "Runs in the RDF lane, where the store is on");
    assumeTrue(
        KNOWLEDGE_GRAPH.equals(System.getProperty("mcpOfferedTools")),
        "Runs when the suite's server is started with -DmcpOfferedTools=knowledge_graph");
    initAuth();
  }

  @Test
  void onlyTheKnowledgeGraphToolIsOfferedAndAnyOtherIsRefusedAsNotOffered() throws Exception {
    final JsonNode initialized =
        executeMcpRequest(McpTestUtils.createInitializeRequest()).path("result");
    final JsonNode listed =
        executeMcpRequest(McpTestUtils.createJsonRpcRequest("tools/list", Map.of()))
            .path("result")
            .path("tools");
    final JsonNode refused =
        executeMcpRequest(
                McpTestUtils.createToolCallRequest(
                    "search_metadata", Map.of("query", "orders", "size", 1)))
            .path("result");

    assertThat(initialized.path("instructions").asText()).contains(KNOWLEDGE_GRAPH, "cite");
    assertThat(StreamSupport.stream(listed.spliterator(), false))
        .extracting(tool -> tool.path("name").asText())
        .containsExactly(KNOWLEDGE_GRAPH);
    assertThat(refused.path("isError").asBoolean()).isTrue();
    assertThat(refused.path("content").path(0).path("text").asText())
        .contains("search_metadata is not offered by this MCP server")
        .doesNotContain("403");
  }
}
