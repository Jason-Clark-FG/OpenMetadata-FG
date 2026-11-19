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

package org.openmetadata.mcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.openmetadata.schema.entity.context.AgentHarnessToolAccess;
import org.openmetadata.schema.entity.context.ToolAccessLevel;
import org.openmetadata.schema.entity.context.ToolAccessServer;
import org.openmetadata.service.aicontext.agents.ConnectionDefinition;
import org.openmetadata.service.aicontext.agents.ConnectionTool;
import org.openmetadata.service.aicontext.harness.ToolAccessPolicy;
import org.openmetadata.service.aicontext.harness.ToolAccessPolicy.McpCall;

/**
 * The Agent Harness's MCP Access rules applied by the server to a connection called through
 * delegate: the layers the PreToolUse hook reads (the published default harness, then the caller's
 * persona harness), decided with the connection's name as the server and the tool's as the tool.
 * The stricter layer wins. {@code readable} is false when the caller's harness could not be read:
 * then every connection is refused, so an unreadable rule never lets a call through.
 */
record ConnectionAccess(List<AgentHarnessToolAccess> layers, boolean readable) {

  /** No rule could be read: nothing is offered. */
  static final ConnectionAccess UNREADABLE = new ConnectionAccess(List.of(), false);

  static final String UNREADABLE_RULE = "the agent harness could not be read, so none is offered";

  ConnectionAccess {
    layers = Collections.unmodifiableList(new ArrayList<>(layers));
  }

  static ConnectionAccess of(final List<AgentHarnessToolAccess> layers) {
    return new ConnectionAccess(layers == null ? List.of() : layers, true);
  }

  ToolAccessLevel level(final String connection, final String tool) {
    return readable
        ? ToolAccessPolicy.decide(layers, new McpCall(connection, tool))
        : ToolAccessLevel.BLOCK;
  }

  /** The tools of {@code connection} the rules leave the caller, in its order. */
  List<ConnectionTool> offeredTools(final ConnectionDefinition connection) {
    return connection.tools().stream()
        .filter(tool -> level(connection.name(), tool.name()) != ToolAccessLevel.BLOCK)
        .toList();
  }

  /** The connections that keep at least one tool for the caller. */
  List<ConnectionDefinition> offered(final List<ConnectionDefinition> connections) {
    return connections.stream().filter(connection -> !offeredTools(connection).isEmpty()).toList();
  }

  /** In words, the rule that decides the call: which harness, and its entry or its default. */
  String rule(final String connection, final String tool) {
    final McpCall call = new McpCall(connection, tool);
    final ToolAccessLevel decision = level(connection, tool);
    return readable
        ? IntStream.range(0, layers.size())
            .filter(layer -> ToolAccessPolicy.decide(layers.get(layer), call) == decision)
            .mapToObj(layer -> ruleOf(layer, layers.get(layer), call, decision))
            .findFirst()
            .orElse("no MCP Access rule, which allows it")
        : UNREADABLE_RULE;
  }

  private static String ruleOf(
      final int layer,
      final AgentHarnessToolAccess access,
      final McpCall call,
      final ToolAccessLevel decision) {
    final String harness =
        layer == 0 ? "the default agent harness" : "your persona's agent harness";
    final Optional<ToolAccessServer> entry = ToolAccessPolicy.decidingEntry(access, call);
    return entry
        .map(
            server ->
                "%s's MCP Access entry for server '%s'%s sets %s"
                    .formatted(harness, server.getMatch(), tools(server), decision.value()))
        .orElseGet(
            () ->
                "%s's MCP Access default (%s for servers no entry lists)"
                    .formatted(
                        harness,
                        access == null || access.getDefault() == null
                            ? "Allow"
                            : access.getDefault().value()));
  }

  private static String tools(final ToolAccessServer server) {
    final List<String> tools = server.getTools() == null ? List.of() : server.getTools();
    return tools.isEmpty() ? "" : " and tools " + String.join(", ", tools);
  }
}
