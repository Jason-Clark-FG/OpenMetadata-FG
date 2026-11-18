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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.ws.rs.BadRequestException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.entity.context.AgentHarnessEvent;
import org.openmetadata.schema.entity.context.AgentHarnessGuardrail;
import org.openmetadata.schema.entity.context.AgentHarnessMemory;
import org.openmetadata.schema.entity.context.AgentHarnessSkill;
import org.openmetadata.schema.entity.context.AgentMemoryReview;
import org.openmetadata.schema.entity.context.AgentMemoryVisibility;
import org.openmetadata.schema.entity.context.GuardrailLevel;
import org.openmetadata.schema.entity.context.HookEvent;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.personaContext.ContextRule;

class HarnessDraftValidatorTest {
  private static final AgentHarnessSpec PUBLISHED_DEFAULT =
      HarnessSpecs.specOf(HarnessSpecs.seedDefault(1L));

  @Test
  void acceptsAPersonaSpecThatAddsAndTightensAndStampsRuleIds() {
    AgentHarnessSpec requested =
        personaSpec()
            .withGuardrails(
                List.of(guardrail(GuardrailRegistry.PREFER_CERTIFIED_TABLES, GuardrailLevel.BLOCK)))
            .withEvents(List.of(event(HookEvent.SESSION_START, true, 3000)))
            .withSkills(
                List.of(
                    new AgentHarnessSkill()
                        .withName(SkillRegistry.METRIC_DEFINITION)
                        .withEnabled(true)));
    requested
        .getContext()
        .setRules(List.of(new ContextRule().withName(" Marketing ").withEntityType("table")));

    AgentHarnessSpec validated = HarnessDraftValidator.forPersona(requested, PUBLISHED_DEFAULT);

    ContextRule rule = validated.getContext().getRules().getFirst();
    assertNotNull(rule.getId());
    assertEquals("Marketing", rule.getName());
    assertNull(requested.getContext().getRules().getFirst().getId(), "the request is not changed");
  }

  @Test
  void rejectsAGuardrailBelowTheDefaultsLevel() {
    AgentHarnessSpec requested =
        personaSpec()
            .withGuardrails(
                List.of(guardrail(GuardrailRegistry.BLOCK_DEPRECATED_ASSETS, GuardrailLevel.WARN)));

    IllegalArgumentException refusal =
        assertThrows(
            IllegalArgumentException.class,
            () -> HarnessDraftValidator.forPersona(requested, PUBLISHED_DEFAULT));

    assertEquals(
        "Guardrail blockDeprecatedAssets cannot be Warn. The default's level Block is the minimum",
        refusal.getMessage());
  }

  @Test
  void rejectsAnEventBudgetAboveTheDefaults() {
    AgentHarnessSpec requested =
        personaSpec().withEvents(List.of(event(HookEvent.PRE_TOOL_USE, true, 2001)));

    IllegalArgumentException refusal =
        assertThrows(
            IllegalArgumentException.class,
            () -> HarnessDraftValidator.forPersona(requested, PUBLISHED_DEFAULT));

    assertEquals(
        "Event PreToolUse tokenBudget 2001 exceeds the default's 2000", refusal.getMessage());
  }

  @Test
  void rejectsAPerTurnBudgetBelowTheEnabledEvents() {
    AgentHarnessSpec forDefault =
        HarnessSpecs.specOf(HarnessSpecs.seedDefault(1L)).withPerTurnTokenBudget(13_999);
    AgentHarnessSpec forPersona = personaSpec().withPerTurnTokenBudget(10_000);

    IllegalArgumentException defaultRefusal =
        assertThrows(
            IllegalArgumentException.class, () -> HarnessDraftValidator.forDefault(forDefault));
    IllegalArgumentException personaRefusal =
        assertThrows(
            IllegalArgumentException.class,
            () -> HarnessDraftValidator.forPersona(forPersona, PUBLISHED_DEFAULT));

    assertEquals(
        "perTurnTokenBudget 13999 is below the 14000 tokens of the enabled events",
        defaultRefusal.getMessage());
    assertEquals(
        "perTurnTokenBudget 10000 is below the 14000 tokens of the enabled events",
        personaRefusal.getMessage());
  }

