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

/**
 * A registered connection as the Agent Harness settings page lists it: its name, a name for
 * people, what it is, and its tools' names and descriptions.
 */
public record RegisteredConnection(
    String name, String displayName, String description, List<Tool> tools) {

  public record Tool(String name, String description) {}

  public static RegisteredConnection of(final ConnectionDefinition connection) {
    return new RegisteredConnection(
        connection.name(),
        connection.displayName(),
        connection.description(),
        connection.tools().stream()
            .map(tool -> new Tool(tool.name(), tool.description()))
            .toList());
  }
}
