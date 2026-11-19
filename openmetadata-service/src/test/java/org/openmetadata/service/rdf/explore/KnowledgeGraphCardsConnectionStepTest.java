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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.utils.JsonUtils;

class KnowledgeGraphCardsConnectionStepTest {

  @Test
  void aCallWithEmptyArgumentsIsACallAndSurvivesTheStore() {
    final RunStep.Input stored =
        roundTrip(RunStep.Input.connection("x", "list_everything", Map.of(), "COMPLETED"));

    assertThat(stored.connectionForm()).isEqualTo(RunStep.ConnectionForm.CALL);
    assertThat(stored.arguments()).isEqualTo(Map.of());
    assertThat(lineOf(stored))
        .contains("x list_everything, called as the caller with {}")
        .doesNotContain("its arguments", "failed");
  }

  @Test
  void eachFormIsRecordedAndAFailedCallSaysSo() {
    final RunStep.Input listing = roundTrip(RunStep.Input.connection("x", null, null, null));
    final RunStep.Input schema = roundTrip(RunStep.Input.connection("x", "t", null, null));
    final RunStep.Input failed =
        roundTrip(RunStep.Input.connection("x", "t", Map.of("q", "a"), "FAILED"));

    assertThat(listing.connectionForm()).isEqualTo(RunStep.ConnectionForm.TOOLS);
    assertThat(schema.connectionForm()).isEqualTo(RunStep.ConnectionForm.SCHEMA);
    assertThat(schema.status()).isNull();
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(lineOf(listing)).startsWith("x: its tools");
    assertThat(lineOf(schema)).startsWith("x t: its arguments");
    assertThat(lineOf(failed)).startsWith("x t, failed, called as the caller with {\"q\":\"a\"}");
  }

  @Test
  void aStepStoredBeforeTheFormWasRecordedReadsAsBefore() {
    final RunStep.Input old =
        JsonUtils.readValue("{\"connection\":\"x\",\"tool\":\"t\"}", RunStep.Input.class);

    assertThat(old.connectionForm()).isEqualTo(RunStep.ConnectionForm.SCHEMA);
  }

  private static RunStep.Input roundTrip(final RunStep.Input input) {
    return JsonUtils.readValue(JsonUtils.pojoToJson(input), RunStep.Input.class);
  }

  private static String lineOf(final RunStep.Input input) {
    final RunTrace trace =
        new RunTrace(
            UUID.randomUUID(),
            List.of(),
            List.of(),
            List.of(new TraceStep(1, Operation.DELEGATE, 0L, input, List.of())));
    return KnowledgeGraphCards.ofRun(Operation.DELEGATE, trace, List.of())
        .steps()
        .getFirst()
        .line();
  }
}
