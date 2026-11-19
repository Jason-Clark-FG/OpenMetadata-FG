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
import java.util.Optional;

/**
 * A distribution's agents that the knowledge_graph tool can hand a task to. Registered at startup
 * with {@link SubAgentRegistry}; OpenMetadata itself registers none.
 */
public interface SubAgentProvider {

  /** The agents this provider runs; each name is unique across providers. */
  List<SubAgentDefinition> agents();

  /**
   * Runs {@code request} as {@code caller}. May block; the registry's caller bounds the wait and
   * answers RUNNING with the handle when it is not done.
   */
  SubAgentResult run(SecurityContext caller, SubAgentRequest request);

  /** The result of a run that answered RUNNING, by its handle; empty when the handle is unknown. */
  Optional<SubAgentResult> result(SecurityContext caller, String handle);
}
