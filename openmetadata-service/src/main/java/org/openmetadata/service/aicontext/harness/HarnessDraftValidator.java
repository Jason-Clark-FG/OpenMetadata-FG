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

import java.time.DateTimeException;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.ResolvedAgentHarness;
import org.openmetadata.schema.api.context.ResolvedHarnessEvent;
import org.openmetadata.schema.entity.context.AgentHarnessAnswer;
import org.openmetadata.schema.entity.context.AgentHarnessCalendar;
import org.openmetadata.schema.entity.context.AgentHarnessConnection;
import org.openmetadata.schema.entity.context.AgentHarnessEvent;
import org.openmetadata.schema.entity.context.AgentHarnessGuardrail;
import org.openmetadata.schema.entity.context.AgentHarnessKnowledgeGraph;
import org.openmetadata.schema.entity.context.AgentHarnessMemory;
import org.openmetadata.schema.entity.context.AgentHarnessSkill;
import org.openmetadata.schema.entity.context.AgentHarnessSubAgent;
import org.openmetadata.schema.entity.context.AgentMemoryReview;
import org.openmetadata.schema.entity.context.AgentMemoryVisibility;
import org.openmetadata.schema.entity.context.GuardrailLevel;
import org.openmetadata.schema.entity.context.HookEvent;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.aicontext.PersonaContextDefinitionValidation;
import org.openmetadata.service.aicontext.agents.SubAgentRegistry;

/**
 * Checks the settings an administrator saves as a draft or publishes, and returns them normalized.
 * A persona's settings are checked against the published default. They can add to it and make it
 * stricter, and they cannot loosen it. Every refusal is an {@link IllegalArgumentException} that
 * names the setting.
 */
