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

import java.util.ArrayList;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessKind;
import org.openmetadata.schema.entity.context.AgentHarnessMemory;
import org.openmetadata.schema.entity.context.AgentMemoryReview;
import org.openmetadata.schema.entity.context.AgentMemoryVisibility;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.utils.JsonUtils;

/**
 * The editable part of a harness. A draft holds one spec, and a publish copies it onto the entity.
 */
public final class HarnessSpecs {
  public static final String DEFAULT_NAME = "CompanyDefault";
  static final int DEFAULT_PER_TURN_TOKEN_BUDGET = 20000;

  private HarnessSpecs() {}

  /** A copy of the harness's own settings, which a caller may change without changing the harness. */
  public static AgentHarnessSpec specOf(final AgentHarness harness) {
    return JsonUtils.deepCopy(
        new AgentHarnessSpec()
            .withContext(harness.getContext())
            .withCompanyInstructions(harness.getCompanyInstructions())
            .withPerTurnTokenBudget(harness.getPerTurnTokenBudget())
            .withEvents(harness.getEvents())
            .withSkills(harness.getSkills())
            .withGuardrails(harness.getGuardrails())
            .withToolAccess(harness.getToolAccess())
            .withMemory(harness.getMemory())
            .withAnswerHarness(harness.getAnswerHarness()),
        AgentHarnessSpec.class);
  }

  public static void apply(final AgentHarnessSpec spec, final AgentHarness harness) {
    final AgentHarnessSpec copy = JsonUtils.deepCopy(spec, AgentHarnessSpec.class);
    harness
        .withContext(copy.getContext())
        .withCompanyInstructions(copy.getCompanyInstructions())
        .withPerTurnTokenBudget(copy.getPerTurnTokenBudget())
        .withEvents(copy.getEvents())
        .withSkills(copy.getSkills())
        .withGuardrails(copy.getGuardrails())
        .withToolAccess(copy.getToolAccess())
        .withMemory(copy.getMemory())
        .withAnswerHarness(copy.getAnswerHarness());
  }

  /** A copy of the harness as it would be if {@code spec} were published and it were enabled. */
  public static AgentHarness asPublished(final AgentHarness harness, final AgentHarnessSpec spec) {
    final AgentHarness copy = JsonUtils.deepCopy(harness, AgentHarness.class);
    apply(spec, copy);
    return copy.withEnabled(true)
        .withPublishedAt(
            harness.getPublishedAt() == null
                ? System.currentTimeMillis()
                : harness.getPublishedAt());
  }

  /** The company default as a new deployment starts with it: no rules and no instructions. */
  public static AgentHarness seedDefault(final long publishedAt) {
    return new AgentHarness()
        .withName(DEFAULT_NAME)
        .withFullyQualifiedName(DEFAULT_NAME)
        .withDisplayName("Company default")
        .withDescription("Context every agent receives. Persona harnesses extend it.")
        .withKind(AgentHarnessKind.DEFAULT)
        .withEnabled(true)
        .withPublishedAt(publishedAt)
        .withContext(new PersonaContextDefinition())
        .withPerTurnTokenBudget(DEFAULT_PER_TURN_TOKEN_BUDGET)
        .withEvents(new ArrayList<>(HarnessEvents.fallbacks()))
        .withSkills(new ArrayList<>(SkillRegistry.fallbacks()))
        .withGuardrails(new ArrayList<>(GuardrailRegistry.seedLevels()))
        .withToolAccess(ToolAccessPolicy.allowAll())
        .withMemory(memoryFallback());
  }

  /**
   * The draft a new persona harness starts with. It adds nothing, and it starts from the default's
   * budgets and cache lifetime so that publishing it unchanged does not lower them for the persona.
   */
  public static AgentHarnessSpec initialPersonaSpec(final AgentHarnessSpec publishedDefault) {
    final PersonaContextDefinition defaultContext = contextOf(publishedDefault);
    return new AgentHarnessSpec()
        .withContext(
            new PersonaContextDefinition()
                .withCharacterBudget(defaultContext.getCharacterBudget())
                .withCacheTtlMinutes(defaultContext.getCacheTtlMinutes()))
        .withPerTurnTokenBudget(publishedDefault.getPerTurnTokenBudget());
  }

  /** The spec's context settings, or the built-in ones when it has none. */
  public static PersonaContextDefinition contextOf(final AgentHarnessSpec spec) {
    return spec.getContext() == null ? new PersonaContextDefinition() : spec.getContext();
  }

  /** The default harness's memory settings, or the built-in ones when it has none. */
  public static AgentHarnessMemory defaultMemory(final AgentHarnessSpec defaultSpec) {
    return defaultSpec.getMemory() == null ? memoryFallback() : defaultSpec.getMemory();
  }

  private static AgentHarnessMemory memoryFallback() {
    return new AgentHarnessMemory()
        .withCapture(true)
        .withLinkAssets(true)
        .withDedup(true)
        .withReview(AgentMemoryReview.STEWARD_REVIEW)
        .withVisibility(AgentMemoryVisibility.PRIVATE);
  }
}
