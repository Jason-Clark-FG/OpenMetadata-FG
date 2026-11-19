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
package org.openmetadata.it.tests.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.ADMIN_TOKEN;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.HARNESS;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.admin;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.read;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.send;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.uniqueName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openmetadata.it.bootstrap.TestSuiteBootstrap;
import org.openmetadata.it.tests.mcp.McpTestBase;
import org.openmetadata.it.tests.mcp.McpTestUtils;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.api.context.AgentHarnessSurface;
import org.openmetadata.schema.api.context.AgentHarnessSurfaceId;
import org.openmetadata.schema.api.context.AgentHarnessSurfaceStatus;
import org.openmetadata.schema.api.context.AgentHarnessSurfaces;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.aicontext.harness.HarnessSpecs;

class AgentHarnessPluginIT extends McpTestBase {
  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final Duration RECORD_TIMEOUT = Duration.ofSeconds(30);

  @BeforeAll
  static void setup() throws Exception {
    initAuth();
  }

  @Test
  void anAdminDownloadsAZipOfTheSkillConnectorAndHooks() throws Exception {
    final HttpResponse<byte[]> response = download(ADMIN_TOKEN);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/zip");
    assertThat(response.headers().firstValue("Content-Disposition"))
        .hasValueSatisfying(value -> assertThat(value).contains("openmetadata.zip"));
    final Map<String, String> files = unzip(response.body());
    assertThat(files.keySet())
        .containsExactly(
            ".claude-plugin/plugin.json",
            ".codex-plugin/plugin.json",
            ".mcp.json",
            "hooks/hooks.json",
            "skills/openmetadata/SKILL.md");
    assertThat(files.get(".mcp.json")).contains("/mcp\"");
    assertThat(files.get("hooks/hooks.json")).contains("plugin:openmetadata:openmetadata");
    final var codex = JsonUtils.readTree(files.get(".codex-plugin/plugin.json"));
    assertThat(codex.path("mcpServers").asText()).isEqualTo("./.mcp.json");
    final var handler =
        codex.path("hooks").path("hooks").path("PreToolUse").path(0).path("hooks").path(0);
    assertThat(handler.path("server").asText()).isEqualTo("openmetadata");
    assertThat(handler.path("tool").asText()).isEqualTo("harness_hook");
  }

  @Test
  void nonAdminUsersAndBotsAreRefused() throws Exception {
    final User member = AgentHarnessApi.createUser(uniqueName("plugin_member"));
    final User bot =
        read(send("GET", "users/name/ingestion-bot", null, ADMIN_TOKEN), 200, User.class);

    for (final String token :
        List.of(AgentHarnessApi.tokenFor(member), AgentHarnessApi.tokenFor(bot))) {
      assertThat(download(token).statusCode()).isEqualTo(403);
      assertThat(send("GET", HARNESS + "/surfaces", null, token).statusCode()).isEqualTo(403);
    }
  }

