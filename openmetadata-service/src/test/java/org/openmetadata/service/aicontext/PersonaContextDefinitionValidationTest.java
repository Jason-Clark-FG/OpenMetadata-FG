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

package org.openmetadata.service.aicontext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.type.personaContext.ContextSection;
import org.openmetadata.service.Entity;

class PersonaContextDefinitionValidationTest {
  @Test
  void anExistingRuleIdTakesPriorityOverTheNameFallbackWhenReactivating() {
    ContextRule disabled =
        new ContextRule()
            .withId(UUID.randomUUID())
            .withName("Disabled")
            .withEntityType(Entity.TABLE)
            .withQueryFilter("{}")
            .withEnabled(false);
    ContextRule other =
        new ContextRule()
            .withId(UUID.randomUUID())
            .withName("Other")
            .withEntityType(Entity.TABLE)
            .withQueryFilter("{}")
            .withEnabled(true);
    ContextRule requested =
        new ContextRule()
            .withId(disabled.getId())
            .withName("Other")
            .withEntityType(Entity.TABLE)
            .withQueryFilter("{}")
            .withEnabled(true);
    assertTrue(
        PersonaContextDefinitionValidation.requiresRuleValidation(
            requested, List.of(disabled, other)));
  }

  @Test
  void anUnchangedRuleIsMatchedByItsUniqueName() {
    ContextRule stored =
        new ContextRule()
            .withId(UUID.randomUUID())
            .withName("Tables")
            .withEntityType(Entity.TABLE)
            .withQueryFilter("{\"term\":{\"name\":\"orders\"}}")
            .withEnabled(true);
    ContextRule requested =
        new ContextRule()
            .withName("tables")
            .withEntityType(Entity.TABLE)
            .withQueryFilter(stored.getQueryFilter())
            .withEnabled(true);
    PersonaContextDefinitionValidation.validate(
        new PersonaContextDefinition().withRules(List.of(requested)));

    assertFalse(
        PersonaContextDefinitionValidation.requiresRuleValidation(requested, List.of(stored)));
    stored.setEnabled(false);
    assertTrue(
        PersonaContextDefinitionValidation.requiresRuleValidation(requested, List.of(stored)));
    stored.setEnabled(true);
    requested.setQueryFilter("{\"regexp\":{\"name\":\"[shop\"}}");
    assertTrue(
        PersonaContextDefinitionValidation.requiresRuleValidation(requested, List.of(stored)));
    requested.setQueryFilter(stored.getQueryFilter());
    requested.setName("New rule");
    assertTrue(
        PersonaContextDefinitionValidation.requiresRuleValidation(requested, List.of(stored)));
  }

  @Test
  void disablingPreservesRecoveryButReenablingRevalidatesTheFilter() {
    UUID id = UUID.randomUUID();
    ContextRule enabled =
        new ContextRule()
            .withId(id)
            .withName("Tables")
            .withEntityType(Entity.TABLE)
            .withQueryFilter("{\"regexp\":{\"name\":\"[shop\"}}")
            .withEnabled(true);
    ContextRule disabled =
        new ContextRule()
            .withId(id)
            .withName("Tables")
            .withEntityType(Entity.TABLE)
            .withQueryFilter(enabled.getQueryFilter())
            .withEnabled(false);
    assertFalse(
        PersonaContextDefinitionValidation.requiresRuleValidation(disabled, List.of(enabled)));
    assertFalse(
        PersonaContextDefinitionValidation.requiresRuleValidation(enabled, List.of(enabled)));
    assertTrue(
        PersonaContextDefinitionValidation.requiresRuleValidation(enabled, List.of(disabled)));
    assertTrue(PersonaContextDefinitionValidation.requiresRuleValidation(disabled, List.of()));
  }

