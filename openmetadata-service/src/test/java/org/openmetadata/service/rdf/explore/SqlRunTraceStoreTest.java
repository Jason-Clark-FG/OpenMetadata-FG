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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.jdbi3.RdfInfraDAOs.RdfExplorationRunDAO;

class SqlRunTraceStoreTest {

  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

  private final RowsDao rows = new RowsDao();
  private final UUID run = UUID.randomUUID();

  private static SqlRunTraceStore storeAt(final RowsDao rows, final Instant instant) {
    return new SqlRunTraceStore(rows, Clock.fixed(instant, ZoneOffset.UTC));
  }

  private static RunStep step(final String question) {
    return new RunStep(
        Operation.FIND,
        NOW.toEpochMilli(),
        "analyst",
        new RunStep.Input(question, List.of(), List.of(), null),
        List.of(
            new RunStep.Node(
                "https://open-metadata.org/entity/table/1", "orders", "table", List.of())),
        List.of());
  }

  @Test
  void stepsComeBackInTheOrderTheyWereRecorded() {
    final SqlRunTraceStore store = storeAt(rows, NOW);

    store.append(run, step("first"));
    store.append(run, step("second"));

    assertThat(store.steps(run))
        .extracting(step -> step.input().question())
        .containsExactly("first", "second");
  }

  @Test
  void aStepOlderThanTheRetentionIsNotReturned() {
    final SqlRunTraceStore store = storeAt(rows, NOW);
    store.append(run, step("old"));

    final SqlRunTraceStore later =
        storeAt(rows, NOW.plus(RunTraceStore.RETENTION).plus(Duration.ofSeconds(1)));

    assertThat(store.steps(run)).hasSize(1);
    assertThat(later.steps(run)).isEmpty();
  }

  @Test
  void recordingAStepPurgesEveryExpiredStep() {
    storeAt(rows, NOW).append(UUID.randomUUID(), step("old"));

    storeAt(rows, NOW.plus(RunTraceStore.RETENTION).plus(Duration.ofSeconds(1)))
        .append(run, step("new"));

    assertThat(rows.rows).extracting(Row::runId).containsExactly(run.toString());
  }

  @Test
  void aStepWithinTheRetentionIsKept() {
    storeAt(rows, NOW).append(run, step("recent"));

    final SqlRunTraceStore later = storeAt(rows, NOW.plus(RunTraceStore.RETENTION));

    assertThat(later.steps(run)).hasSize(1);
  }

  private record Row(String runId, long createdAt, String json) {}

  /** Applies the DAO's SQL semantics to a list: insertion order stands for the id order. */
  private static final class RowsDao implements RdfExplorationRunDAO {
    private final List<Row> rows = new ArrayList<>();

    @Override
    public void insert(final String runId, final long createdAt, final String json) {
      rows.add(new Row(runId, createdAt, json));
    }

    @Override
    public List<String> listSteps(final String runId, final long notBefore) {
      return rows.stream()
          .filter(row -> row.runId().equals(runId) && row.createdAt() >= notBefore)
          .map(Row::json)
          .toList();
    }

    @Override
    public int deleteBefore(final long cutoff) {
      final int before = rows.size();
      rows.removeIf(row -> row.createdAt() < cutoff);
      return before - rows.size();
    }
  }
}
