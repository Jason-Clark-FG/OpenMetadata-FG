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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmetadata.schema.entity.context.ContextMemory;

class AgentMemoryDuplicatesTest {
  @Test
  void caseSpacingAndPunctuationDoNotMakeANewFact() {
    final ContextMemory stored = new ContextMemory().withAnswer("ARR excludes churned customers.");

    assertTrue(AgentMemoryDuplicates.restates(stored, "  arr EXCLUDES churned   customers "));
  }

  @Test
  void aDifferentNumberOrWordIsANewFact() {
    final ContextMemory stored = new ContextMemory().withAnswer("Use analytics.fct_orders");

    assertFalse(AgentMemoryDuplicates.restates(stored, "Use analytics.fct_orders_v2"));
    assertFalse(AgentMemoryDuplicates.restates(stored, "Do not use analytics.fct_orders"));
  }

  @Test
  void normalizingKeepsLettersAndDigitsOfAnyScript() {
    assertEquals("収益 2025 q3", AgentMemoryDuplicates.normalize("収益 (2025), Q3!"));
    assertEquals("", AgentMemoryDuplicates.normalize(null));
  }
}