  @Test
  void rejectsKindSpecificFieldsOnTheWrongKind() {
    AgentHarnessSpec personaWithInstructions = personaSpec().withCompanyInstructions("Be brief");
    AgentHarnessSpec defaultWithPrompt = HarnessSpecs.specOf(HarnessSpecs.seedDefault(1L));
    defaultWithPrompt.getContext().setPrompt("You serve analysts");

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessDraftValidator.forPersona(personaWithInstructions, PUBLISHED_DEFAULT));
    assertThrows(
        IllegalArgumentException.class, () -> HarnessDraftValidator.forDefault(defaultWithPrompt));
    assertEquals(
        "Be brief",
        HarnessDraftValidator.forDefault(
                HarnessSpecs.specOf(HarnessSpecs.seedDefault(1L))
                    .withCompanyInstructions("  Be brief "))
            .getCompanyInstructions());
  }

  @Test
  void rejectsInvalidRules() {
    AgentHarnessSpec unsupportedType = personaSpec();
    unsupportedType
        .getContext()
        .setRules(List.of(new ContextRule().withName("Users").withEntityType("user")));
    AgentHarnessSpec brokenFilter = personaSpec();
    brokenFilter
        .getContext()
        .setRules(
            List.of(
                new ContextRule().withName("Tables").withEntityType("table").withQueryFilter("{")));

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessDraftValidator.forPersona(unsupportedType, PUBLISHED_DEFAULT));
    assertThrows(
        BadRequestException.class,
        () -> HarnessDraftValidator.forPersona(brokenFilter, PUBLISHED_DEFAULT));
  }

  @Test
  void rejectsAPersonaRuleThatReusesAnInheritedRuleId() {
    UUID inheritedId = UUID.randomUUID();
    AgentHarnessSpec defaults = HarnessSpecs.specOf(HarnessSpecs.seedDefault(1L));
    defaults
        .getContext()
        .setRules(
            List.of(
                new ContextRule()
                    .withId(inheritedId)
                    .withName("Certified")
                    .withEntityType("table")));
    AgentHarnessSpec requested = personaSpec();
    requested
        .getContext()
        .setRules(
            List.of(
                new ContextRule().withId(inheritedId).withName("Mine").withEntityType("table")));

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessDraftValidator.forPersona(requested, defaults));
  }

  @Test
  void rejectsUnknownOrRepeatedRegistryEntriesAndNonPositiveBudgets() {
    List<AgentHarnessSpec> refused =
        List.of(
            personaSpec()
                .withGuardrails(List.of(guardrail("noSuchGuardrail", GuardrailLevel.WARN))),
            personaSpec()
                .withSkills(
                    List.of(new AgentHarnessSkill().withName("no-such-skill").withEnabled(true))),
            personaSpec()
                .withEvents(
                    List.of(event(HookEvent.STOP, true, 100), event(HookEvent.STOP, true, 200))),
            personaSpec().withEvents(List.of(event(HookEvent.STOP, true, 0))),
            personaSpec().withPerTurnTokenBudget(0));

    for (AgentHarnessSpec requested : refused) {
      assertThrows(
          IllegalArgumentException.class,
          () -> HarnessDraftValidator.forPersona(requested, PUBLISHED_DEFAULT),
          requested.toString());
    }
  }

  @Test
  void rejectsMemoryThatIsAuthorOnlyButNotPrivate() {
    AgentHarnessSpec requested =
        personaSpec()
            .withMemory(
                new AgentHarnessMemory()
                    .withCapture(true)
                    .withLinkAssets(true)
                    .withDedup(true)
                    .withReview(AgentMemoryReview.AUTHOR_ONLY)
                    .withVisibility(AgentMemoryVisibility.ORGANIZATION));

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessDraftValidator.forPersona(requested, PUBLISHED_DEFAULT));
  }

  @Test
  void rejectsADisabledContextDefinition() {
    AgentHarnessSpec requested = personaSpec();
    requested.getContext().setEnabled(false);

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessDraftValidator.forPersona(requested, PUBLISHED_DEFAULT));
  }

  private static AgentHarnessSpec personaSpec() {
    return HarnessSpecs.initialPersonaSpec(PUBLISHED_DEFAULT)
        .withContext(new PersonaContextDefinition().withPrompt("Answer as a marketing analyst"));
  }

  private static AgentHarnessGuardrail guardrail(String id, GuardrailLevel level) {
    return new AgentHarnessGuardrail().withId(id).withLevel(level);
  }

  private static AgentHarnessEvent event(HookEvent id, boolean enabled, int tokenBudget) {
    return new AgentHarnessEvent().withId(id).withEnabled(enabled).withTokenBudget(tokenBudget);
  }
}
