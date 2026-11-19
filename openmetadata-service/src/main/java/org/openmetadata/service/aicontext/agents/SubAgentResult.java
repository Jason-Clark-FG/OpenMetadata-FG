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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What an agent answered: its status, the answer in words, what it produced (the SQL it ran, a
 * bounded table, a link to open the run in the product), a handle while it is still running, and
 * the error when it failed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubAgentResult(
    String agent,
    Status status,
    String answer,
    String sql,
    SubAgentTable table,
    String link,
    String handle,
    String error) {

  /** Whether the agent finished, is still running, or failed. */
  public enum Status {
    COMPLETED,
    RUNNING,
    FAILED
  }

  public static SubAgentResult running(final String agent, final String handle) {
    return new SubAgentResult(agent, Status.RUNNING, null, null, null, null, handle, null);
  }

  public static SubAgentResult failed(final String agent, final String error) {
    return new SubAgentResult(agent, Status.FAILED, null, null, null, null, null, error);
  }
}