  private static final List<String> SUPPORTED_ENTITY_TYPES =
      List.of(
          Entity.TABLE,
          Entity.TOPIC,
          Entity.DASHBOARD,
          Entity.CHART,
          Entity.DASHBOARD_DATA_MODEL,
          Entity.PIPELINE,
          Entity.MLMODEL,
          Entity.CONTAINER,
          Entity.DATABASE,
          Entity.DATABASE_SCHEMA,
          Entity.STORED_PROCEDURE,
          Entity.SEARCH_INDEX,
          Entity.API_COLLECTION,
          Entity.API_ENDPOINT,
          Entity.DATA_PRODUCT,
          Entity.GLOSSARY_TERM,
          Entity.PAGE,
          Entity.METRIC);

  @Test
  void acceptsEverySupportedPersonaContextEntityType() {
    for (String entityType : SUPPORTED_ENTITY_TYPES) {
      PersonaContextDefinition definition =
          new PersonaContextDefinition()
              .withRules(
                  List.of(new ContextRule().withName(entityType).withEntityType(entityType)));

      assertDoesNotThrow(() -> PersonaContextDefinitionValidation.validate(definition), entityType);
    }
  }

  @Test
  void rejectsUnsupportedPersonaContextEntityTypes() {
    PersonaContextDefinition definition =
        new PersonaContextDefinition()
            .withRules(List.of(new ContextRule().withName("Users").withEntityType(Entity.USER)));

    assertThrows(
        IllegalArgumentException.class,
        () -> PersonaContextDefinitionValidation.validate(definition));
  }

  @Test
  void appliesDefaultsWhenGeneratedRuleSectionsAreEmpty() {
    ContextRule rule =
        new ContextRule().withName("Tables").withEntityType(Entity.TABLE).withSections(Set.of());
    PersonaContextDefinition definition = new PersonaContextDefinition().withRules(List.of(rule));

    PersonaContextDefinitionValidation.validate(definition);

    assertEquals(200, rule.getMaxAssets());
    assertTrue(rule.getSections().contains(ContextSection.JOINS));
    assertTrue(rule.getSections().contains(ContextSection.ARTICLES));
    assertTrue(rule.getSections().contains(ContextSection.METRICS));
    assertFalse(rule.getSections().contains(ContextSection.LINEAGE));
  }

  @Test
  void forcesKnowledgeRulesToFullyRendered() {
    ContextRule rule =
        new ContextRule()
            .withName("Glossary")
            .withEntityType(Entity.GLOSSARY_TERM)
            .withFullyRendered(false);
    PersonaContextDefinition definition = new PersonaContextDefinition().withRules(List.of(rule));

    PersonaContextDefinitionValidation.validate(definition);

    assertTrue(rule.getFullyRendered());
  }

  @Test
  void rejectsCaseInsensitiveDuplicateRuleNames() {
    PersonaContextDefinition definition =
        new PersonaContextDefinition()
            .withRules(
                List.of(
                    new ContextRule().withName("Tables").withEntityType(Entity.TABLE),
                    new ContextRule().withName(" tables ").withEntityType(Entity.TABLE)));

    assertThrows(
        IllegalArgumentException.class,
        () -> PersonaContextDefinitionValidation.validate(definition));
  }

  @Test
  void validatesNumericBounds() {
    PersonaContextDefinition definition =
        new PersonaContextDefinition().withCharacterBudget(0).withCacheTtlMinutes(30);

    assertThrows(
        IllegalArgumentException.class,
        () -> PersonaContextDefinitionValidation.validate(definition));
  }

  @Test
  void trimsThePromptAndTreatsBlankAsUnset() {
    PersonaContextDefinition written =
        new PersonaContextDefinition().withPrompt("  You assist finance analysts.  \n");
    PersonaContextDefinition blank = new PersonaContextDefinition().withPrompt(" \n\t ");

    PersonaContextDefinitionValidation.validate(written);
    PersonaContextDefinitionValidation.validate(blank);

    assertEquals("You assist finance analysts.", written.getPrompt());
    assertNull(blank.getPrompt());
  }

  @Test
  void rejectsAPromptOverTheLimit() {
    // A draft is not checked against the schema's maxLength, so the validator enforces it.
    PersonaContextDefinition definition =
        new PersonaContextDefinition().withPrompt("x".repeat(8_001));

    assertThrows(
        IllegalArgumentException.class,
        () -> PersonaContextDefinitionValidation.validate(definition));
  }
}
