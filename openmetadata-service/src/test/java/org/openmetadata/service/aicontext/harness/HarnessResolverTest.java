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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.ResolvedAgentHarness;
import org.openmetadata.schema.api.context.ResolvedContextRule;
import org.openmetadata.schema.api.context.ResolvedHarnessEvent;
import org.openmetadata.schema.api.context.ResolvedHarnessGuardrail;
import org.openmetadata.schema.api.context.ResolvedHarnessSkill;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessEvent;
import org.openmetadata.schema.entity.context.AgentHarnessGuardrail;
import org.openmetadata.schema.entity.context.AgentHarnessKind;
import org.openmetadata.schema.entity.context.AgentHarnessMemory;
import org.openmetadata.schema.entity.context.AgentHarnessSkill;
import org.openmetadata.schema.entity.context.AgentMemoryReview;
import org.openmetadata.schema.entity.context.AgentMemoryVisibility;
import org.openmetadata.schema.entity.context.GuardrailLevel;
import org.openmetadata.schema.entity.context.HookEvent;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.personaContext.ContextRule;

class HarnessResolverTest {

  @Test
  void mergedContextHasTheDefaultRulesFirstThenThePersonaRulesUnderPrefixedNames() {
    AgentHarness defaults = seededDefault(rule("Certified tables"), rule("Revenue"));
    AgentHarness persona = publishedPersona(rule("Revenue"), rule("Marketing"));

    AgentHarnessSpec effective = HarnessResolver.effective(defaults, persona).orElseThrow();

    assertEquals(
        List.of(
            "default/Certified tables", "default/Revenue", "persona/Revenue", "persona/Marketing"),
        effective.getContext().getRules().stream().map(ContextRule::getName).toList());
    assertEquals("Answer as a marketing analyst", effective.getContext().getPrompt());
    assertEquals(
        List.of("Certified tables", "Revenue"),
        defaults.getContext().getRules().stream().map(ContextRule::getName).toList(),
        "resolving leaves the stored default untouched");
  }

  @Test
  void aMissingDisabledOrUnpublishedPersonaHarnessResolvesToTheDefaultAlone() {
    AgentHarness defaults = seededDefault(rule("Certified tables"));
    AgentHarness disabled = publishedPersona(rule("Marketing")).withEnabled(false);
    AgentHarness unpublished = publishedPersona(rule("Marketing")).withPublishedAt(null);

    for (AgentHarness persona : new AgentHarness[] {null, disabled, unpublished}) {
      AgentHarnessSpec effective = HarnessResolver.effective(defaults, persona).orElseThrow();
      assertEquals(
          List.of("default/Certified tables"),
          effective.getContext().getRules().stream().map(ContextRule::getName).toList());
      assertNull(effective.getContext().getPrompt());
    }
  }

  @Test
  void aDisabledDefaultDeliversNothing() {
    AgentHarness defaults = seededDefault().withEnabled(false);

    assertTrue(HarnessResolver.effective(defaults, publishedPersona()).isEmpty());
  }

  @Test
  void aDefaultMadeStricterAfterThePersonaWasPublishedStillBindsThePersona() {
    AgentHarness persona = publishedPersona();
    persona
        .withGuardrails(
            List.of(guardrail(GuardrailRegistry.FLAG_OPEN_INCIDENTS, GuardrailLevel.WARN)))
        .withEvents(List.of(event(HookEvent.SESSION_START, true, 5000)));
    AgentHarness defaults = seededDefault();
    defaults.setGuardrails(
        List.of(
            guardrail(GuardrailRegistry.FLAG_OPEN_INCIDENTS, GuardrailLevel.BLOCK),
            guardrail(GuardrailRegistry.QUERY_COST_CEILING, GuardrailLevel.OFF)));
    defaults.setEvents(List.of(event(HookEvent.SESSION_START, true, 3000)));

    AgentHarnessSpec effective = HarnessResolver.effective(defaults, persona).orElseThrow();

    assertEquals(GuardrailLevel.BLOCK, levelOf(effective, GuardrailRegistry.FLAG_OPEN_INCIDENTS));
    assertEquals(3000, budgetOf(effective, HookEvent.SESSION_START));
  }

