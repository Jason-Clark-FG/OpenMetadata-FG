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
package org.openmetadata.service.rdf.explore;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Set;

/**
 * One memory as a response shows it: its node, title, statement (question and answer, cut like
 * other long values, with the full {@code length} when cut), stage and when its content changed.
 * {@code awaitingReview} is true only for a memory not yet approved.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryEntry(
    String id,
    String title,
    String statement,
    Integer length,
    String stage,
    Boolean awaitingReview,
    String changedAt) {

  /** The entry of a memory read from the catalog rather than the graph, cut the same way. */
  public static MemoryEntry of(
      final String iri,
      final String title,
      final String question,
      final String answer,
      final String stage,
      final Long changedAt) {
    return new StoredMemory(
            iri,
            Set.of(),
            title,
            question,
            question == null ? 0 : question.length(),
            answer,
            answer == null ? 0 : answer.length(),
            stage,
            changedAt,
            false,
            0)
        .cut();
  }
}
