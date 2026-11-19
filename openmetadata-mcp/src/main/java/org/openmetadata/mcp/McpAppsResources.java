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

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The MCP Apps views this server offers, as {@code ui://} resources (MCP Apps extension, version
 * 2026-01-26). A host that renders them shows the view from a tool's {@code structuredContent};
 * every other host ignores the resource and shows the tool's text content.
 */
public final class McpAppsResources {
  public static final String MIME_TYPE = "text/html;profile=mcp-app";

  /** Versioned in the URI because hosts cache a template by its URI. */
  public static final String ANSWER_CITATION_URI = "ui://openmetadata/answer-citation-v1.html";

  private static final String ANSWER_CITATION_FILE = "mcp-apps/answer-citation-v1.html";
  // No external origins: the view loads nothing, so the host's default CSP applies in full.
  private static final Map<String, Object> UI_META =
      Map.of("ui", Map.of("prefersBorder", true, "csp", Map.of()));

  private McpAppsResources() {}

  public static List<McpStatelessServerFeatures.SyncResourceSpecification> specifications() {
    return List.of(answerCitation());
  }

  /** Fails at startup when the view is missing from the jar, rather than on a host's first read. */
  static McpStatelessServerFeatures.SyncResourceSpecification answerCitation() {
    final String html = load(ANSWER_CITATION_FILE);
    final McpSchema.Resource resource =
        McpSchema.Resource.builder()
            .uri(ANSWER_CITATION_URI)
            .name("answer-citation")
            .title("How I got this")
            .description("Citation card for a finish_answer result")
            .mimeType(MIME_TYPE)
            .meta(UI_META)
            .build();
    return new McpStatelessServerFeatures.SyncResourceSpecification(
        resource,
        (context, request) ->
            new McpSchema.ReadResourceResult(
                List.of(
                    new McpSchema.TextResourceContents(
                        ANSWER_CITATION_URI, MIME_TYPE, html, UI_META))));
  }

  private static String load(final String path) {
    try (InputStream stream = McpAppsResources.class.getClassLoader().getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalStateException("MCP Apps view missing from the classpath: " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot read MCP Apps view " + path, exception);
    }
  }
}