  @Test
  void aPersonaCanOnlyTightenLevelsAndBudgetsAndAddEventsAndSkills() {
    AgentHarness defaults = seededDefault();
    defaults.setEvents(
        List.of(event(HookEvent.SESSION_START, true, 6000), event(HookEvent.STOP, false, 1000)));
    defaults.setSkills(
        List.of(
            new AgentHarnessSkill().withName(SkillRegistry.METRIC_DEFINITION).withEnabled(false)));
    AgentHarness persona = publishedPersona();
    persona
        .withGuardrails(
            List.of(
                guardrail(GuardrailRegistry.QUERY_COST_CEILING, GuardrailLevel.WARN),
                guardrail(GuardrailRegistry.BLOCK_DEPRECATED_ASSETS, GuardrailLevel.OFF)))
        .withEvents(
            List.of(event(HookEvent.SESSION_START, false, 9000), event(HookEvent.STOP, true, 500)))
        .withSkills(
            List.of(
                new AgentHarnessSkill()
                    .withName(SkillRegistry.METRIC_DEFINITION)
                    .withEnabled(true)));

    AgentHarnessSpec effective = HarnessResolver.effective(defaults, persona).orElseThrow();

    assertEquals(GuardrailLevel.WARN, levelOf(effective, GuardrailRegistry.QUERY_COST_CEILING));
    assertEquals(
        GuardrailLevel.BLOCK, levelOf(effective, GuardrailRegistry.BLOCK_DEPRECATED_ASSETS));
    AgentHarnessEvent sessionStart = eventOf(effective, HookEvent.SESSION_START);
    assertTrue(sessionStart.getEnabled(), "the persona cannot turn off an event the default sends");
    assertEquals(6000, sessionStart.getTokenBudget());
    AgentHarnessEvent stop = eventOf(effective, HookEvent.STOP);
    assertTrue(stop.getEnabled());
    assertEquals(500, stop.getTokenBudget());
    assertTrue(
        effective.getSkills().stream()
            .filter(skill -> SkillRegistry.METRIC_DEFINITION.equals(skill.getName()))
            .findFirst()
            .orElseThrow()
            .getEnabled());
  }

  @Test
  void thePersonaBudgetsAreCappedAtTheDefaults() {
    AgentHarness defaults = seededDefault();
    defaults.getContext().setCharacterBudget(50_000);
    defaults.setPerTurnTokenBudget(15_000);
    AgentHarness persona = publishedPersona();
    persona.getContext().setCharacterBudget(900_000);
    persona.setPerTurnTokenBudget(30_000);

    AgentHarnessSpec effective = HarnessResolver.effective(defaults, persona).orElseThrow();

    assertEquals(50_000, effective.getContext().getCharacterBudget());
    assertEquals(15_000, effective.getPerTurnTokenBudget());

    persona.getContext().setCharacterBudget(20_000);
    assertEquals(
        20_000,
        HarnessResolver.effective(defaults, persona)
            .orElseThrow()
            .getContext()
            .getCharacterBudget());
  }

  @Test
  void thePersonaDisplayFlagsInheritedItemsAndCarriesTheDefaultMinimums() {
    AgentHarness defaults = seededDefault(rule("Certified tables"));
    AgentHarness persona = publishedPersona(rule("Marketing"));
    persona.withGuardrails(
        List.of(guardrail(GuardrailRegistry.PREFER_CERTIFIED_TABLES, GuardrailLevel.BLOCK)));

    ResolvedAgentHarness view =
        HarnessResolver.display(HarnessSpecs.specOf(defaults), HarnessSpecs.specOf(persona));

    assertEquals(AgentHarnessKind.PERSONA, view.getKind());
    assertEquals(
        List.of("Certified tables:true", "Marketing:false"),
        view.getRules().stream()
            .map(rule -> rule.getRule().getName() + ":" + rule.getInherited())
            .toList());
    ResolvedHarnessGuardrail certified =
        guardrailView(view, GuardrailRegistry.PREFER_CERTIFIED_TABLES);
    assertEquals(GuardrailLevel.BLOCK, certified.getLevel());
    assertEquals(GuardrailLevel.WARN, certified.getMinLevel());
    ResolvedHarnessEvent sessionStart = eventView(view, HookEvent.SESSION_START);
    assertTrue(sessionStart.getInherited());
    assertEquals(6000, sessionStart.getMaxTokenBudget());
    assertTrue(view.getSkills().stream().allMatch(ResolvedHarnessSkill::getInherited));
    assertTrue(view.getMemoryInherited());
    assertEquals(AgentMemoryReview.STEWARD_REVIEW, view.getMemory().getReview());
  }

