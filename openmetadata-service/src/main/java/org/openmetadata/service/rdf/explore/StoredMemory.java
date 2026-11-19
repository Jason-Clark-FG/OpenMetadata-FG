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

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.jena.query.QuerySolution;

/** A memory as one aggregated graph row gives it, before it is shown. */
record StoredMemory(
    String iri,
    Set<String> anchors,
    String title,
    String question,
    int questionLength,
    String answer,
    int answerLength,
    String stage,
    Long changedAt,
    boolean pinned,
    int hits) {

  static final String MEMORY = "memory";
  static final String ANCHORS = "anchors";
  static final String TITLE = "title";
  static final String QUESTION = "question";
  static final String QUESTION_LENGTH = "questionLength";
  static final String ANSWER = "answer";
  static final String ANSWER_LENGTH = "answerLength";
  static final String STAGE = "stage";
  static final String CHANGED = "changed";
  static final String MODIFIED = "modified";
  static final String PINNED = "pinned";
  static final String HITS = "hits";

  /** Stages still in use; a memory in any other stage was retired and is left out. */
  static final List<String> SHOWN_STAGES = List.of("Approved", "Unprocessed", "Draft");

  private static final Set<String> AWAITING_REVIEW = Set.of("Unprocessed", "Draft");
  private static final String JOIN = " → ";
  private static final String ANCHOR_SEPARATOR = " ";

  static final Comparator<StoredMemory> NEWEST_FIRST =
      Comparator.comparing(
              StoredMemory::changedAt, Comparator.nullsLast(Comparator.<Long>reverseOrder()))
          .thenComparing(StoredMemory::iri);

  static final Comparator<StoredMemory> BEST_MATCH_FIRST =
      Comparator.comparingInt((StoredMemory memory) -> -memory.hits()).thenComparing(NEWEST_FIRST);

  static final Comparator<StoredMemory> PINNED_FIRST =
      Comparator.comparing((StoredMemory memory) -> !memory.pinned()).thenComparing(NEWEST_FIRST);

  static StoredMemory of(final QuerySolution row) {
    return new StoredMemory(
        SparqlRows.iri(row, MEMORY),
        SparqlRows.text(row, ANCHORS).map(StoredMemory::anchorsOf).orElse(Set.of()),
        SparqlRows.text(row, TITLE).orElse(null),
        SparqlRows.text(row, QUESTION).orElse(null),
        number(row, QUESTION_LENGTH),
        SparqlRows.text(row, ANSWER).orElse(null),
        number(row, ANSWER_LENGTH),
        SparqlRows.text(row, STAGE).orElse(null),
        changedAtOf(row),
        SparqlRows.text(row, PINNED).map(Boolean::parseBoolean).orElse(false),
        number(row, HITS));
  }

  /** The entry, its statement cut like other long values. */
  MemoryEntry cut() {
    final String statement = statement();
    final int length = fullLength();
    final boolean cut = length > CardValue.VALUE_LIMIT;
    return entry(cut ? CardFacts.truncate(statement) : statement, cut ? length : null);
  }

  /** The whole entry, or none when the graph read did not hold all of its text. */
  Optional<MemoryEntry> whole() {
    final String statement = statement();
    return statement.length() == fullLength()
        ? Optional.of(entry(statement, null))
        : Optional.empty();
  }

  private MemoryEntry entry(final String statement, final Integer length) {
    return new MemoryEntry(
        iri,
        title,
        statement,
        length,
        stage,
        AWAITING_REVIEW.contains(stage) ? Boolean.TRUE : null,
        changedAt == null ? null : Instant.ofEpochMilli(changedAt).toString());
  }

  private String statement() {
    return Stream.of(question, answer)
        .filter(text -> text != null && !text.isBlank())
        .collect(Collectors.joining(JOIN));
  }

  private int fullLength() {
    final int parts = (questionLength > 0 ? 1 : 0) + (answerLength > 0 ? 1 : 0);
    return questionLength + answerLength + (parts > 1 ? JOIN.length() : 0);
  }

  private static Set<String> anchorsOf(final String joined) {
    return Stream.of(joined.split(ANCHOR_SEPARATOR))
        .filter(anchor -> !anchor.isBlank())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static int number(final QuerySolution row, final String variable) {
    return row.contains(variable) ? row.getLiteral(variable).getInt() : 0;
  }

  /** When the content last changed; the entity's modification time for a memory without it. */
  private static Long changedAtOf(final QuerySolution row) {
    return row.contains(CHANGED)
        ? Long.valueOf(row.getLiteral(CHANGED).getLong())
        : SparqlRows.text(row, MODIFIED).map(StoredMemory::epochMillis).orElse(null);
  }

  private static Long epochMillis(final String dateTime) {
    Long millis = null;
    try {
      millis = Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(dateTime)).toEpochMilli();
    } catch (DateTimeParseException ignored) {
      // A modification time written in another form orders the memory last.
    }
    return millis;
  }
}
