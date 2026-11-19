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
package org.openmetadata.service.aicontext.capture;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.openmetadata.schema.entity.context.ContextMemory;
import org.openmetadata.schema.entity.context.ContextMemoryStatus;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.ContextMemoryRepository;
import org.openmetadata.service.resources.context.ContextMemoryVisibility;

/**
 * Finds the memory a captured fact only restates: one on the same anchor, still in use or in
 * review, that the writer may read and whose answer, the stated fact, is the same words once case,
 * punctuation and spacing are ignored. A differing title or question does not make a new fact.
 *
 * <p>It does not use {@code SemanticMemoryDuplicateFinder}, which compares what documents state
 * and needs the search index and an AI judge. A differing fact is a new memory, and the memory
 * review's conflict handling compares it with the others
 * (ADR:2026-11-18-agent-captured-memories-merge-only-restatements).
 */
public final class AgentMemoryDuplicates {
  static final int MAX_CANDIDATES = 100;
  private static final Set<ContextMemoryStatus> LIVE =
      Set.of(
          ContextMemoryStatus.UNPROCESSED, ContextMemoryStatus.DRAFT, ContextMemoryStatus.APPROVED);

  private AgentMemoryDuplicates() {}

  public static Optional<ContextMemory> find(
      final ContextMemoryRepository repository,
      final EntityReference anchor,
      final String answer,
      final String reader) {
    if (anchor == null) {
      return Optional.empty();
    }
    final List<EntityReference> candidates =
        repository
            .findTo(
                anchor.getId(), anchor.getType(), Relationship.APPLIED_TO, Entity.CONTEXT_MEMORY)
            .stream()
            .limit(MAX_CANDIDATES)
            .toList();
    return candidates.stream()
        .map(AgentMemoryDuplicates::load)
        .flatMap(Optional::stream)
        .filter(memory -> LIVE.contains(memory.getEntityStatus()))
        .filter(memory -> ContextMemoryVisibility.isVisibleToUser(memory, reader, false))
        .filter(memory -> restates(memory, answer))
        .findFirst();
  }

  static boolean restates(final ContextMemory memory, final String answer) {
    return normalize(memory.getAnswer()).equals(normalize(answer));
  }

  public static String normalize(final String text) {
    return text == null
        ? ""
        : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
  }

  private static Optional<ContextMemory> load(final EntityReference reference) {
    try {
      return Optional.of(
          Entity.getEntity(
              reference,
              ContextMemoryVisibility.guardFields(Entity.CONTEXT_MEMORY, ""),
              Include.NON_DELETED));
    } catch (EntityNotFoundException gone) {
      return Optional.empty();
    }
  }
}