public final class HarnessDraftValidator {
  private static final DateTimeFormatter MONTH_DAY =
      DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT);

  private HarnessDraftValidator() {}

  public static AgentHarnessSpec forDefault(final AgentHarnessSpec requested) {
    final AgentHarnessSpec spec = normalized(requested);
    if (spec.getContext().getPrompt() != null) {
      throw new IllegalArgumentException(
          "The default harness has no persona prompt. Put company-wide text in companyInstructions");
    }
    validateOwnSettings(spec);
    validateAnswerHarness(spec.getAnswerHarness());
    requirePerTurnCoversEvents(HarnessResolver.display(spec));
    return spec;
  }

  public static AgentHarnessSpec forPersona(
      final AgentHarnessSpec requested, final AgentHarnessSpec publishedDefault) {
    final AgentHarnessSpec spec = normalized(requested);
    if (spec.getCompanyInstructions() != null) {
      throw new IllegalArgumentException(
          "companyInstructions belong to the default harness. A persona harness sets context.prompt");
    }
    if (spec.getAnswerHarness() != null) {
      throw new IllegalArgumentException(
          "answerHarness belongs to the default harness. Two calendars would give two answers to "
              + "one question");
    }
    validateOwnSettings(spec);
    requireOwnRuleIds(spec, publishedDefault);
    requireEventBudgetsWithinDefault(spec, publishedDefault);
    requireGuardrailsAtLeastDefault(spec, publishedDefault);
    ToolAccessPolicy.requireNoLooserThan(spec.getToolAccess(), publishedDefault.getToolAccess());
    requirePerTurnCoversEvents(HarnessResolver.display(publishedDefault, spec));
    return spec;
  }

  private static AgentHarnessSpec normalized(final AgentHarnessSpec requested) {
    if (requested == null) {
      throw new IllegalArgumentException("Agent harness settings must not be empty");
    }
    final AgentHarnessSpec spec = JsonUtils.deepCopy(requested, AgentHarnessSpec.class);
    final String instructions =
        spec.getCompanyInstructions() == null ? "" : spec.getCompanyInstructions().strip();
    return spec.withContext(normalizedContext(spec.getContext()))
        .withCompanyInstructions(instructions.isEmpty() ? null : instructions)
        .withEvents(new ArrayList<>(listOrEmpty(spec.getEvents())))
        .withSkills(new ArrayList<>(listOrEmpty(spec.getSkills())))
        .withGuardrails(new ArrayList<>(listOrEmpty(spec.getGuardrails())));
  }

  private static PersonaContextDefinition normalizedContext(
      final PersonaContextDefinition requested) {
    final PersonaContextDefinition context =
        requested == null ? new PersonaContextDefinition() : requested;
    if (Boolean.FALSE.equals(context.getEnabled())) {
      throw new IllegalArgumentException(
          "context.enabled cannot be false. A harness is enabled or disabled as a whole with "
              + "PUT /v1/agentHarness/{id}/enabled");
    }
    PersonaContextDefinitionValidation.validate(context.withEnabled(true));
    return context;
  }

  private static void validateOwnSettings(final AgentHarnessSpec spec) {
    requirePositive("perTurnTokenBudget", spec.getPerTurnTokenBudget());
    validateEvents(spec.getEvents());
    validateSkills(spec.getSkills());
    validateGuardrails(spec.getGuardrails());
    ToolAccessPolicy.requireValid(spec.getToolAccess());
    validateMemory(spec.getMemory());
    validateKnowledgeGraph(spec.getKnowledgeGraph());
  }

  /**
   * A sub-agent the harness enables must be one this installation registers. A connection needs
   * only a name: connections come and go while the server runs, so one not registered now is kept
   * and does nothing.
   */
  private static void validateKnowledgeGraph(final AgentHarnessKnowledgeGraph knowledgeGraph) {
    if (knowledgeGraph == null) {
      return;
    }
    for (final AgentHarnessSubAgent agent : listOrEmpty(knowledgeGraph.getSubAgents())) {
      if (agent.getName() == null
          || SubAgentRegistry.getInstance().agent(agent.getName()).isEmpty()) {
        throw new IllegalArgumentException(
            "knowledgeGraph.subAgents names '%s', which no provider registers. See GET /v1/agentHarness/subAgents"
                .formatted(agent.getName()));
      }
    }
    for (final AgentHarnessConnection connection : listOrEmpty(knowledgeGraph.getConnections())) {
      if (connection.getName() == null || connection.getName().isBlank()) {
        throw new IllegalArgumentException(
            "knowledgeGraph.connections has an entry without a name. See GET /v1/agentHarness/connections");
      }
    }
  }

  private static void validateEvents(final List<AgentHarnessEvent> events) {
    final Set<HookEvent> listed = EnumSet.noneOf(HookEvent.class);
    for (final AgentHarnessEvent event : events) {
      if (event.getId() == null || event.getEnabled() == null) {
        throw new IllegalArgumentException("Every event needs an id and an enabled flag");
      }
      requireListedOnce(listed.add(event.getId()), "Event", event.getId().value());
      requirePositive("Event " + event.getId().value() + " tokenBudget", event.getTokenBudget());
    }
  }

  private static void validateSkills(final List<AgentHarnessSkill> skills) {
    final Set<String> listed = new HashSet<>();
    for (final AgentHarnessSkill skill : skills) {
      if (skill.getName() == null || !SkillRegistry.isRegistered(skill.getName())) {
        throw new IllegalArgumentException(
            "Unknown skill '"
                + skill.getName()
                + "'. GET /v1/agentHarness/skills lists the skills");
      }
      if (skill.getEnabled() == null) {
        throw new IllegalArgumentException("Skill " + skill.getName() + " needs an enabled flag");
      }
      requireListedOnce(listed.add(skill.getName()), "Skill", skill.getName());
    }
  }

  private static void validateGuardrails(final List<AgentHarnessGuardrail> guardrails) {
    final Set<String> listed = new HashSet<>();
    for (final AgentHarnessGuardrail guardrail : guardrails) {
      if (guardrail.getId() == null || !GuardrailRegistry.isRegistered(guardrail.getId())) {
        throw new IllegalArgumentException(
            "Unknown guardrail '"
                + guardrail.getId()
                + "'. GET /v1/agentHarness/guardrails lists the guardrails");
      }
      if (guardrail.getLevel() == null) {
        throw new IllegalArgumentException("Guardrail " + guardrail.getId() + " needs a level");
      }
      requireListedOnce(listed.add(guardrail.getId()), "Guardrail", guardrail.getId());
    }
  }

  private static void validateAnswerHarness(final AgentHarnessAnswer answer) {
    final AgentHarnessCalendar calendar = answer == null ? null : answer.getCalendar();
    if (calendar != null) {
      try {
        MonthDay.parse(calendar.getFiscalYearStart(), MONTH_DAY);
        ZoneId.of(calendar.getTimeZone());
      } catch (DateTimeException exception) {
        throw new IllegalArgumentException(
            "answerHarness.calendar needs a real MM-DD fiscalYearStart and an IANA timeZone: "
                + exception.getMessage());
      }
    }
  }

  private static void validateMemory(final AgentHarnessMemory memory) {
    if (memory != null) {
      final boolean incomplete =
          Stream.of(
                  memory.getCapture(),
                  memory.getLinkAssets(),
                  memory.getDedup(),
                  memory.getReview(),
                  memory.getVisibility())
              .anyMatch(Objects::isNull);
      if (incomplete) {
        throw new IllegalArgumentException(
            "Memory settings need capture, linkAssets, dedup, review and visibility");
      }
      if (memory.getReview() == AgentMemoryReview.AUTHOR_ONLY
          && memory.getVisibility() != AgentMemoryVisibility.PRIVATE) {
        throw new IllegalArgumentException(
            "Memory visibility must be Private when review is AuthorOnly");
      }
    }
  }

  private static void requireOwnRuleIds(
      final AgentHarnessSpec spec, final AgentHarnessSpec publishedDefault) {
    final Set<UUID> inherited =
        listOrEmpty(HarnessSpecs.contextOf(publishedDefault).getRules()).stream()
            .map(ContextRule::getId)
            .collect(Collectors.toSet());
    for (final ContextRule rule : spec.getContext().getRules()) {
      if (inherited.contains(rule.getId())) {
        throw new IllegalArgumentException(
            "Rule '"
                + rule.getName()
                + "' has the id of a rule inherited from the default. A persona harness cannot "
                + "change inherited rules");
      }
    }
  }

  private static void requireEventBudgetsWithinDefault(
      final AgentHarnessSpec spec, final AgentHarnessSpec publishedDefault) {
    for (final AgentHarnessEvent event : spec.getEvents()) {
      final int maximum =
          HarnessEvents.defaultSetting(publishedDefault.getEvents(), event.getId())
              .getTokenBudget();
      if (event.getTokenBudget() > maximum) {
        throw new IllegalArgumentException(
            String.format(
                "Event %s tokenBudget %d exceeds the default's %d",
                event.getId().value(), event.getTokenBudget(), maximum));
      }
    }
  }

  private static void requireGuardrailsAtLeastDefault(
      final AgentHarnessSpec spec, final AgentHarnessSpec publishedDefault) {
    for (final AgentHarnessGuardrail guardrail : spec.getGuardrails()) {
      final GuardrailLevel minimum =
          GuardrailRegistry.defaultLevel(publishedDefault.getGuardrails(), guardrail.getId());
      if (guardrail.getLevel().compareTo(minimum) < 0) {
        throw new IllegalArgumentException(
            String.format(
                "Guardrail %s cannot be %s. The default's level %s is the minimum",
                guardrail.getId(), guardrail.getLevel().value(), minimum.value()));
      }
    }
  }

  private static void requirePerTurnCoversEvents(final ResolvedAgentHarness resolved) {
    final long enabledTotal =
        resolved.getEvents().stream()
            .filter(event -> Boolean.TRUE.equals(event.getEnabled()))
            .mapToLong(ResolvedHarnessEvent::getTokenBudget)
            .sum();
    if (resolved.getPerTurnTokenBudget() < enabledTotal) {
      throw new IllegalArgumentException(
          String.format(
              "perTurnTokenBudget %d is below the %d tokens of the enabled events",
              resolved.getPerTurnTokenBudget(), enabledTotal));
    }
  }

  private static void requirePositive(final String setting, final Integer value) {
    if (value == null || value < 1) {
      throw new IllegalArgumentException(setting + " must be an integer greater than 0");
    }
  }

  private static void requireListedOnce(
      final boolean firstListing, final String kind, final String name) {
    if (!firstListing) {
      throw new IllegalArgumentException(kind + " " + name + " is listed more than once");
    }
  }
}
