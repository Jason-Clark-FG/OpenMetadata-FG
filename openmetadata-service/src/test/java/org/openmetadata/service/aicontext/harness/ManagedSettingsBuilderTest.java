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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.AgentHarnessManagedSettings;
import org.openmetadata.schema.entity.context.AgentHarnessToolAccess;
import org.openmetadata.schema.entity.context.ToolAccessDefault;
import org.openmetadata.schema.entity.context.ToolAccessLevel;
import org.openmetadata.schema.entity.context.ToolAccessServer;
import org.openmetadata.schema.utils.JsonUtils;

class ManagedSettingsBuilderTest {
  private static final String URL = "https://om.example.com/mcp";

  @Test
  void anAllowDefaultDeniesOnlyWholeServerBlocks() {
    final AgentHarnessManagedSettings settings =
        ManagedSettingsBuilder.build(
            access(
                ToolAccessDefault.ALLOW,
                server("slack", ToolAccessLevel.BLOCK, List.of()),
                server("github", ToolAccessLevel.BLOCK, List.of("delete_repo")),
                server("linear", ToolAccessLevel.WARN, List.of())),
            URL);

    assertEquals(
        JsonUtils.readTree("{\"deniedMcpServers\":[{\"serverName\":\"slack\"}]}"),
        JsonUtils.valueToTree(settings.getClaudeCode()));
    assertEquals("", settings.getCodexRequirementsToml());
    assertEquals("openmetadata", settings.getServerName());
    assertEquals(URL, settings.getServerUrl());
  }

  @Test
  void aDenyDefaultAllowsOnlyListedServersAndOpenMetadata() {
    final AgentHarnessManagedSettings settings =
        ManagedSettingsBuilder.build(
            access(
                ToolAccessDefault.DENY,
                server("slack", ToolAccessLevel.BLOCK, List.of()),
                server("github", ToolAccessLevel.WARN, List.of())),
            URL);

    assertEquals(
        JsonUtils.readTree(
            """
            {"deniedMcpServers": [{"serverName": "slack"}],
             "allowedMcpServers": [{"serverName": "openmetadata"}, {"serverName": "github"}],
             "allowManagedMcpServersOnly": true}"""),
        JsonUtils.valueToTree(settings.getClaudeCode()));
    assertEquals(
        """
        [mcp_servers."openmetadata"]
        identity = { url = "https://om.example.com/mcp" }

        [mcp_servers."github"]
        identity = { url = "REPLACE_WITH_THE_SERVER_URL" }""",
        settings.getCodexRequirementsToml());
  }

  @Test
  void toolLimitedEntriesAreLeftToTheHooksAndNoted() {
    final AgentHarnessManagedSettings settings =
        ManagedSettingsBuilder.build(
            access(
                ToolAccessDefault.ALLOW,
                server("github", ToolAccessLevel.BLOCK, List.of("delete_repo"))),
            URL);

    assertEquals(
        List.of(
            "Entries match the server name each user gave the server, which a user can change. To"
                + " pin a server, replace its serverName entry with a serverUrl or serverCommand"
                + " entry.",
            "Server github is limited to some tools. Only the OpenMetadata hooks enforce that.",
            "Codex requirements have no denylist, so blocked servers are enforced only by the"
                + " OpenMetadata hooks while the default allows unlisted servers."),
        settings.getNotes());
  }

  private static AgentHarnessToolAccess access(
      final ToolAccessDefault defaultLevel, final ToolAccessServer... servers) {
    return new AgentHarnessToolAccess().withDefault(defaultLevel).withServers(List.of(servers));
  }

  private static ToolAccessServer server(
      final String match, final ToolAccessLevel level, final List<String> tools) {
    return new ToolAccessServer().withMatch(match).withLevel(level).withTools(tools);
  }
}
