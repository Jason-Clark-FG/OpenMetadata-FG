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

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * A task handed to an agent: the agent's name, the task in words, the run's nodes it is about,
 * the knowledge-graph run it belongs to, and how long the caller will wait for it ({@code maxWait}:
 * a record component cannot be called {@code wait}).
 */
public record SubAgentRequest(
    String agent, String task, List<SubAgentNode> nodes, UUID run, Duration maxWait) {

  public SubAgentRequest {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
  }
}
