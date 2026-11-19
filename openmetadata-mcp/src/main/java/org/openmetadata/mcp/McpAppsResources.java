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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The MCP Apps views this server offers, as {@code ui://} resources (MCP Apps extension, version
 * 2026-01-26). A host that renders them shows the view from a tool's {@code structuredContent};
 * every other host ignores the resource and shows the tool's text content.
 *
 * <p>Each view is a small page under {@code mcp-apps/} that links the shared card shell. The page
 * is served as one file: its stylesheet and script links are replaced by their contents when the
 * server starts, so a view makes no request of its own and the shell has one source.
 */
public final class McpAppsResources {
  public static final String MIME_TYPE = "text/html;profile=mcp-app";

  /** Versioned in the URI because hosts cache a template by its URI. */
  public static final String ANSWER_CITATION_URI = "ui://openmetadata/answer-citation-v1.html";

  public static final String DATA_ASSET_URI = "ui://openmetadata/data-asset-v1.html";

  private static final String VIEWS_DIR = "mcp-apps/";
  private static final Pattern STYLESHEET =
      Pattern.compile("<link rel=\"stylesheet\" href=\"([^\"]+)\">");
  private static final Pattern SCRIPT = Pattern.compile("<script src=\"([^\"]+)\"></script>");
  // The card draws its own border. No external origins: the views load nothing, so the host's
  // default CSP applies in full.
  private static final Map<String, Object> UI_META =
      Map.of("ui", Map.of("prefersBorder", false, "csp", Map.of()));

  private McpAppsResources() {}

  public static List<McpStatelessServerFeatures.SyncResourceSpecification> specifications() {
    return List.of(
        view(
            ANSWER_CITATION_URI,
            "answer-citation",
            "How I got this",
            "Citation card for a finish_answer result",
            "answer-citation-v1.html"),
        view(
            DATA_ASSET_URI,
            "data-asset",
            "Data asset",
            "Card for an entity returned by get_entity_details",
            "data-asset-v1.html"));
  }

  /** Fails at startup when a view or a file it links is missing, not on a host's first read. */
  private static McpStatelessServerFeatures.SyncResourceSpecification view(
      final String uri,
      final String name,
      final String title,
      final String description,
      final String file) {
    final String html = inline(load(VIEWS_DIR + file));
    final McpSchema.Resource resource =
        McpSchema.Resource.builder()
            .uri(uri)
            .name(name)
            .title(title)
            .description(description)
            .mimeType(MIME_TYPE)
            .meta(UI_META)
            .build();
    return new McpStatelessServerFeatures.SyncResourceSpecification(
        resource,
        (context, request) ->
            new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(uri, MIME_TYPE, html, UI_META))));
  }

  static String inline(final String page) {
    final String styled =
        replace(page, STYLESHEET, file -> "<style>\n" + load(VIEWS_DIR + file) + "</style>");
    return replace(styled, SCRIPT, file -> "<script>\n" + script(file) + "</script>");
  }

  private static String script(final String file) {
    final String source = load(VIEWS_DIR + file);
    if (source.toLowerCase().contains("</script")) {
      throw new IllegalStateException("MCP Apps script contains a closing script tag: " + file);
    }
    return source;
  }

  private static String replace(
      final String page,
      final Pattern pattern,
      final java.util.function.UnaryOperator<String> contents) {
    final Matcher matcher = pattern.matcher(page);
    final StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(result, Matcher.quoteReplacement(contents.apply(matcher.group(1))));
    }
    return matcher.appendTail(result).toString();
  }

  private static String load(final String path) {
    try (InputStream stream = McpAppsResources.class.getClassLoader().getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalStateException("MCP Apps file missing from the classpath: " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot read MCP Apps file " + path, exception);
    }
  }
}
