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
package org.openmetadata.service.aicontext.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.openmetadata.schema.utils.JsonUtils;

/**
 * The plugin that connects an agent host, Claude Code or Codex, to OpenMetadata. One zip installs
 * in both: each host reads its own manifest and ignores the other's. It carries no harness
 * content, so
 * one package serves every employee and publishing never requires a re-upload: the skill, the MCP
 * connector and the hooks only point the host at the server, which compiles the harness per call.
 * The zip is a pure function of the server URL and version, with sorted entries and fixed
 * timestamps.
 */
public final class PluginBuilder {
  public static final String PLUGIN_NAME = "openmetadata";
  static final String SERVER_KEY = "openmetadata";

  /** How Claude Code names a hook's target when the MCP server ships inside a plugin. */
  static final String HOOK_SERVER = "plugin:" + PLUGIN_NAME + ":" + SERVER_KEY;

  static final String HOOK_TOOL = "harness_hook";
  static final String MANIFEST_PATH = ".claude-plugin/plugin.json";
  static final String CODEX_MANIFEST_PATH = ".codex-plugin/plugin.json";
  static final String MCP_PATH = ".mcp.json";
  static final String SKILL_PATH = "skills/openmetadata/SKILL.md";
  static final String HOOKS_PATH = "hooks/hooks.json";

  /**
   * Claude Code skips an {@code mcp_tool} SessionStart hook at launch, because the plugin's server
   * is not connected yet, so SessionStart is left to the skill and {@code get_context}.
   */
  private static final List<String> HOOK_EVENTS =
      List.of("UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop");

  private static final String MATCH_ALL_TOOLS = ".*";
  private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

  private PluginBuilder() {}

  public static byte[] build(final String serverUrl, final String version) {
    final Map<String, String> files = new TreeMap<>();
    files.put(MANIFEST_PATH, manifest(version));
    files.put(CODEX_MANIFEST_PATH, codexManifest(version));
    files.put(MCP_PATH, mcpConfig(serverUrl));
    files.put(SKILL_PATH, skill());
    files.put(HOOKS_PATH, json(hooks(HOOK_SERVER)));
    return zip(files);
  }

  private static String manifest(final String version) {
    return json(baseManifest(version));
  }

  /**
   * Codex names a plugin's MCP server by its key, where Claude Code prefixes it with the plugin,
   * so the shared {@code hooks/hooks.json} cannot serve both. The manifest's inline hooks replace
   * Codex's default hooks file, and Claude Code never reads this manifest.
   */
  private static String codexManifest(final String version) {
    final ObjectNode manifest = baseManifest(version);
    manifest.put("skills", "./skills/");
    manifest.put("mcpServers", "./" + MCP_PATH);
    manifest.set("hooks", hooks(SERVER_KEY));
    return json(manifest);
  }

  private static ObjectNode baseManifest(final String version) {
    final ObjectNode manifest = JsonUtils.getObjectMapper().createObjectNode();
    manifest.put("name", PLUGIN_NAME);
    manifest.put("displayName", "OpenMetadata");
    manifest.put("version", version);
    manifest.put(
        "description",
        "Gives your AI agent your company's data context from OpenMetadata: what terms, metrics "
            + "and tables mean, which to trust, and the rules for using them.");
    return manifest;
  }

  private static String mcpConfig(final String serverUrl) {
    final ObjectNode server = JsonUtils.getObjectMapper().createObjectNode();
    server.put("type", "http");
    server.put("url", serverUrl);
    final ObjectNode root = JsonUtils.getObjectMapper().createObjectNode();
    root.putObject("mcpServers").set(SERVER_KEY, server);
    return json(root);
  }

  private static ObjectNode hooks(final String server) {
    final ObjectNode root = JsonUtils.getObjectMapper().createObjectNode();
    final ObjectNode events = root.putObject("hooks");
    HOOK_EVENTS.forEach(event -> events.putArray(event).add(hookGroup(event, server)));
    return root;
  }

  private static ObjectNode hookGroup(final String event, final String server) {
    final ObjectNode input = JsonUtils.getObjectMapper().createObjectNode();
    input.put("hook_event_name", "${hook_event_name}");
    input.put("session_id", "${session_id}");
    input.put("cwd", "${cwd}");
    switch (event) {
      case "UserPromptSubmit" -> input.put("prompt", "${prompt}");
      case "PreToolUse" -> toolInput(input);
      case "PostToolUse" -> {
        toolInput(input);
        input.put("tool_response", "${tool_response}");
      }
      default -> {}
    }
    final ObjectNode handler = JsonUtils.getObjectMapper().createObjectNode();
    handler.put("type", "mcp_tool");
    handler.put("server", server);
    handler.put("tool", HOOK_TOOL);
    handler.set("input", input);
    final ObjectNode group = JsonUtils.getObjectMapper().createObjectNode();
    if (event.endsWith("ToolUse")) {
      group.put("matcher", MATCH_ALL_TOOLS);
    }
    group.putArray("hooks").add(handler);
    return group;
  }

  private static void toolInput(final ObjectNode input) {
    input.put("tool_name", "${tool_name}");
    input.put("tool_input", "${tool_input}");
  }

  private static String skill() {
    return """
        ---
        name: openmetadata
        description: Use for any question about company data, business terms, metrics, tables, columns or data quality. Looks the answer up in OpenMetadata before answering.
        ---

        # OpenMetadata

        OpenMetadata is this company's source of truth for what its data means and which data to trust.

        1. For a question about company data, terms, metrics or tables, call the `get_context` tool
           first, then search with the OpenMetadata tools for what it points to.
        2. Cite what OpenMetadata returns: the term, metric or table name and its owner. Do not
           answer from memory when OpenMetadata has the answer.
        3. Follow guardrail decisions. When a tool call is denied or warned, tell the user why and
           use the alternative OpenMetadata names.
        4. Treat deprecated assets and assets with open incidents as untrusted unless the user asks
           for them.
        """;
  }

  private static String json(final JsonNode node) {
    return JsonUtils.pojoToJson(node, true) + "\n";
  }

  private static byte[] zip(final Map<String, String> files) {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytes)) {
      for (final Map.Entry<String, String> file : files.entrySet()) {
        final ZipEntry entry = new ZipEntry(file.getKey());
        entry.setTimeLocal(FIXED_TIME);
        out.putNextEntry(entry);
        out.write(file.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
    return bytes.toByteArray();
  }
}