  @Test
  @ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK)
  void publishingCompanyInstructionsDoesNotChangeThePlugin() throws Exception {
    final byte[] before = download(ADMIN_TOKEN).body();
    final String instructions = "Plugin bytes must not carry " + uniqueName("secret_rule");

    publishDefaultInstructions(instructions);

    final byte[] after = download(ADMIN_TOKEN).body();
    assertThat(after).isEqualTo(before);
    assertThat(new String(after, StandardCharsets.ISO_8859_1)).doesNotContain(instructions);
  }

  @Test
  void aSurfaceIsConnectedAfterAToolCallThatNamesItInTheUserAgent() throws Exception {
    final AgentHarnessSurfaces before = surfaces();
    assertThat(before.getSurfaces())
        .extracting(AgentHarnessSurface::getId)
        .containsExactly(
            AgentHarnessSurfaceId.CLAUDE_CHAT,
            AgentHarnessSurfaceId.CLAUDE_DESKTOP,
            AgentHarnessSurfaceId.COWORK,
            AgentHarnessSurfaceId.CLAUDE_CODE,
            AgentHarnessSurfaceId.CODEX,
            AgentHarnessSurfaceId.CHAT_GPT);
    assertThat(surface(before, AgentHarnessSurfaceId.CHAT_GPT).getStatus())
        .isEqualTo(AgentHarnessSurfaceStatus.NOT_CONNECTED);
    assertThat(surface(before, AgentHarnessSurfaceId.CHAT_GPT).getLastSeenAt()).isNull();

    callTool("get_context", "claude-cli/2.1.0 (external, cli)");
    callTool("get_context", "codex_cli_rs/0.5.0");

    await()
        .atMost(RECORD_TIMEOUT)
        .untilAsserted(
            () -> {
              final AgentHarnessSurfaces now = surfaces();
              assertThat(surface(now, AgentHarnessSurfaceId.CLAUDE_CODE).getStatus())
                  .isEqualTo(AgentHarnessSurfaceStatus.CONNECTED);
              assertThat(surface(now, AgentHarnessSurfaceId.CLAUDE_CODE).getLastSeenAt())
                  .isPositive();
              assertThat(surface(now, AgentHarnessSurfaceId.CODEX).getStatus())
                  .isEqualTo(AgentHarnessSurfaceStatus.CONNECTED);
            });
    final AgentHarnessSurfaces after = surfaces();
    assertThat(surface(after, AgentHarnessSurfaceId.CHAT_GPT).getStatus())
        .isEqualTo(AgentHarnessSurfaceStatus.NOT_CONNECTED);
    assertThat(surface(after, AgentHarnessSurfaceId.COWORK).getStatus())
        .isEqualTo(AgentHarnessSurfaceStatus.NOT_CONNECTED);
    final AgentHarnessSurface claudeCode = surface(after, AgentHarnessSurfaceId.CLAUDE_CODE);
    assertThat(List.of(claudeCode.getSkills(), claudeCode.getConnector(), claudeCode.getHooks()))
        .containsOnly(true);
    final AgentHarnessSurface codex = surface(after, AgentHarnessSurfaceId.CODEX);
    assertThat(List.of(codex.getSkills(), codex.getConnector(), codex.getHooks()))
        .containsOnly(true);
    final AgentHarnessSurface chatGpt = surface(after, AgentHarnessSurfaceId.CHAT_GPT);
    assertThat(List.of(chatGpt.getSkills(), chatGpt.getConnector(), chatGpt.getHooks()))
        .containsExactly(false, true, false);
  }

  /** Other suites edit the default's draft too, so a stale revision is read again and retried. */
  private static void publishDefaultInstructions(final String instructions) {
    await()
        .atMost(RECORD_TIMEOUT)
        .untilAsserted(
            () -> {
              final AgentHarnessState state = admin("GET", "/default", null, null);
              final AgentHarnessSpec spec =
                  state.getDraft() == null
                      ? HarnessSpecs.specOf(state.getPublished())
                      : state.getDraft();
              final String id = "/" + state.getPublished().getId();
              final HttpResponse<String> drafted =
                  send(
                      "PUT",
                      HARNESS + id + "/draft",
                      spec.withCompanyInstructions(instructions),
                      ADMIN_TOKEN,
                      state.getDraftRevision());
              assertThat(drafted.statusCode()).as(drafted.body()).isEqualTo(200);
              final int revision =
                  JsonUtils.readValue(drafted.body(), AgentHarnessState.class).getDraftRevision();
              final HttpResponse<String> published =
                  send("POST", HARNESS + id + "/publish", null, ADMIN_TOKEN, revision);
              assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
            });
  }

  private static HttpResponse<byte[]> download(final String token) throws Exception {
    return CLIENT.send(
        HttpRequest.newBuilder(
                URI.create(
                    TestSuiteBootstrap.getBaseUrl()
                        + "/api/v1/"
                        + HARNESS
                        + "/plugin?target=claude"))
            .header("Authorization", token)
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static AgentHarnessSurfaces surfaces() {
    return read(
        send("GET", HARNESS + "/surfaces", null, ADMIN_TOKEN), 200, AgentHarnessSurfaces.class);
  }

  private static AgentHarnessSurface surface(
      final AgentHarnessSurfaces surfaces, final AgentHarnessSurfaceId id) {
    return surfaces.getSurfaces().stream()
        .filter(surface -> surface.getId() == id)
        .findFirst()
        .orElseThrow();
  }

  private static void callTool(final String tool, final String userAgent) throws Exception {
    final HttpResponse<String> response =
        HTTP_CLIENT.send(
            HttpRequest.newBuilder()
                .uri(URI.create(TestSuiteBootstrap.getBaseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", ADMIN_TOKEN)
                .header("User-Agent", userAgent)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        OBJECT_MAPPER.writeValueAsString(
                            McpTestUtils.createToolCallRequest(tool, Map.of()))))
                .timeout(Duration.ofSeconds(30))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
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
