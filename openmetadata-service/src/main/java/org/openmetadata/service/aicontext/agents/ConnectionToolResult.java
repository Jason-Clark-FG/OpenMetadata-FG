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
 * What a connection's tool answered: its status, the text it returned, its structured result when
 * it has one, and the error, in the upstream's own words, when it failed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectionToolResult(Status status, String content, Object structured, String error) {

  /** Whether the tool answered, or the call failed (a tool error, no answer, a timeout). */
  public enum Status {
    COMPLETED,
    FAILED
  }

  public static ConnectionToolResult completed(final String content, final Object structured) {
    return new ConnectionToolResult(Status.COMPLETED, content, structured, null);
  }

  public static ConnectionToolResult failed(final String error) {
    return new ConnectionToolResult(Status.FAILED, null, null, error);
  }

  /** A tool that answered with an error: its text is the error, beside any structured result. */
  public static ConnectionToolResult failed(final String error, final Object structured) {
    return new ConnectionToolResult(Status.FAILED, null, structured, error);
  }
}
