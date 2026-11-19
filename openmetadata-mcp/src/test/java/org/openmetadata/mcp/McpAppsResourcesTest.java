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
import org.openmetadata.schema.api.configuration.MCPConfiguration;

class McpAppsResourcesTest {
  private static final String CARD_URI = "ui://openmetadata/answer-citation-v1.html";

  @Test
  void theCitationCardIsListedWithTheMcpAppsMimeType() {
    final McpStatelessSyncServer server = serverWithResources();

    final List<McpSchema.Resource> resources = server.listResources();

    assertThat(resources).hasSize(1);
    assertThat(resources.get(0).uri()).isEqualTo(CARD_URI);
    assertThat(resources.get(0).mimeType()).isEqualTo("text/html;profile=mcp-app");
    assertThat(resources.get(0).meta()).containsKey("ui");
  }

  @Test
  void theCitationCardIsReadAsHtmlWithItsUiMeta() {
    final McpSchema.ReadResourceResult result =
        McpAppsResources.specifications()
            .get(0)
            .readHandler()
            .apply(McpTransportContext.EMPTY, new McpSchema.ReadResourceRequest(CARD_URI));

    assertThat(result.contents()).hasSize(1);
    final McpSchema.TextResourceContents card =
        (McpSchema.TextResourceContents) result.contents().get(0);
    assertThat(card.uri()).isEqualTo(CARD_URI);
    assertThat(card.mimeType()).isEqualTo("text/html;profile=mcp-app");
    assertThat(card.text()).startsWith("<!doctype html>").contains("ui/initialize");
    assertThat(card.meta()).isEqualTo(Map.of("ui", Map.of("prefersBorder", true, "csp", Map.of())));
  }

  @Test
  void onlyFinishAnswerPointsAtTheCard() {
    final List<McpSchema.Tool> tools = new McpServer().getTools();

    final List<String> withView =
        tools.stream()
            .filter(tool -> tool.meta() != null && tool.meta().containsKey("ui"))
            .map(McpSchema.Tool::name)
            .toList();
    assertThat(withView).containsExactly("finish_answer");
    final McpSchema.Tool finish =
        tools.stream()
            .filter(tool -> "finish_answer".equals(tool.name()))
            .findFirst()
            .orElseThrow();
    assertThat(finish.meta())
        .isEqualTo(Map.of("ui", Map.of("resourceUri", McpAppsResources.ANSWER_CITATION_URI)));
    assertThat(McpAppsResources.ANSWER_CITATION_URI).isEqualTo(CARD_URI);
  }

  @Test
  void theCardSpeaksTheHostProtocolAndWritesCatalogTextAsText() throws Exception {
    final String card = cardHtml();

    assertThat(card)
        .contains("ui/initialize", "ui/notifications/initialized")
        .contains("ui/notifications/tool-result", "ui/notifications/host-context-changed")
        .contains("ui/open-link", "ui/notifications/size-changed")
        .contains("structuredContent", "prefers-color-scheme");
    // Names and descriptions in a citation are written by anyone with edit rights, so none may
    // reach the page as markup.
    assertThat(card).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "eval(");
  }

  @Test
  void theCardLoadsNothingAndNeverNavigates() throws Exception {
    final String card = cardHtml();

    assertThat(card)
        .doesNotContain("fetch(", "XMLHttpRequest", "WebSocket", "window.open", "location.href")
        .doesNotContainPattern("<script[^>]*\\ssrc=")
        .doesNotContainPattern("<link[^>]*\\shref=")
        .doesNotContainPattern("@import")
        .doesNotContainPattern("url\\(");
  }

  private static String cardHtml() throws Exception {
    final McpSchema.ReadResourceResult result =
        McpAppsResources.specifications()
            .get(0)
            .readHandler()
            .apply(McpTransportContext.EMPTY, new McpSchema.ReadResourceRequest(CARD_URI));
    return ((McpSchema.TextResourceContents) result.contents().get(0)).text();
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
