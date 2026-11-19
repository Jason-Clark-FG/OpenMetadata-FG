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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.entity.context.AgentHarnessMemory;
import org.openmetadata.schema.type.AIContext;
import org.openmetadata.schema.type.PersonaContext;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.type.personaContext.RuleResult;
import org.openmetadata.schema.type.personaContext.SearchScope;
import org.openmetadata.schema.type.personaContext.SearchScopeRule;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.harness.CompileSelector.Document;
import org.openmetadata.service.aicontext.harness.HarnessDocuments.Sources;

class HarnessDocumentsTest {
  @Test
  void aRuleMaterializedGlossaryTermIsHeadedByItsName() {
    final ContextRule rule =
        new ContextRule()
            .withName("terms")
            .withEntityType(Entity.GLOSSARY_TERM)
            .withEnabled(true)
            .withAlwaysInContext(true)
            .withFilteredInSearch(false);
    final AIContext term =
        new AIContext()
            .withId(UUID.randomUUID())
            .withEntityType(Entity.GLOSSARY_TERM)
            .withFullyQualifiedName("Finance.ARR")
            .withName("ARR")
            .withDescription("Annual Recurring Revenue");
    final RuleResult result =
        new RuleResult()
            .withRuleName("terms")
            .withEntityType(Entity.GLOSSARY_TERM)
            .withEntities(List.of(term));

    final Document document =
        HarnessDocuments.from(
            sources(
                new PersonaContextDefinition().withEnabled(true).withRules(List.of(rule)),
                new PersonaContext().withRules(List.of(result))));

    assertEquals(
        "### Glossary: ARR\n`Finance.ARR`\nAnnual Recurring Revenue",
        document.knowledge().get(0).text());
  }

  @Test
  void filteredInSearchRulesAddASearchScopeNoteAfterThePrompt() {
    final ContextRule scoped =
        new ContextRule()
            .withName("sales-tables")
            .withEntityType(Entity.TABLE)
            .withEnabled(true)
            .withFilteredInSearch(true);
    final ContextRule preloaded =
        new ContextRule()
            .withName("terms")
            .withEntityType(Entity.GLOSSARY_TERM)
            .withEnabled(true)
            .withFilteredInSearch(false);

    final Document document =
        HarnessDocuments.from(
            sources(
                new PersonaContextDefinition()
                    .withEnabled(true)
                    .withPrompt("You serve analysts.")
                    .withRules(List.of(scoped, preloaded)),
                new PersonaContext()
                    .withSearchScope(
                        new SearchScope()
                            .withEntityTypes(Set.of("table"))
                            .withRules(
                                List.of(
                                    new SearchScopeRule()
                                        .withRuleName("persona/sales-tables")
                                        .withEntityType("table"))))));

    assertEquals(
        List.of("prompt", "searchScope", "answerCards"),
        document.fixed().stream().map(ContextItem::key).toList());
    final String note = document.fixed().get(1).text();
    assertTrue(
        note.startsWith("## Search scope\n\nYour administrator configured default search rules"));
    assertTrue(note.endsWith("- sales-tables — table (every entity of this type)"));
  }

  @Test
  void noSearchScopeNoteWhenNoRuleIsFilteredInSearch() {
    final Document document =
        HarnessDocuments.from(
            sources(
                new PersonaContextDefinition()
                    .withEnabled(true)
                    .withRules(
                        List.of(
                            new ContextRule()
                                .withName("terms")
                                .withEntityType(Entity.GLOSSARY_TERM)
                                .withEnabled(true)
                                .withFilteredInSearch(false))),
                new PersonaContext()));

    assertEquals(
        List.of(HarnessDocuments.ANSWER_RULE),
        document.fixed().stream().map(ContextItem::text).toList());
    assertTrue(HarnessDocuments.ANSWER_RULE.startsWith("When you answer a question about data"));
    assertTrue(HarnessDocuments.ANSWER_RULE.contains("finish_answer"));
  }

  @Test
  void theRememberInstructionIsSentOnlyWhenCaptureIsOn() {
    final String instruction =
        "## Remembering\n\nWhen the user corrects you or states a lasting fact about the data (a"
            + " definition, which table to use, a caveat), call remember with the fact and the"
            + " assets it is about.";
    final AgentHarnessSpec capturing =
        new AgentHarnessSpec().withMemory(new AgentHarnessMemory().withCapture(true));
    final AgentHarnessSpec silent =
        new AgentHarnessSpec().withMemory(new AgentHarnessMemory().withCapture(false));
    final PersonaContextDefinition definition = new PersonaContextDefinition().withEnabled(true);

    final List<String> on =
        HarnessDocuments.from(new Sources(capturing, capturing, definition, new PersonaContext()))
            .fixed()
            .stream()
            .filter(item -> ContextItem.MEMORY.equals(item.kind()))
            .map(ContextItem::text)
            .toList();
    final List<String> off =
        HarnessDocuments.from(new Sources(silent, silent, definition, new PersonaContext()))
            .fixed()
            .stream()
            .filter(item -> ContextItem.MEMORY.equals(item.kind()))
            .map(ContextItem::text)
            .toList();

    assertEquals(List.of(instruction), on);
    assertEquals(List.of(), off);
  }

  private static Sources sources(
      final PersonaContextDefinition definition, final PersonaContext context) {
    final AgentHarnessSpec spec = new AgentHarnessSpec();
    return new Sources(spec, spec, definition, context);
  }
}
