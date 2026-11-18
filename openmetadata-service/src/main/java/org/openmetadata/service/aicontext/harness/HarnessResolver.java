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

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.openmetadata.schema.entity.context.AgentHarnessSkill;
import org.openmetadata.schema.entity.context.GuardrailLevel;
import org.openmetadata.schema.entity.context.HookEvent;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.utils.JsonUtils;

/**
 * Applies inheritance between the company default and a persona harness. The default's items come
 * first, a persona only adds to them, and every level and budget is the stricter of the two, so a
 * default that becomes stricter after a persona harness was published still binds that persona.
 */
public final class HarnessResolver {
  public static final String DEFAULT_RULE_PREFIX = "default/";
  public static final String PERSONA_RULE_PREFIX = "persona/";

  private HarnessResolver() {}

  /** Whether a persona harness takes part in what the persona's members receive. */
  public static boolean applies(final AgentHarness personaHarness) {
    return personaHarness != null
        && Boolean.TRUE.equals(personaHarness.getEnabled())
        && personaHarness.getPublishedAt() != null;
  }

  /**
   * What agents receive for one caller, as one spec that needs no further inheritance. Its context
   * holds the default's rules and then the persona's, with names prefixed so that two rules of the
   * same name stay distinct. Empty when the default is disabled, because then nothing is delivered.
   * A persona harness that is missing, disabled or never published leaves the default alone.
   */
  public static Optional<AgentHarnessSpec> effective(
      final AgentHarness defaultHarness, final AgentHarness personaHarness) {
    Optional<AgentHarnessSpec> effective = Optional.empty();
    if (Boolean.TRUE.equals(defaultHarness.getEnabled())) {
      final AgentHarnessSpec defaults = HarnessSpecs.specOf(defaultHarness);
      final AgentHarnessSpec persona =
          applies(personaHarness) ? HarnessSpecs.specOf(personaHarness) : null;
      effective = Optional.of(effective(defaults, persona));
    }
    return effective;
  }

  /** The default harness as its editor shows it. Nothing is inherited. */
  public static ResolvedAgentHarness display(final AgentHarnessSpec defaultSpec) {
    return view(defaultSpec, null);
  }

  /** A persona harness as its editor shows it, over the published default. */
  public static ResolvedAgentHarness display(
      final AgentHarnessSpec defaultSpec, final AgentHarnessSpec personaSpec) {
    return view(defaultSpec, personaSpec);
  }

  private static AgentHarnessSpec effective(
      final AgentHarnessSpec defaults, final AgentHarnessSpec persona) {
    final ResolvedAgentHarness view = view(defaults, persona);
    return new AgentHarnessSpec()
        .withContext(mergedContext(defaults, persona, view))
        .withCompanyInstructions(view.getCompanyInstructions())
        .withPerTurnTokenBudget(view.getPerTurnTokenBudget())
        .withEvents(view.getEvents().stream().map(HarnessResolver::setting).toList())
        .withSkills(view.getSkills().stream().map(HarnessResolver::setting).toList())
        .withGuardrails(view.getGuardrails().stream().map(HarnessResolver::setting).toList())
        .withMemory(view.getMemory());
  }

  private static PersonaContextDefinition mergedContext(
      final AgentHarnessSpec defaults,
      final AgentHarnessSpec persona,
      final ResolvedAgentHarness view) {
    final List<ContextRule> rules =
        new ArrayList<>(prefixed(DEFAULT_RULE_PREFIX, HarnessSpecs.contextOf(defaults)));
    if (persona != null) {
      rules.addAll(prefixed(PERSONA_RULE_PREFIX, HarnessSpecs.contextOf(persona)));
    }
    return new PersonaContextDefinition()
        .withPrompt(view.getPrompt())
        .withRules(rules)
        .withCharacterBudget(view.getCharacterBudget())
        .withCacheTtlMinutes(view.getCacheTtlMinutes());
  }

  private static List<ContextRule> prefixed(
      final String prefix, final PersonaContextDefinition context) {
    return listOrEmpty(context.getRules()).stream()
        .map(rule -> JsonUtils.deepCopy(rule, ContextRule.class))
        .map(rule -> rule.withName(prefix + rule.getName()))
        .toList();
  }

  private static ResolvedAgentHarness view(
      final AgentHarnessSpec defaults, final AgentHarnessSpec persona) {
    final PersonaContextDefinition defaultContext = HarnessSpecs.contextOf(defaults);
    final ResolvedAgentHarness view =
        new ResolvedAgentHarness()
            .withKind(persona == null ? AgentHarnessKind.DEFAULT : AgentHarnessKind.PERSONA)
            .withCompanyInstructions(defaults.getCompanyInstructions())
            .withRules(rules(defaults, persona))
            .withCharacterBudget(defaultContext.getCharacterBudget())
            .withCacheTtlMinutes(defaultContext.getCacheTtlMinutes())
            .withPerTurnTokenBudget(defaults.getPerTurnTokenBudget())
            .withEvents(events(defaults, persona))
            .withSkills(skills(defaults, persona))
            .withGuardrails(guardrails(defaults, persona))
            .withMemory(HarnessSpecs.defaultMemory(defaults))
            .withMemoryInherited(false);
    if (persona != null) {
      overlayPersonaSettings(view, persona);
    }
    return view;
  }

