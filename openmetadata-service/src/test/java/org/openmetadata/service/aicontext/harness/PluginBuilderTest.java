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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.utils.JsonUtils;

class PluginBuilderTest {
  private static final String URL = "https://om.example.com/mcp";

  @Test
  void zipHoldsExactlyTheManifestsConnectorSkillAndHooks() throws IOException {
    final Map<String, String> files = unzip(PluginBuilder.build(URL, "2.1.0"));

    assertThat(files.keySet())
        .containsExactly(
            ".claude-plugin/plugin.json",
            ".codex-plugin/plugin.json",
            ".mcp.json",
            "hooks/hooks.json",
            "skills/openmetadata/SKILL.md");
  }

  @Test
  void manifestNamesThePluginAtTheServerVersion() throws IOException {
    final JsonNode manifest =
        json(unzip(PluginBuilder.build(URL, "2.1.0")), ".claude-plugin/plugin.json");

    assertThat(manifest.path("name").asText()).isEqualTo("openmetadata");
    assertThat(manifest.path("version").asText()).isEqualTo("2.1.0");
  }

  @Test
  void connectorIsAnHttpServerAtTheMcpUrl() throws IOException {
    final JsonNode server =
        json(unzip(PluginBuilder.build(URL, "2.1.0")), ".mcp.json")
            .path("mcpServers")
            .path("openmetadata");

    assertThat(server.path("type").asText()).isEqualTo("http");
    assertThat(server.path("url").asText()).isEqualTo(URL);
  }

  @Test
  void skillTellsTheModelToCallGetContextAndCite() throws IOException {
    final String skill =
        unzip(PluginBuilder.build(URL, "2.1.0")).get("skills/openmetadata/SKILL.md");

    assertThat(skill).startsWith("---\nname: openmetadata\n");
    assertThat(skill).contains("`get_context`").contains("Cite").contains("guardrail");
  }

  @Test
  void hooksCallHarnessHookOnThePluginScopedServer() throws IOException {
    final JsonNode hooks =
        json(unzip(PluginBuilder.build(URL, "2.1.0")), "hooks/hooks.json").path("hooks");

    assertThat(hooks.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse");
    hooks.forEach(
        groups -> {
          final JsonNode handler = groups.path(0).path("hooks").path(0);
          assertThat(handler.path("type").asText()).isEqualTo("mcp_tool");
          assertThat(handler.path("server").asText()).isEqualTo("plugin:openmetadata:openmetadata");
          assertThat(handler.path("tool").asText()).isEqualTo("harness_hook");
        });
    final JsonNode preToolUse = hooks.path("PreToolUse").path(0);
    assertThat(preToolUse.path("matcher").asText()).isEqualTo(".*");
    assertThat(preToolUse.path("hooks").path(0).path("input").path("tool_input").asText())
        .isEqualTo("${tool_input}");
    assertThat(
            hooks
                .path("UserPromptSubmit")
                .path(0)
                .path("hooks")
                .path(0)
                .path("input")
                .path("prompt")
                .asText())
        .isEqualTo("${prompt}");
  }

  @Test
  void codexManifestPointsAtTheSharedFilesAndCarriesItsOwnHooks() throws IOException {
    final JsonNode manifest =
        json(unzip(PluginBuilder.build(URL, "2.1.0")), ".codex-plugin/plugin.json");

    assertThat(manifest.path("name").asText()).isEqualTo("openmetadata");
    assertThat(manifest.path("version").asText()).isEqualTo("2.1.0");
    assertThat(manifest.path("skills").asText()).isEqualTo("./skills/");
    assertThat(manifest.path("mcpServers").asText()).isEqualTo("./.mcp.json");
    final JsonNode hooks = manifest.path("hooks").path("hooks");
    assertThat(hooks.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse");
    hooks.forEach(
        groups -> {
          final JsonNode handler = groups.path(0).path("hooks").path(0);
          assertThat(handler.path("type").asText()).isEqualTo("mcp_tool");
          assertThat(handler.path("server").asText()).isEqualTo("openmetadata");
          assertThat(handler.path("tool").asText()).isEqualTo("harness_hook");
        });
  }

  @Test
  void sessionStartFiresOnlyForTheSourcesThatRunAfterLaunch() throws IOException {
    final JsonNode hooks =
        json(unzip(PluginBuilder.build(URL, "2.1.0")), "hooks/hooks.json").path("hooks");
    final JsonNode codex =
        json(unzip(PluginBuilder.build(URL, "2.1.0")), ".codex-plugin/plugin.json")
            .path("hooks")
            .path("hooks");

    assertThat(hooks.path("SessionStart").path(0).path("matcher").asText())
        .isEqualTo("clear|compact");
    assertThat(codex.path("SessionStart").path(0).path("matcher").asText())
        .isEqualTo("clear|compact");
  }

  @Test
  void stopIsNotHookedWhileTheServerCompilesNothingForIt() throws IOException {
    final JsonNode hooks =
        json(unzip(PluginBuilder.build(URL, "2.1.0")), "hooks/hooks.json").path("hooks");

    assertThat(hooks.has("Stop")).isFalse();
  }

  @Test
  void noExecutableIsShipped() throws IOException {
    assertThat(unzip(PluginBuilder.build(URL, "2.1.0")).keySet())
        .noneMatch(name -> name.startsWith("bin/"));
  }

  @Test
  void identicalInputsGiveIdenticalBytes() {
    assertThat(PluginBuilder.build(URL, "2.1.0")).isEqualTo(PluginBuilder.build(URL, "2.1.0"));
    assertThat(PluginBuilder.build(URL, "2.1.1")).isNotEqualTo(PluginBuilder.build(URL, "2.1.0"));
  }

  private static JsonNode json(final Map<String, String> files, final String path) {
    return JsonUtils.readTree(files.get(path));
  }

  private static Map<String, String> unzip(final byte[] zip) throws IOException {
    final Map<String, String> files = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return files;
  }
}
