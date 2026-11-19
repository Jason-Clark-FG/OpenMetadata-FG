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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openmetadata.schema.api.configuration.MCPConfiguration;

class McpAppsResourcesTest {
  private static final String CITATION_URI = "ui://openmetadata/answer-citation-v1.html";
  private static final String ASSET_URI = "ui://openmetadata/entity-v1.html";
  private static final String LINEAGE_URI = "ui://openmetadata/lineage-v1.html";
  private static final String ONTOLOGY_URI = "ui://openmetadata/ontology-v1.html";
  private static final String MEMORY_URI = "ui://openmetadata/memory-v1.html";
  private static final String MIME_TYPE = "text/html;profile=mcp-app";
  private static final String BASE_URL = "https://om.example.com:8585/ctx/";
  private static final String ORIGIN = "https://om.example.com:8585";

  @Test
  void everyCardIsListedWithTheMcpAppsMimeType() {
    final List<McpSchema.Resource> resources = serverWithResources().listResources();

    assertThat(resources)
        .extracting(McpSchema.Resource::uri)
        .containsExactlyInAnyOrder(CITATION_URI, ASSET_URI, LINEAGE_URI, ONTOLOGY_URI, MEMORY_URI);
    assertThat(resources)
        .allSatisfy(
            resource -> {
              assertThat(resource.mimeType()).isEqualTo(MIME_TYPE);
              assertThat(resource.meta()).containsKey("ui");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {CITATION_URI, ASSET_URI, LINEAGE_URI, ONTOLOGY_URI, MEMORY_URI})
  void aCardIsReadAsOneSelfContainedHtmlFile(final String uri) {
    final McpSchema.TextResourceContents card = read(uri);

    assertThat(card.uri()).isEqualTo(uri);
    assertThat(card.mimeType()).isEqualTo(MIME_TYPE);
    assertThat(card.meta())
        .isEqualTo(
            Map.of(
                "ui",
                Map.of("prefersBorder", false, "csp", Map.of("resourceDomains", List.of(ORIGIN)))));
    assertThat(card.text())
        .startsWith("<!doctype html>")
        .contains("--card-bg:", ".om-card")
        .contains("ui/initialize", "ui/notifications/initialized")
        .contains("ui/notifications/tool-result", "ui/notifications/host-context-changed")
        .contains("ui/open-link", "ui/notifications/size-changed")
        .contains("structuredContent", "prefers-color-scheme");
  }

  @ParameterizedTest
  @ValueSource(strings = {CITATION_URI, ASSET_URI, LINEAGE_URI, ONTOLOGY_URI, MEMORY_URI})
  void aCardWritesCatalogTextAsTextAndLoadsOnlyItsFonts(final String uri) {
    final String card = read(uri).text();

    // Names and descriptions are written by anyone with edit rights, so none may reach the page as
    // markup.
    assertThat(card).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "eval(");
    assertThat(card)
        .doesNotContain("fetch(", "XMLHttpRequest", "WebSocket", "window.open", "location.href")
        .doesNotContainPattern("<script[^>]*\\ssrc=")
        .doesNotContainPattern("<link[^>]*\\shref=")
        .doesNotContainPattern("@import")
        .doesNotContain("data:", "__FONT_BASE__");
    final Matcher urls = Pattern.compile("url\\(\"([^\"]*)\"\\)").matcher(card);
    int count = 0;
    while (urls.find()) {
      count++;
      assertThat(urls.group(1)).startsWith(ORIGIN + "/ctx/mcp-assets/fonts/").endsWith(".woff2");
    }
    assertThat(count).isEqualTo(4);
    assertThat(card).doesNotContainPattern("url\\([^\"]");
  }

  @Test
  void withoutAUsableBaseUrlTheCardKeepsTheSystemFontsAndDeclaresNoOrigin() {
    for (final String baseUrl : new String[] {null, "", "  ", "not a url", "ftp://host/x"}) {
      final McpSchema.TextResourceContents card = read(CITATION_URI, baseUrl);

      assertThat(card.meta())
          .isEqualTo(Map.of("ui", Map.of("prefersBorder", false, "csp", Map.of())));
      assertThat(card.text()).doesNotContain("@font-face", "__FONT_BASE__", "url(");
      assertThat(card.text()).contains("--card-font: Inter, system-ui");
    }
  }

  @Test
  void theOriginKeepsTheSchemeHostAndPortOnly() {
    assertThat(McpAppsResources.originOf("http://localhost:8585"))
        .isEqualTo("http://localhost:8585");
    assertThat(McpAppsResources.originOf("https://acme.getcollate.io/om/"))
        .isEqualTo("https://acme.getcollate.io");
    assertThat(McpAppsResources.originOf("javascript:alert(1)")).isNull();
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
  void eachCardToolPointsAtItsCard() {
    final List<McpSchema.Tool> tools = new McpServer().getTools();

    final Map<String, Object> withView = new java.util.TreeMap<>();
    tools.stream()
        .filter(tool -> tool.meta() != null && tool.meta().containsKey("ui"))
        .forEach(tool -> withView.put(tool.name(), tool.meta()));
    assertThat(withView)
        .containsOnlyKeys(
            "finish_answer",
            "get_entity_details",
            "find_context",
            "get_concept_context",
            "get_concept_relations",
            "get_entity_lineage",
            "remember")
        .containsEntry("finish_answer", Map.of("ui", Map.of("resourceUri", CITATION_URI)))
        .containsEntry("get_entity_details", Map.of("ui", Map.of("resourceUri", ASSET_URI)))
        .containsEntry("find_context", Map.of("ui", Map.of("resourceUri", ASSET_URI)))
        .containsEntry("get_concept_context", Map.of("ui", Map.of("resourceUri", ASSET_URI)))
        .containsEntry("get_concept_relations", Map.of("ui", Map.of("resourceUri", ONTOLOGY_URI)))
        .containsEntry("get_entity_lineage", Map.of("ui", Map.of("resourceUri", LINEAGE_URI)))
        .containsEntry("remember", Map.of("ui", Map.of("resourceUri", MEMORY_URI)));
    assertThat(McpAppsResources.ANSWER_CITATION_URI).isEqualTo(CITATION_URI);
    assertThat(McpAppsResources.ENTITY_URI).isEqualTo(ASSET_URI);
    assertThat(McpAppsResources.LINEAGE_URI).isEqualTo(LINEAGE_URI);
    assertThat(McpAppsResources.ONTOLOGY_URI).isEqualTo(ONTOLOGY_URI);
    assertThat(McpAppsResources.MEMORY_URI).isEqualTo(MEMORY_URI);
  }

  @Test
  void theLineageAndOntologyCardsAreTheirOwnPages() {
    final String lineage = read(LINEAGE_URI).text();
    final String ontology = read(ONTOLOGY_URI).text();

    assertThat(lineage)
        .contains("Upstream", "This asset", "Downstream", "Column lineage")
        .doesNotContain("Join path");
    assertThat(ontology).contains("Join path", "om-relation").doesNotContain("Column lineage");
  }

  @Test
  void theMemoryCardIsItsOwnPage() {
    final String memory = read(MEMORY_URI).text();

    assertThat(memory)
        .contains("Linked to", "Visible to", "Capture off", "Merged into an existing memory")
        .doesNotContain("Join path", "Column lineage", "Key columns");
  }

  private static McpSchema.TextResourceContents read(final String uri) {
    return read(uri, BASE_URL);
  }

  private static McpSchema.TextResourceContents read(final String uri, final String baseUrl) {
    final McpSchema.ReadResourceResult result =
        McpAppsResources.specifications(baseUrl).stream()
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
    McpAppsResources.specifications(BASE_URL).forEach(server::addResource);
    return server;
  }
}
