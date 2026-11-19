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

import java.util.Set;
import java.util.UUID;

/**
 * Saves what a run learnt, after the checks the graph can make (the card is one the run showed,
 * the corrected memory one it showed, evidence is present). The writer checks repeats against the
 * catalog and saves through the catalog's create path, as the caller.
 */
@FunctionalInterface
public interface MemoryWriter {

  /** Refuses every candidate, with {@code reason}. */
  static MemoryWriter refusing(final String reason) {
    return (candidate, shownMemories, run) -> new Written.Refused(reason);
  }

  Written write(LearnedCandidate candidate, Set<String> shownMemories, UUID run);

  /** What the writer did with one candidate. */
  sealed interface Written {
    /** Saved, or a memory awaiting review corrected in place. */
    record Saved(MemoryEntry memory) implements Written {}

    /** Not saved: {@code memory} already says it. */
    record Repeat(String memory, String title) implements Written {}

    /** Not saved, with the reason. */
    record Refused(String reason) implements Written {}
  }
}
