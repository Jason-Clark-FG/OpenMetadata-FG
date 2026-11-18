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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.openmetadata.schema.entity.context.AgentHarnessToolAccess;
import org.openmetadata.schema.entity.context.ToolAccessDefault;
import org.openmetadata.schema.entity.context.ToolAccessLevel;
import org.openmetadata.schema.entity.context.ToolAccessServer;

/**
 * Which MCP calls an agent may make. A harness lists servers in match order and the first entry
 * that matches decides. A persona harness is a second layer over the default: each call gets the
 * stricter decision of the two, so a persona can tighten the default and never loosen it, even
 * when the default becomes stricter after the persona was published.
 */
public final class ToolAccessPolicy {
  /** Name the OpenMetadata server has in agent hosts, which the plugin and managed settings use. */
  public static final String OPENMETADATA_SERVER = "openmetadata";

  private static final String MCP_PREFIX = "mcp__";
  private static final String SEPARATOR = "__";

  /** An MCP tool call, from a host tool name {@code mcp__<server>__<tool>}. */
  public record McpCall(String server, String tool) {}

  private ToolAccessPolicy() {}

  public static AgentHarnessToolAccess allowAll() {
    return new AgentHarnessToolAccess()
        .withDefault(ToolAccessDefault.ALLOW)
        .withServers(new ArrayList<>());
  }

  /** The MCP call a host tool name names, or empty for a tool that is not an MCP tool. */
  public static Optional<McpCall> parse(final String toolName) {
    Optional<McpCall> call = Optional.empty();
    if (toolName != null && toolName.startsWith(MCP_PREFIX)) {
      final String rest = toolName.substring(MCP_PREFIX.length());
      final int split = rest.indexOf(SEPARATOR);
      if (split > 0 && split + SEPARATOR.length() < rest.length()) {
        call =
            Optional.of(
                new McpCall(rest.substring(0, split), rest.substring(split + SEPARATOR.length())));
      }
    }
    return call;
  }

  /**
   * Whether the server is OpenMetadata itself, under its own name or scoped by a plugin
   * ({@code plugin_<plugin>_openmetadata}). Its tools are never refused: an agent locked out of
   * them could not fetch the harness that refused them.
   */
  public static boolean isOpenMetadata(final String server) {
    final String name = server.toLowerCase(Locale.ROOT);
    return name.equals(OPENMETADATA_SERVER) || name.endsWith("_" + OPENMETADATA_SERVER);
  }

  /** The stricter decision of every layer. A layer that is absent allows everything. */
  public static ToolAccessLevel decide(
      final List<AgentHarnessToolAccess> layers, final McpCall call) {
    ToolAccessLevel decision = ToolAccessLevel.ALLOW;
    if (!isOpenMetadata(call.server())) {
      for (final AgentHarnessToolAccess layer : layers) {
        decision = stricter(decision, decide(layer, call));
      }
    }
    return decision;
  }

  /** One harness's decision: the first matching entry, else the harness's default. */
  public static ToolAccessLevel decide(final AgentHarnessToolAccess access, final McpCall call) {
    ToolAccessLevel decision = ToolAccessLevel.ALLOW;
    if (access != null) {
      decision =
          listOrEmpty(access.getServers()).stream()
              .filter(entry -> matches(entry, call))
              .map(ToolAccessServer::getLevel)
              .findFirst()
              .orElse(levelOf(access.getDefault()));
    }
    return decision;
  }

  /** Rejects entries without a server name or level, and a server and tool set listed twice. */
  public static void requireValid(final AgentHarnessToolAccess access) {
    if (access != null) {
      if (access.getDefault() == null) {
        throw new IllegalArgumentException("toolAccess needs a default of Allow or Deny");
      }
      final Set<String> listed = new HashSet<>();
      for (final ToolAccessServer entry : listOrEmpty(access.getServers())) {
        if (entry.getMatch() == null || entry.getMatch().isBlank() || entry.getLevel() == null) {
          throw new IllegalArgumentException("Every toolAccess server needs a match and a level");
        }
        final String key =
            entry.getMatch().strip().toLowerCase(Locale.ROOT) + listOrEmpty(entry.getTools());
        if (!listed.add(key)) {
          throw new IllegalArgumentException(
              "toolAccess server " + entry.getMatch() + " is listed more than once");
        }
      }
    }
  }

  /** Rejects persona settings that would allow a call the default refuses or warns about. */
  public static void requireNoLooserThan(
      final AgentHarnessToolAccess persona, final AgentHarnessToolAccess defaults) {
    if (persona != null && defaults != null) {
      if (levelOf(persona.getDefault()).compareTo(levelOf(defaults.getDefault())) < 0) {
        throw new IllegalArgumentException(
            "toolAccess default cannot be Allow while the default harness denies unlisted servers");
      }
      for (final ToolAccessServer entry : listOrEmpty(persona.getServers())) {
        for (final McpCall call : callsOf(entry)) {
          final ToolAccessLevel minimum = decide(defaults, call);
          if (entry.getLevel().compareTo(minimum) < 0) {
            throw new IllegalArgumentException(
                String.format(
                    "toolAccess server %s cannot be %s. The default harness sets %s",
                    entry.getMatch(), entry.getLevel().value(), minimum.value()));
          }
        }
      }
    }
  }

  public static ToolAccessLevel levelOf(final ToolAccessDefault value) {
    return value == ToolAccessDefault.DENY ? ToolAccessLevel.BLOCK : ToolAccessLevel.ALLOW;
  }

  private static List<McpCall> callsOf(final ToolAccessServer entry) {
    final List<String> tools = listOrEmpty(entry.getTools());
    return tools.isEmpty()
        ? List.of(new McpCall(entry.getMatch(), null))
        : tools.stream().map(tool -> new McpCall(entry.getMatch(), tool)).toList();
  }

  private static boolean matches(final ToolAccessServer entry, final McpCall call) {
    final List<String> tools = listOrEmpty(entry.getTools());
    return entry.getMatch() != null
        && entry.getMatch().strip().equalsIgnoreCase(call.server())
        && (tools.isEmpty() || tools.contains(call.tool()));
  }

  private static ToolAccessLevel stricter(
      final ToolAccessLevel first, final ToolAccessLevel second) {
    return first.compareTo(second) >= 0 ? first : second;
  }
}
