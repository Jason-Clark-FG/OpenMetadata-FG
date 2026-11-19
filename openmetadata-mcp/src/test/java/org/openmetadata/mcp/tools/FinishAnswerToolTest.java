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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.AnswerConceptType;
import org.openmetadata.schema.api.context.AnswerExecutor;
import org.openmetadata.schema.api.context.FinishAnswer;

class FinishAnswerToolTest {
  @Test
  void aBareNameStandsForTheObjectItWouldBe() {
    final FinishAnswer request =
        FinishAnswerTool.parse(
            Map.of(
                "question",
                "What was last month's ARR?",
                "answer",
                "12.4M",
                "concepts",
                List.of(
                    "finance.arr", Map.of("phrase", "ARR", "entityType", "metric", "fqn", "m.arr")),
                "tables",
                List.of("svc.db.sch.arr_daily"),
                "timeWindow",
                "last month",
                "execution",
                "none"));

    assertEquals("finance.arr", request.getConcepts().get(0).getFqn());
    assertEquals(AnswerConceptType.METRIC, request.getConcepts().get(1).getEntityType());
    assertEquals("svc.db.sch.arr_daily", request.getTables().get(0).getFqn());
    assertEquals("last month", request.getTimeWindow().getPhrase());
    assertEquals(AnswerExecutor.NONE, request.getExecution().getExecutor());
  }

  @Test
  void keysTheSchemaDoesNotHaveAreDroppedAndNumbersMayArriveAsText() {
    final FinishAnswer request =
        FinishAnswerTool.parse(
            Map.of(
                "question",
                "q",
                "answer",
                "a",
                "mcp_client_name",
                "codex",
                "confidence",
                0.9,
                "execution",
                Map.of("executor", "hostConnector", "rowCount", "7")));

    assertEquals(7, request.getExecution().getRowCount());
  }

  @Test
  void aTimeWindowWithNeitherPhraseNorDatesIsNoWindow() {
    final FinishAnswer request =
        FinishAnswerTool.parse(Map.of("question", "q", "answer", "a", "timeWindow", Map.of()));

    assertNull(request.getTimeWindow());
  }

  @Test
  void theQuestionAndTheAnswerAreRequired() {
    assertThrows(
        IllegalArgumentException.class, () -> FinishAnswerTool.parse(Map.of("answer", "a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> FinishAnswerTool.parse(Map.of("question", "q", "answer", " ")));
  }
}
