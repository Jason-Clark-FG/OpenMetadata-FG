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
import org.openmetadata.schema.api.context.AnswerCitation;
import org.openmetadata.schema.api.context.AnswerConceptType;
import org.openmetadata.schema.api.context.AnswerExecutor;
import org.openmetadata.schema.api.context.AppliedHarness;
import org.openmetadata.schema.api.context.AppliedHarnesses;
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
  void aBareResultValueStandsForTheResultAndBreakdownNumbersMayArriveAsText() {
    final FinishAnswer bare =
        FinishAnswerTool.parse(Map.of("question", "q", "answer", "a", "result", "$48.2M"));
    assertEquals("$48.2M", bare.getResult().getValue());

    final FinishAnswer rows =
        FinishAnswerTool.parse(
            Map.of(
                "question",
                "q",
                "answer",
                "a",
                "result",
                Map.of(
                    "value",
                    "$48.2M",
                    "breakdown",
                    List.of(Map.of("label", "NA", "value", "$21.6M", "share", "0.5")))));
    assertEquals(0.5, rows.getResult().getBreakdown().get(0).getShare());
  }

  @Test
  void theCitationNamesThePersonaHarnessElseTheCompanyDefault() {
    final AppliedHarness persona =
        new AppliedHarness().withDisplayName("Business Analyst").withVersion(0.6);
    final AppliedHarness company =
        new AppliedHarness().withDisplayName("Anything the admin called it").withVersion(1.2);

    final AnswerCitation withPersona = new AnswerCitation();
    FinishAnswerTool.labelWithHarness(
        withPersona, new AppliedHarnesses().withDefault(company).withPersona(persona));
    final AnswerCitation withoutPersona = new AnswerCitation();
    FinishAnswerTool.labelWithHarness(withoutPersona, new AppliedHarnesses().withDefault(company));
    final AnswerCitation unknown = new AnswerCitation();
    FinishAnswerTool.labelWithHarness(unknown, null);

    assertEquals("Business Analyst", withPersona.getHarnessName());
    assertEquals(0.6, withPersona.getHarnessVersion());
    assertEquals("Company default", withoutPersona.getHarnessName());
    assertEquals(1.2, withoutPersona.getHarnessVersion());
    assertNull(unknown.getHarnessName());
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