  @Test
  void theDefaultDisplayInheritsNothingAndHasNoMinimums() {
    ResolvedAgentHarness view =
        HarnessResolver.display(HarnessSpecs.specOf(seededDefault(rule("Certified tables"))));

    assertEquals(AgentHarnessKind.DEFAULT, view.getKind());
    assertFalse(view.getRules().stream().anyMatch(ResolvedContextRule::getInherited));
    assertEquals(
        List.of(
            HookEvent.SESSION_START,
            HookEvent.USER_PROMPT_SUBMIT,
            HookEvent.PRE_TOOL_USE,
            HookEvent.POST_TOOL_USE,
            HookEvent.STOP),
        view.getEvents().stream().map(ResolvedHarnessEvent::getId).toList());
    assertEquals(
        List.of(6000, 4000, 2000, 1000, 1000),
        view.getEvents().stream().map(ResolvedHarnessEvent::getTokenBudget).toList());
    assertTrue(view.getEvents().stream().noneMatch(ResolvedHarnessEvent::getInherited));
    assertTrue(
        view.getGuardrails().stream().allMatch(guardrail -> guardrail.getMinLevel() == null));
    assertEquals(20_000, view.getPerTurnTokenBudget());
  }

  @Test
  void aPersonaWithItsOwnMemorySettingsReplacesTheDefaults() {
    AgentHarness persona = publishedPersona();
    persona.setMemory(
        new AgentHarnessMemory()
            .withCapture(false)
            .withLinkAssets(true)
            .withDedup(true)
            .withReview(AgentMemoryReview.AUTHOR_ONLY)
            .withVisibility(AgentMemoryVisibility.PRIVATE));

    ResolvedAgentHarness view =
        HarnessResolver.display(HarnessSpecs.specOf(seededDefault()), HarnessSpecs.specOf(persona));

    assertFalse(view.getMemoryInherited());
    assertEquals(AgentMemoryReview.AUTHOR_ONLY, view.getMemory().getReview());
    assertFalse(view.getMemory().getCapture());
  }

  private static AgentHarness seededDefault(ContextRule... rules) {
    AgentHarness harness = HarnessSpecs.seedDefault(1L);
    harness.getContext().setRules(List.of(rules));
    return harness;
  }

  private static AgentHarness publishedPersona(ContextRule... rules) {
    UUID personaId = UUID.randomUUID();
    return new AgentHarness()
        .withId(UUID.randomUUID())
        .withName(personaId.toString())
        .withKind(AgentHarnessKind.PERSONA)
        .withPersona(new EntityReference().withId(personaId).withType("persona"))
        .withEnabled(true)
        .withPublishedAt(2L)
        .withContext(
            new PersonaContextDefinition()
                .withPrompt("Answer as a marketing analyst")
                .withRules(List.of(rules)));
  }

  private static ContextRule rule(String name) {
    return new ContextRule().withId(UUID.randomUUID()).withName(name).withEntityType("table");
  }

  private static AgentHarnessGuardrail guardrail(String id, GuardrailLevel level) {
    return new AgentHarnessGuardrail().withId(id).withLevel(level);
  }

  private static AgentHarnessEvent event(HookEvent id, boolean enabled, int tokenBudget) {
    return new AgentHarnessEvent().withId(id).withEnabled(enabled).withTokenBudget(tokenBudget);
  }

  private static GuardrailLevel levelOf(AgentHarnessSpec spec, String id) {
    return GuardrailRegistry.find(spec.getGuardrails(), id).orElseThrow();
  }

  private static AgentHarnessEvent eventOf(AgentHarnessSpec spec, HookEvent id) {
    return HarnessEvents.find(spec.getEvents(), id).orElseThrow();
  }

  private static int budgetOf(AgentHarnessSpec spec, HookEvent id) {
    return eventOf(spec, id).getTokenBudget();
  }

  private static ResolvedHarnessGuardrail guardrailView(ResolvedAgentHarness view, String id) {
    return view.getGuardrails().stream()
        .filter(guardrail -> id.equals(guardrail.getId()))
        .findFirst()
        .orElseThrow();
  }

  private static ResolvedHarnessEvent eventView(ResolvedAgentHarness view, HookEvent id) {
    return view.getEvents().stream().filter(event -> event.getId() == id).findFirst().orElseThrow();
  }
}
