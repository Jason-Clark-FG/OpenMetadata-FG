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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The final step of a turn: each candidate {@code cite} carries under {@code learned} is checked
 * against what the run showed, in order, and the rest handed to the writer. Every outcome is named,
 * so the answer can say what was saved and why the rest was not
 * (ADR:2026-11-17-one-knowledge-graph-tool-explores-through-node-cards, v20).
 */
final class TurnLearning {

  static final int MAX_CANDIDATES = 3;

  private static final String NOT_SAVED = "not saved: ";
  private static final String ABOUT_NOT_SHOWN =
      NOT_SAVED + "'about' must be the id of a card this run showed; %s was not shown in it.";
  private static final String NOT_AN_ASSET =
      NOT_SAVED
          + "%s is not an asset a memory can be about; leave 'about' out for a personal note.";
  private static final String CORRECTS_NOT_SHOWN =
      NOT_SAVED + "'corrects' must be the id of a memory this run showed; %s was not.";
  private static final String NO_EVIDENCE =
      NOT_SAVED + "no evidence; quote the person's words or a tool result under 'evidence'.";
  private static final String REPEAT = NOT_SAVED + "memory %s (\"%s\") already says this.";

  private final List<RunStep> steps;
  private final NodeIris nodeIris;

  TurnLearning(final List<RunStep> steps, final NodeIris nodeIris) {
    this.steps = List.copyOf(steps);
    this.nodeIris = nodeIris;
  }

  List<LearnedOutcome> keep(
      final List<LearnedCandidate> candidates, final MemoryWriter writer, final UUID run) {
    final Set<String> memories = shownMemories();
    final Set<String> cards = shownCards();
    final List<LearnedOutcome> outcomes = new ArrayList<>();
    for (final LearnedCandidate candidate : candidates) {
      outcomes.add(
          refusal(candidate, cards, memories)
              .map(reason -> LearnedOutcome.notSaved(candidate, reason))
              .orElseGet(() -> written(candidate, writer.write(candidate, memories, run))));
    }
    return List.copyOf(outcomes);
  }

  private Optional<String> refusal(
      final LearnedCandidate candidate, final Set<String> cards, final Set<String> memories) {
    final String about = candidate.about();
    final String corrects = candidate.corrects();
    String reason = null;
    if (about != null && !cards.contains(about)) {
      reason = ABOUT_NOT_SHOWN.formatted(about);
    } else if (about != null && !nodeIris.isAnchor(about)) {
      reason = NOT_AN_ASSET.formatted(about);
    } else if (corrects != null && !memories.contains(corrects)) {
      reason = CORRECTS_NOT_SHOWN.formatted(corrects);
    } else if (!hasEvidence(candidate)) {
      reason = NO_EVIDENCE;
    }
    return Optional.ofNullable(reason);
  }

  private static boolean hasEvidence(final LearnedCandidate candidate) {
    final LearnedCandidate.Evidence evidence = candidate.evidence();
    return evidence != null
        && evidence.source() != null
        && evidence.quote() != null
        && !evidence.quote().isBlank();
  }

  private static LearnedOutcome written(
      final LearnedCandidate candidate, final MemoryWriter.Written written) {
    return switch (written) {
      case MemoryWriter.Written.Saved saved -> LearnedOutcome.saved(candidate, saved.memory());
      case MemoryWriter.Written.Repeat repeat -> LearnedOutcome.repeat(
          candidate, REPEAT.formatted(repeat.memory(), repeat.title()), repeat.memory());
      case MemoryWriter.Written.Refused refused -> LearnedOutcome.notSaved(
          candidate, NOT_SAVED + refused.reason());
    };
  }

  /** Every memory a card, a listing or an earlier final step of the run showed or saved. */
  Set<String> shownMemories() {
    final Set<String> shown = new LinkedHashSet<>();
    served().filter(TurnLearning::isMemory).map(RunStep.Node::id).forEach(shown::add);
    saved().forEach(outcome -> shown.add(outcome.saved().id()));
    return shown;
  }

  private Set<String> shownCards() {
    final Set<String> shown = new LinkedHashSet<>();
    served().filter(node -> !isMemory(node)).map(RunStep.Node::id).forEach(shown::add);
    return shown;
  }

  /** The memories this run saved, by the node each is about, newest last. */
  Map<String, List<MemoryEntry>> savedByAnchor() {
    final Map<String, List<MemoryEntry>> byAnchor = new LinkedHashMap<>();
    saved()
        .filter(outcome -> outcome.about() != null)
        .forEach(
            outcome ->
                byAnchor
                    .computeIfAbsent(outcome.about(), ignored -> new ArrayList<>())
                    .add(outcome.saved()));
    return byAnchor;
  }

  private Stream<LearnedOutcome> saved() {
    return steps.stream()
        .flatMap(step -> step.learned().stream())
        .filter(outcome -> outcome.saved() != null);
  }

  private Stream<RunStep.Node> served() {
    return steps.stream()
        .filter(step -> step.operation() != Operation.CITE)
        .flatMap(step -> step.nodes().stream());
  }

  private static boolean isMemory(final RunStep.Node node) {
    return RunSteps.MEMORY_TYPE.equals(node.entityType());
  }
}
