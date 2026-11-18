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

import java.util.List;
import java.util.Optional;
import org.openmetadata.schema.api.context.SkillDefinition;
import org.openmetadata.schema.entity.context.AgentHarnessSkill;

/** The skills the server can offer to agents, in the order every listing shows them. */
public final class SkillRegistry {
  public static final String FIND_CONTEXT = "find-context";
  public static final String METRIC_DEFINITION = "metric-definition";

  private record Skill(String name, String description, boolean enabledByDefault) {
    SkillDefinition definition() {
      return new SkillDefinition()
          .withName(name)
          .withDescription(description)
          .withDefaultEnabled(enabledByDefault);
    }

    AgentHarnessSkill setting() {
      return new AgentHarnessSkill().withName(name).withEnabled(enabledByDefault);
    }
  }

  private static final List<Skill> SKILLS =
      List.of(
          new Skill(
              FIND_CONTEXT,
              "Looks up the glossary terms, metrics and certified assets that answer a question "
                  + "before the agent queries data.",
              true),
          new Skill(
              METRIC_DEFINITION,
              "Returns the approved definition, owner and lineage of a metric.",
              true));

  private SkillRegistry() {}

  public static List<String> names() {
    return SKILLS.stream().map(Skill::name).toList();
  }

  public static List<SkillDefinition> definitions() {
    return SKILLS.stream().map(Skill::definition).toList();
  }

  public static boolean isRegistered(final String name) {
    return SKILLS.stream().anyMatch(skill -> skill.name().equals(name));
  }

  /** Whether each skill is offered before an administrator changes anything. */
  public static List<AgentHarnessSkill> fallbacks() {
    return SKILLS.stream().map(Skill::setting).toList();
  }

  public static Optional<Boolean> find(final List<AgentHarnessSkill> skills, final String name) {
    return listOrEmpty(skills).stream()
        .filter(skill -> name.equals(skill.getName()))
        .map(skill -> Boolean.TRUE.equals(skill.getEnabled()))
        .findFirst();
  }

  /** Whether the default harness offers {@code name}. A skill it does not list uses the fallback. */
  public static boolean isOfferedByDefault(
      final List<AgentHarnessSkill> defaultSkills, final String name) {
    return find(defaultSkills, name).orElseGet(() -> isEnabledByDefault(name));
  }

  private static boolean isEnabledByDefault(final String name) {
    return SKILLS.stream()
        .filter(skill -> skill.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown skill: " + name))
        .enabledByDefault();
  }
}
