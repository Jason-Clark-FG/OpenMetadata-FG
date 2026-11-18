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
package org.openmetadata.service.aicontext.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.AgentHarnessAdditions;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessGuardrail;
import org.openmetadata.schema.entity.context.AgentHarnessSkill;
import org.openmetadata.schema.entity.context.GuardrailLevel;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.personaContext.ContextRule;

class HarnessAdditionCountsTest {

  @Test
  void countsPickedItemsPerSourceAndEveryOtherRuleOnce() {
    final AgentHarness harness =
        new AgentHarness()
            .withContext(
                new PersonaContextDefinition()
                    .withRules(
                        List.of(
                            picked("glossaryTerm", "t1", "t2"),
                            picked("metric", "m1"),
                            picked("page", "a1", "a2", "a3"),
                            new ContextRule().withName("Sales").withEntityType("dashboard"))))
            .withGuardrails(List.of(new AgentHarnessGuardrail().withLevel(GuardrailLevel.BLOCK)))
            .withSkills(
                List.of(
                    new AgentHarnessSkill().withName("explain-metric").withEnabled(true),
                    new AgentHarnessSkill().withName("write-sql").withEnabled(false)));

    final AgentHarnessAdditions counts = HarnessAdditionCounts.of(harness);

    assertEquals(2, counts.getGlossaryTerms());
    assertEquals(1, counts.getMetrics());
    assertEquals(3, counts.getArticles());
    assertEquals(1, counts.getRules());
    assertEquals(1, counts.getSkills());
    assertEquals(1, counts.getGuardrails());
  }

  @Test
  void aHarnessWithNothingAddedCountsZeroEverywhere() {
    final AgentHarnessAdditions counts = HarnessAdditionCounts.of(new AgentHarness());

    assertEquals(
        new AgentHarnessAdditions()
            .withGlossaryTerms(0)
            .withMetrics(0)
            .withArticles(0)
            .withRules(0)
            .withSkills(0)
            .withGuardrails(0),
        counts);
  }

  @Test
  void aPickedRuleWithAnUnreadableFilterCountsNoItems() {
    final AgentHarness harness =
        new AgentHarness()
            .withContext(
                new PersonaContextDefinition()
                    .withRules(
                        List.of(
                            new ContextRule()
                                .withName("broken")
                                .withEntityType("metric")
                                .withPicked(true)
                                .withQueryFilter("not json"))));

    assertEquals(0, HarnessAdditionCounts.of(harness).getMetrics());
  }

  private static ContextRule picked(final String entityType, final String... ids) {
    final String quoted = String.join("\",\"", ids);
    return new ContextRule()
        .withName("Picked " + entityType)
        .withEntityType(entityType)
        .withPicked(true)
        .withQueryFilter(
            "{\"query\":{\"bool\":{\"must\":[{\"terms\":{\"id\":[\"" + quoted + "\"]}}]}}}");
  }
}
