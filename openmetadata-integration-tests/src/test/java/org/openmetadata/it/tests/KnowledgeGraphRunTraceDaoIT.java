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

package org.openmetadata.it.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openmetadata.it.util.SdkClients;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.RdfInfraDAOs.RdfExplorationRunDAO;
import org.openmetadata.service.rdf.explore.Direction;
import org.openmetadata.service.rdf.explore.Operation;
import org.openmetadata.service.rdf.explore.RunStep;
import org.openmetadata.service.rdf.explore.SqlRunTraceStore;

/**
 * The knowledge-graph run trace table as each database engine runs it: the 2.1.0 migration created
 * it, and the DAO's insert, ordered listing, expiry cutoff and purge work on MySQL and Postgres.
 */
class KnowledgeGraphRunTraceDaoIT {

  private static final String TABLE_IRI = "https://open-metadata.org/entity/table/";

  @BeforeAll
  static void boot() {
    SdkClients.adminClient();
  }

  private static RdfExplorationRunDAO dao() {
    return Entity.getCollectionDAO().rdfExplorationRunDAO();
  }

  private static String step(final int number) {
    return "{\"n\":" + number + "}";
  }

  private static List<Integer> numbers(final List<String> steps) {
    return steps.stream().map(json -> JsonUtils.readTree(json).get("n").asInt()).toList();
  }

  @Test
  void stepsOfARunComeBackInInsertionOrderEvenWithinOneMillisecond() {
    final String run = UUID.randomUUID().toString();
    final long now = System.currentTimeMillis();
    dao().insert(run, now, step(1));
    dao().insert(run, now, step(2));
    dao().insert(UUID.randomUUID().toString(), now, step(9));
    dao().insert(run, now, step(3));

    assertThat(numbers(dao().listSteps(run, now))).containsExactly(1, 2, 3);
  }

  @Test
  void aStepRecordedBeforeTheCutoffIsNotListed() {
    final String run = UUID.randomUUID().toString();
    final long now = System.currentTimeMillis();
    dao().insert(run, now - 1_000, step(1));

    assertThat(dao().listSteps(run, now)).isEmpty();
    assertThat(numbers(dao().listSteps(run, now - 2_000))).containsExactly(1);
  }

  @Test
  void purgeDeletesStepsOlderThanTheCutoffAndKeepsTheRest() {
    final String run = UUID.randomUUID().toString();
    final long ancient = 1_000L;
    final long now = System.currentTimeMillis();
    dao().insert(run, ancient, step(1));
    dao().insert(run, now, step(2));

    dao().deleteBefore(ancient + 1);

    assertThat(numbers(dao().listSteps(run, 0))).containsExactly(2);
  }

  @Test
  void theStoreRoundTripsAStepThroughTheJsonColumn() {
    final UUID run = UUID.randomUUID();
    final SqlRunTraceStore store = new SqlRunTraceStore(dao(), Clock.systemUTC());
    final RunStep step =
        new RunStep(
            Operation.FIND,
            System.currentTimeMillis(),
            "admin",
            new RunStep.Input("orders", List.of(), List.of(), null),
            List.of(
                new RunStep.Node(
                    TABLE_IRI + UUID.randomUUID(),
                    "orders",
                    "table",
                    List.of(new RunStep.Offer("om:hasOwner", Direction.OUTGOING, 2)))),
            List.of());

    store.append(run, step);

    assertThat(store.steps(run)).containsExactly(step);
  }
}
