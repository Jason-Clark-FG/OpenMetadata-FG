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

package org.openmetadata.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.entity.context.AgentHarnessKnowledgeGraph;
import org.openmetadata.schema.entity.context.AgentHarnessMemory;
import org.openmetadata.schema.entity.context.AgentHarnessSubAgent;
import org.openmetadata.service.aicontext.agents.SubAgentDefinition;
import org.openmetadata.service.aicontext.agents.SubAgentRegistry;
import org.openmetadata.service.aicontext.harness.HarnessDocuments;

/** What the caller's effective agent harness lets knowledge_graph do. */
record KnowledgeGraphHarness(
    boolean rawQueries, String learnRefusal, List<SubAgentDefinition> agents, Guidance guidance) {

  static final KnowledgeGraphHarness DEFAULTS =
      new KnowledgeGraphHarness(true, null, List.of(), null);

  /**
   * What the caller's harness tells the model, carried in the first answer of a run: the company
   * instructions, the persona's prompt, and the entry rule when the harness turns it on.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Guidance(String companyInstructions, String yourUsers, String rule) {
    static Guidance of(final String company, final String users, final String rule) {
      final boolean empty = isBlank(company) && isBlank(users) && isBlank(rule);
      return empty
          ? null
          : new Guidance(isBlank(company) ? null : company, isBlank(users) ? null : users, rule);
    }

    private static boolean isBlank(final String text) {
      return text == null || text.isBlank();
    }
  }

  static final String RAW_QUERIES_OFF =
      "The agent harness turns raw SPARQL off for knowledge_graph: answer with find, expand and"
          + " the cards they return.";

  static final String LEARNING_OFF = "the agent harness turns learning off for knowledge_graph.";

  static final String CAPTURE_OFF = "the agent harness turns memory capture off.";

  static KnowledgeGraphHarness of(
      final Optional<AgentHarnessSpec> effective,
      final String personaPrompt,
      final SubAgentRegistry registry) {
    return effective.map(spec -> of(spec, personaPrompt, registry)).orElse(DEFAULTS);
  }

  private static KnowledgeGraphHarness of(
      final AgentHarnessSpec spec, final String personaPrompt, final SubAgentRegistry registry) {
    final AgentHarnessKnowledgeGraph settings =
        Optional.ofNullable(spec.getKnowledgeGraph()).orElseGet(AgentHarnessKnowledgeGraph::new);
    return new KnowledgeGraphHarness(
        !Boolean.FALSE.equals(settings.getRawQueries()),
        learnRefusal(settings, spec.getMemory()),
        enabledAgents(settings, registry),
        Guidance.of(
            spec.getCompanyInstructions(),
            personaPrompt,
            Boolean.TRUE.equals(settings.getEntry())
                ? HarnessDocuments.KNOWLEDGE_GRAPH_RULE
                : null));
  }

  private static String learnRefusal(
      final AgentHarnessKnowledgeGraph settings, final AgentHarnessMemory memory) {
    final boolean captureOff = memory != null && Boolean.FALSE.equals(memory.getCapture());
    final String captureRefusal = captureOff ? CAPTURE_OFF : null;
    return Boolean.FALSE.equals(settings.getLearn()) ? LEARNING_OFF : captureRefusal;
  }

  private static List<SubAgentDefinition> enabledAgents(
      final AgentHarnessKnowledgeGraph settings, final SubAgentRegistry registry) {
    return Optional.ofNullable(settings.getSubAgents()).orElse(List.of()).stream()
        .filter(setting -> Boolean.TRUE.equals(setting.getEnabled()))
        .map(AgentHarnessSubAgent::getName)
        .filter(Objects::nonNull)
        .distinct()
        .map(registry::agent)
        .flatMap(Optional::stream)
        .toList();
  }

  Optional<SubAgentDefinition> agent(final String name) {
    return agents.stream().filter(agent -> agent.name().equals(name)).findFirst();
  }
}