  private static void overlayPersonaSettings(
      final ResolvedAgentHarness view, final AgentHarnessSpec persona) {
    final PersonaContextDefinition own = HarnessSpecs.contextOf(persona);
    view.withPrompt(own.getPrompt())
        .withCharacterBudget(Math.min(view.getCharacterBudget(), own.getCharacterBudget()))
        .withCacheTtlMinutes(own.getCacheTtlMinutes())
        .withPerTurnTokenBudget(
            Math.min(view.getPerTurnTokenBudget(), persona.getPerTurnTokenBudget()))
        .withMemory(persona.getMemory() == null ? view.getMemory() : persona.getMemory())
        .withMemoryInherited(persona.getMemory() == null);
  }

  private static List<ResolvedContextRule> rules(
      final AgentHarnessSpec defaults, final AgentHarnessSpec persona) {
    final List<ResolvedContextRule> rules = new ArrayList<>();
    for (final ContextRule rule : listOrEmpty(HarnessSpecs.contextOf(defaults).getRules())) {
      rules.add(new ResolvedContextRule().withRule(rule).withInherited(persona != null));
    }
    if (persona != null) {
      for (final ContextRule rule : listOrEmpty(HarnessSpecs.contextOf(persona).getRules())) {
        rules.add(new ResolvedContextRule().withRule(rule).withInherited(false));
      }
    }
    return rules;
  }

  private static List<ResolvedHarnessEvent> events(
      final AgentHarnessSpec defaults, final AgentHarnessSpec persona) {
    return HarnessEvents.ids().stream().map(id -> event(id, defaults, persona)).toList();
  }

  private static List<ResolvedHarnessSkill> skills(
      final AgentHarnessSpec defaults, final AgentHarnessSpec persona) {
    return SkillRegistry.names().stream().map(name -> skill(name, defaults, persona)).toList();
  }

  private static List<ResolvedHarnessGuardrail> guardrails(
      final AgentHarnessSpec defaults, final AgentHarnessSpec persona) {
    return GuardrailRegistry.ids().stream().map(id -> guardrail(id, defaults, persona)).toList();
  }

  private static ResolvedHarnessEvent event(
      final HookEvent id, final AgentHarnessSpec defaults, final AgentHarnessSpec persona) {
    final AgentHarnessEvent base = HarnessEvents.defaultSetting(defaults.getEvents(), id);
    final ResolvedHarnessEvent event =
        new ResolvedHarnessEvent()
            .withId(id)
            .withEnabled(isOn(base))
            .withTokenBudget(base.getTokenBudget())
            .withInherited(false);
    if (persona != null) {
      final Optional<AgentHarnessEvent> own = HarnessEvents.find(persona.getEvents(), id);
      event
          .withEnabled(isOn(base) || own.map(HarnessResolver::isOn).orElse(false))
          .withTokenBudget(
              own.map(setting -> Math.min(setting.getTokenBudget(), base.getTokenBudget()))
                  .orElse(base.getTokenBudget()))
          .withInherited(isOn(base))
          .withMaxTokenBudget(base.getTokenBudget());
    }
    return event;
  }

  private static ResolvedHarnessSkill skill(
      final String name, final AgentHarnessSpec defaults, final AgentHarnessSpec persona) {
    final boolean offeredByDefault = SkillRegistry.isOfferedByDefault(defaults.getSkills(), name);
    final ResolvedHarnessSkill skill =
        new ResolvedHarnessSkill()
            .withName(name)
            .withEnabled(offeredByDefault)
            .withInherited(false);
    if (persona != null) {
      final boolean addedByPersona = SkillRegistry.find(persona.getSkills(), name).orElse(false);
      skill.withEnabled(offeredByDefault || addedByPersona).withInherited(offeredByDefault);
    }
    return skill;
  }

  private static ResolvedHarnessGuardrail guardrail(
      final String id, final AgentHarnessSpec defaults, final AgentHarnessSpec persona) {
    final GuardrailLevel base = GuardrailRegistry.defaultLevel(defaults.getGuardrails(), id);
    final ResolvedHarnessGuardrail guardrail =
        new ResolvedHarnessGuardrail().withId(id).withLevel(base);
    if (persona != null) {
      final GuardrailLevel own = GuardrailRegistry.find(persona.getGuardrails(), id).orElse(base);
      guardrail.withLevel(own.compareTo(base) > 0 ? own : base).withMinLevel(base);
    }
    return guardrail;
  }

  private static boolean isOn(final AgentHarnessEvent event) {
    return Boolean.TRUE.equals(event.getEnabled());
  }

  private static AgentHarnessEvent setting(final ResolvedHarnessEvent event) {
    return new AgentHarnessEvent()
        .withId(event.getId())
        .withEnabled(event.getEnabled())
        .withTokenBudget(event.getTokenBudget());
  }

  private static AgentHarnessSkill setting(final ResolvedHarnessSkill skill) {
    return new AgentHarnessSkill().withName(skill.getName()).withEnabled(skill.getEnabled());
  }

  private static AgentHarnessGuardrail setting(final ResolvedHarnessGuardrail guardrail) {
    return new AgentHarnessGuardrail().withId(guardrail.getId()).withLevel(guardrail.getLevel());
  }
}
