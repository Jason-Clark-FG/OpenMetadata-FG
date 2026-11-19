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

/**
 * What became of one candidate: the memory it {@code saved}, with the node it is {@code about} and
 * its stage in words ({@code effect}), or
 * the {@code reason} it was not, and for a repeat the memory that already says it ({@code sameAs}).
 * The trace keeps the {@code candidate} with it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LearnedOutcome(
    String title,
    MemoryEntry saved,
    String about,
    String effect,
    String reason,
    String sameAs,
    LearnedCandidate candidate) {

  static LearnedOutcome saved(final LearnedCandidate candidate, final MemoryEntry memory) {
    return new LearnedOutcome(
        candidate.title(), memory, candidate.about(), effectOf(memory), null, null, candidate);
  }

  static LearnedOutcome notSaved(final LearnedCandidate candidate, final String reason) {
    return new LearnedOutcome(candidate.title(), null, null, null, reason, null, candidate);
  }

  static LearnedOutcome repeat(
      final LearnedCandidate candidate, final String reason, final String sameAs) {
    return new LearnedOutcome(candidate.title(), null, null, null, reason, sameAs, candidate);
  }

  /** The answer's view of the outcome; the candidate stays in the trace. */
  public LearnedOutcome withoutCandidate() {
    return new LearnedOutcome(title, saved, about, effect, reason, sameAs, null);
  }

  /** The stage in words: who the memory is in force for now. */
  private static String effectOf(final MemoryEntry memory) {
    return Boolean.TRUE.equals(memory.awaitingReview())
        ? "Saved, awaiting review (%s): in force now for you, for others once approved."
            .formatted(memory.stage())
        : "Saved and %s: in force now for everyone who reads it.".formatted(memory.stage());
  }
}
