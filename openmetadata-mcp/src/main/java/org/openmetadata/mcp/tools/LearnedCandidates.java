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

package org.openmetadata.mcp.tools;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.openmetadata.service.rdf.RdfIriValidator;
import org.openmetadata.service.rdf.explore.EvidenceSource;
import org.openmetadata.service.rdf.explore.LearnedCandidate;
import org.openmetadata.service.rdf.explore.LearnedKind;

/**
 * The {@code learned} argument of {@code cite}: at most three candidates, each in the shape and
 * within the sizes of the AskCollate capture's candidates. A malformed one is a correction naming
 * the field; whether it is kept is decided after, candidate by candidate.
 */
final class LearnedCandidates {

  static final int MAX_CANDIDATES = 3;
  static final int MAX_TITLE = 80;
  static final int MAX_QUESTION = 300;
  static final int MAX_ANSWER = 600;

  private static final String LEARNED = "learned";
  private static final String TITLE = "title";
  private static final String QUESTION = "question";
  private static final String ANSWER = "answer";
  private static final String KIND = "kind";
  private static final String ABOUT = "about";
  private static final String EVIDENCE = "evidence";
  private static final String CORRECTS = "corrects";
  private static final String SOURCE = "source";
  private static final String QUOTE = "quote";
  private static final String TOOL = "tool";

  private LearnedCandidates() {}

  static List<LearnedCandidate> parse(final Object value) {
    final List<?> entries = value instanceof List<?> list ? list : List.of();
    if (entries.size() > MAX_CANDIDATES) {
      throw new IllegalArgumentException(
          "'learned' takes at most %d candidates per cite; got %d. Keep the ones most worth keeping."
              .formatted(MAX_CANDIDATES, entries.size()));
    }
    return entries.stream().map(LearnedCandidates::candidate).toList();
  }

  private static LearnedCandidate candidate(final Object entry) {
    final Map<?, ?> fields = entry instanceof Map<?, ?> map ? map : Map.of();
    return new LearnedCandidate(
        text(fields, TITLE, MAX_TITLE),
        text(fields, QUESTION, MAX_QUESTION),
        text(fields, ANSWER, MAX_ANSWER),
        kind(fields.get(KIND)),
        node(fields, ABOUT),
        evidence(fields.get(EVIDENCE)),
        node(fields, CORRECTS));
  }

  private static String text(final Map<?, ?> fields, final String name, final int max) {
    final String value = fields.get(name) instanceof String text ? text.strip() : "";
    if (value.isEmpty() || value.length() > max) {
      throw new IllegalArgumentException(
          "'learned' entries need '%s', a text of at most %d characters; got %d"
              .formatted(name, max, value.length()));
    }
    return value;
  }

  private static LearnedKind kind(final Object value) {
    return LearnedKind.fromValue(value instanceof String text ? text : null)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "'learned' entries need 'kind': correction, definition, preference or learning; got '%s'"
                        .formatted(value)));
  }

  private static String node(final Map<?, ?> fields, final String name) {
    final Object value = fields.get(name);
    final boolean absent = !(value instanceof String text) || text.isBlank();
    return absent
        ? null
        : Optional.ofNullable(RdfIriValidator.validateEntityIri((String) value))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "'learned' '%s' must be the 'id' of a card or memory this run showed; got '%s'"
                            .formatted(name, value)));
  }

  /** Evidence is optional here: a candidate without it is reported as not saved, not refused. */
  private static LearnedCandidate.Evidence evidence(final Object value) {
    final Map<?, ?> fields = value instanceof Map<?, ?> map ? map : null;
    return fields == null
        ? null
        : new LearnedCandidate.Evidence(
            EvidenceSource.fromValue(string(fields.get(SOURCE))).orElse(null),
            string(fields.get(QUOTE)),
            string(fields.get(TOOL)));
  }

  private static String string(final Object value) {
    return value instanceof String text && !text.isBlank() ? text : null;
  }
}
