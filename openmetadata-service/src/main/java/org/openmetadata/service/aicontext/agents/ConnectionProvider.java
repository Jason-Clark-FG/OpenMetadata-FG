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

import jakarta.ws.rs.core.SecurityContext;
import java.util.List;

/**
 * A distribution's outside MCP servers that the knowledge_graph tool can call through {@code
 * delegate}. Registered at startup with {@link ConnectionRegistry}; OpenMetadata itself registers
 * none. Connections come and go while the server runs, so {@link #connections()} is asked each
 * time it is needed and never kept.
 */
public interface ConnectionProvider {

  /** The connections usable now, each with its tools; names are unique across providers. */
  List<ConnectionDefinition> connections();

  /**
   * Calls one tool of one connection as {@code caller}. May block; answers a failure (a tool
   * error, an unreachable server, a timeout) as {@link ConnectionToolResult.Status#FAILED} with
   * the upstream's words rather than throwing.
   */
  ConnectionToolResult call(SecurityContext caller, ConnectionToolCall call);
}
