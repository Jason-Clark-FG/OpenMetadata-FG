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
package org.openmetadata.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openmetadata.schema.api.configuration.MCPConfiguration;

class McpAppsResourcesTest {
  private static final String CITATION_URI = "ui://openmetadata/answer-citation-v1.html";
  private static final String ASSET_URI = "ui://openmetadata/data-asset-v1.html";
  private static final String MIME_TYPE = "text/html;profile=mcp-app";

  @Test
  void bothCardsAreListedWithTheMcpAppsMimeType() {
    final List<McpSchema.Resource> resources = serverWithResources().listResources();

    assertThat(resources)
        .extracting(McpSchema.Resource::uri)
        .containsExactlyInAnyOrder(CITATION_URI, ASSET_URI);
    assertThat(resources)
        .allSatisfy(
            resource -> {
              assertThat(resource.mimeType()).isEqualTo(MIME_TYPE);
              assertThat(resource.meta()).containsKey("ui");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {CITATION_URI, ASSET_URI})
  void aCardIsReadAsOneSelfContainedHtmlFile(final String uri) {
    final McpSchema.TextResourceContents card = read(uri);

    assertThat(card.uri()).isEqualTo(uri);
    assertThat(card.mimeType()).isEqualTo(MIME_TYPE);
    assertThat(card.meta())
        .isEqualTo(Map.of("ui", Map.of("prefersBorder", false, "csp", Map.of())));
    assertThat(card.text())
        .startsWith("<!doctype html>")
        .contains("--card-bg:", ".om-card")
        .contains("ui/initialize", "ui/notifications/initialized")
        .contains("ui/notifications/tool-result", "ui/notifications/host-context-changed")
        .contains("ui/open-link", "ui/notifications/size-changed")
        .contains("structuredContent", "prefers-color-scheme");
  }

  @ParameterizedTest
  @ValueSource(strings = {CITATION_URI, ASSET_URI})
  void aCardWritesCatalogTextAsTextAndLoadsNothing(final String uri) {
    final String card = read(uri).text();

    // Names and descriptions are written by anyone with edit rights, so none may reach the page as
    // markup.
    assertThat(card).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "eval(");
    assertThat(card)
        .doesNotContain("fetch(", "XMLHttpRequest", "WebSocket", "window.open", "location.href")
        .doesNotContainPattern("<script[^>]*\\ssrc=")
        .doesNotContainPattern("<link[^>]*\\shref=")
        .doesNotContainPattern("@import")
        .doesNotContainPattern("url\\(")
        .doesNotContainPattern("@font-face");
  }

  @Test
  void theCardsShareOneShell() {
    final String citation = read(CITATION_URI).text();
    final String asset = read(ASSET_URI).text();

    assertThat(citation).contains("var OM = (function");
    assertThat(asset).contains("var OM = (function");
    assertThat(citation).contains("How I got this").doesNotContain("Key columns");
    assertThat(asset).contains("Key columns").doesNotContain("Answer");
  }

  @Test
  void finishAnswerAndEntityDetailsPointAtTheirCards() {
    final List<McpSchema.Tool> tools = new McpServer().getTools();

    final Map<String, Object> withView = new java.util.TreeMap<>();
    tools.stream()
        .filter(tool -> tool.meta() != null && tool.meta().containsKey("ui"))
        .forEach(tool -> withView.put(tool.name(), tool.meta()));
    assertThat(withView)
        .containsOnlyKeys("finish_answer", "get_entity_details")
        .containsEntry("finish_answer", Map.of("ui", Map.of("resourceUri", CITATION_URI)))
        .containsEntry("get_entity_details", Map.of("ui", Map.of("resourceUri", ASSET_URI)));
    assertThat(McpAppsResources.ANSWER_CITATION_URI).isEqualTo(CITATION_URI);
    assertThat(McpAppsResources.DATA_ASSET_URI).isEqualTo(ASSET_URI);
  }

  private static McpSchema.TextResourceContents read(final String uri) {
    final McpSchema.ReadResourceResult result =
        McpAppsResources.specifications().stream()
            .filter(specification -> uri.equals(specification.resource().uri()))
            .findFirst()
            .orElseThrow()
            .readHandler()
            .apply(McpTransportContext.EMPTY, new McpSchema.ReadResourceRequest(uri));
    assertThat(result.contents()).hasSize(1);
    return (McpSchema.TextResourceContents) result.contents().get(0);
  }

  private static McpStatelessSyncServer serverWithResources() {
    final McpStatelessSyncServer server =
        McpServer.buildServer(
            mock(McpStatelessServerTransport.class),
            new McpServer().buildServerCapabilities(),
            MCPConfiguration::new);
    McpAppsResources.specifications().forEach(server::addResource);
    return server;
  }
}
