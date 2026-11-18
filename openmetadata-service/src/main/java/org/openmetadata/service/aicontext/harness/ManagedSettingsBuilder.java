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

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.openmetadata.schema.api.context.AgentHarnessManagedSettings;
import org.openmetadata.schema.entity.context.AgentHarnessToolAccess;
import org.openmetadata.schema.entity.context.ToolAccessDefault;
import org.openmetadata.schema.entity.context.ToolAccessLevel;
import org.openmetadata.schema.entity.context.ToolAccessServer;
import org.openmetadata.schema.utils.JsonUtils;

/**
 * Host settings that enforce the default harness's MCP access before any hook runs: Claude Code
 * managed settings and the Codex {@code requirements.toml} allowlist. The hosts match servers by
 * the name a user gave them, a URL or a command; the harness records only names, so entries match
 * by name, and an entry limited to some tools is left to the hooks.
 */
public final class ManagedSettingsBuilder {
  static final String DENIED = "deniedMcpServers";
  static final String ALLOWED = "allowedMcpServers";
  static final String ALLOWED_ONLY_MANAGED = "allowManagedMcpServersOnly";
  static final String SERVER_NAME = "serverName";
  static final String URL_PLACEHOLDER = "REPLACE_WITH_THE_SERVER_URL";

  private ManagedSettingsBuilder() {}

  public static AgentHarnessManagedSettings build(
      final AgentHarnessToolAccess access, final String serverUrl) {
    final AgentHarnessToolAccess policy = access == null ? ToolAccessPolicy.allowAll() : access;
    return new AgentHarnessManagedSettings()
        .withServerName(ToolAccessPolicy.OPENMETADATA_SERVER)
        .withServerUrl(serverUrl)
        .withClaudeCode(claudeCode(policy))
        .withCodexRequirementsToml(codex(policy, serverUrl))
        .withNotes(notes(policy));
  }

  private static ObjectNode claudeCode(final AgentHarnessToolAccess policy) {
    final ObjectNode settings = JsonUtils.getObjectMapper().createObjectNode();
    final ArrayNode denied = settings.putArray(DENIED);
    blockedServers(policy).forEach(name -> denied.addObject().put(SERVER_NAME, name));
    if (deniesUnlisted(policy)) {
      final ArrayNode allowed = settings.putArray(ALLOWED);
      allowedServers(policy).forEach(name -> allowed.addObject().put(SERVER_NAME, name));
      settings.put(ALLOWED_ONLY_MANAGED, true);
    }
    return settings;
  }

  private static String codex(final AgentHarnessToolAccess policy, final String serverUrl) {
    final StringBuilder toml = new StringBuilder();
    if (deniesUnlisted(policy)) {
      for (final String name : allowedServers(policy)) {
        final boolean isOpenMetadata = ToolAccessPolicy.OPENMETADATA_SERVER.equals(name);
        toml.append("[mcp_servers.")
            .append(JsonUtils.pojoToJson(name))
            .append("]\nidentity = { url = ")
            .append(JsonUtils.pojoToJson(isOpenMetadata ? serverUrl : URL_PLACEHOLDER))
            .append(" }\n\n");
      }
    }
    return toml.toString().strip();
  }

  private static List<String> notes(final AgentHarnessToolAccess policy) {
    final List<String> notes = new ArrayList<>();
    notes.add(
        "Entries match the server name each user gave the server, which a user can change. "
            + "To pin a server, replace its serverName entry with a serverUrl or serverCommand entry.");
    for (final ToolAccessServer entry : listOrEmpty(policy.getServers())) {
      if (!listOrEmpty(entry.getTools()).isEmpty()) {
        notes.add(
            "Server "
                + entry.getMatch()
                + " is limited to some tools. Only the OpenMetadata hooks enforce that.");
      }
    }
    if (deniesUnlisted(policy)) {
      notes.add(
          "Codex matches a server by name and URL or command. Replace "
              + URL_PLACEHOLDER
              + " with each server's URL, or its identity with a command.");
    } else {
      notes.add(
          "Codex requirements have no denylist, so blocked servers are enforced only by the "
              + "OpenMetadata hooks while the default allows unlisted servers.");
    }
    return notes;
  }

  private static boolean deniesUnlisted(final AgentHarnessToolAccess policy) {
    return policy.getDefault() == ToolAccessDefault.DENY;
  }

  private static List<String> blockedServers(final AgentHarnessToolAccess policy) {
    return listOrEmpty(policy.getServers()).stream()
        .filter(entry -> entry.getLevel() == ToolAccessLevel.BLOCK)
        .filter(entry -> listOrEmpty(entry.getTools()).isEmpty())
        .map(entry -> entry.getMatch().strip())
        .distinct()
        .toList();
  }

  private static List<String> allowedServers(final AgentHarnessToolAccess policy) {
    final Set<String> names = new LinkedHashSet<>();
    names.add(ToolAccessPolicy.OPENMETADATA_SERVER);
    final List<String> blocked = blockedServers(policy);
    listOrEmpty(policy.getServers()).stream()
        .filter(
            entry ->
                entry.getLevel() != ToolAccessLevel.BLOCK
                    || !listOrEmpty(entry.getTools()).isEmpty())
        .map(entry -> entry.getMatch().strip())
        .filter(name -> !blocked.contains(name))
        .forEach(names::add);
    return List.copyOf(names);
  }
}
