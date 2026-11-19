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
import java.net.URI;
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

  public static final String ENTITY_URI = "ui://openmetadata/entity-v1.html";

  public static final String LINEAGE_URI = "ui://openmetadata/lineage-v1.html";

  public static final String ONTOLOGY_URI = "ui://openmetadata/ontology-v1.html";

  public static final String MEMORY_URI = "ui://openmetadata/memory-v1.html";

  public static final String KNOWLEDGE_GRAPH_URI = "ui://openmetadata/knowledge-graph-v1.html";

  private static final String VIEWS_DIR = "mcp-apps/";
  private static final Pattern STYLESHEET =
      Pattern.compile("<link rel=\"stylesheet\" href=\"([^\"]+)\">");
  private static final Pattern SCRIPT = Pattern.compile("<script src=\"([^\"]+)\"></script>");

  /** Where {@code McpCardFontsFilter} serves the card fonts, relative to the server's base URL. */
  public static final String FONT_PATH = "/mcp-assets/fonts";

  private static final String FONT_BASE_PLACEHOLDER = "__FONT_BASE__";
  private static final Pattern FONT_FACE = Pattern.compile("@font-face\\s*\\{[^}]*\\}");

  private McpAppsResources() {}

  /**
   * @param baseUrl the server's public base URL, with any context path. The card loads its fonts
   *     from there, and the resource's {@code _meta.ui.csp} lists its origin: a host's default CSP
   *     allows no fonts, not even {@code data:} ones. Without a usable base URL the card keeps the
   *     system fonts.
   */
  public static List<McpStatelessServerFeatures.SyncResourceSpecification> specifications(
      final String baseUrl) {
    final String origin = originOf(baseUrl);
    final Map<String, Object> meta = meta(origin);
    final String fontBase =
        origin == null ? null : baseUrl.trim().replaceAll("/+$", "") + FONT_PATH;
    return List.of(
        view(
            ANSWER_CITATION_URI,
            "answer-citation",
            "How I got this",
            "Citation card for a finish_answer result",
            "answer-citation-v1.html",
            fontBase,
            meta),
        view(
            ENTITY_URI,
            "entity",
            "Entity",
            "Card for an entity returned by get_entity_details: a data asset, metric or glossary term,"
                + " chosen by the entity type in the result",
            "entity-v1.html",
            fontBase,
            meta),
        view(
            LINEAGE_URI,
            "lineage",
            "Lineage",
            "Card for the lineage returned by get_entity_lineage: what feeds an asset, what depends on"
                + " it and which upstream checks fail",
            "lineage-v1.html",
            fontBase,
            meta),
        view(
            ONTOLOGY_URI,
            "ontology",
            "Concept relations",
            "Card for get_concept_relations: the terms related to a glossary term, the table for each"
                + " and the join path to a target term",
            "ontology-v1.html",
            fontBase,
            meta),
        view(
            MEMORY_URI,
            "memory",
            "Memory",
            "Card for the memory saved by remember: the fact, the assets it is linked to, who can"
                + " read it and whether it needs review",
            "memory-v1.html",
            fontBase,
            meta),
        view(
            KNOWLEDGE_GRAPH_URI,
            "knowledge-graph",
            "Knowledge graph",
            "Card for knowledge_graph: what a call served, and for cite and trace the run's graph with"
                + " what it cited, explored and was offered",
            "knowledge-graph-v1.html",
            fontBase,
            meta));
  }

  /** The card draws its own border; {@code resourceDomains} is the MCP Apps allowance for fonts. */
  static Map<String, Object> meta(final String origin) {
    final Map<String, Object> csp =
        origin == null ? Map.of() : Map.of("resourceDomains", List.of(origin));
    return Map.of("ui", Map.of("prefersBorder", false, "csp", csp));
  }

  /** {@code scheme://host[:port]} of an http(s) URL, or null when there is none. */
  static String originOf(final String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return null;
    }
    try {
      final URI uri = URI.create(baseUrl.trim());
      final boolean web = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
      if (!web || uri.getHost() == null) {
        return null;
      }
      return uri.getScheme()
          + "://"
          + uri.getHost()
          + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  /** Fails at startup when a view or a file it links is missing, not on a host's first read. */
  private static McpStatelessServerFeatures.SyncResourceSpecification view(
      final String uri,
      final String name,
      final String title,
      final String description,
      final String file,
      final String fontBase,
      final Map<String, Object> meta) {
    final String html = withFonts(inline(load(VIEWS_DIR + file)), fontBase);
    final McpSchema.Resource resource =
        McpSchema.Resource.builder()
            .uri(uri)
            .name(name)
            .title(title)
            .description(description)
            .mimeType(MIME_TYPE)
            .meta(meta)
            .build();
    return new McpStatelessServerFeatures.SyncResourceSpecification(
        resource,
        (context, request) ->
            new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(uri, MIME_TYPE, html, meta))));
  }

  /** Without a font base the rules go, so the card requests no URL it cannot reach. */
  static String withFonts(final String page, final String fontBase) {
    return fontBase == null
        ? FONT_FACE.matcher(page).replaceAll("")
        : page.replace(FONT_BASE_PLACEHOLDER, fontBase);
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
