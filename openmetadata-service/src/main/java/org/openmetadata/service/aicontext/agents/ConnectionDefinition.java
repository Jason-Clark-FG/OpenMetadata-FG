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
package org.openmetadata.service.aicontext.agents;

import java.util.List;
import java.util.Optional;

/**
 * An outside MCP server a distribution connected: its name (what {@code delegate} takes as {@code
 * connection}), a name for people, what it is in a sentence, and the tools it offers.
 */
public record ConnectionDefinition(
    String name, String displayName, String description, List<ConnectionTool> tools) {

  public ConnectionDefinition {
    tools = tools == null ? List.of() : List.copyOf(tools);
  }

  public Optional<ConnectionTool> tool(final String toolName) {
    return tools.stream().filter(tool -> tool.name().equals(toolName)).findFirst();
  }
}
