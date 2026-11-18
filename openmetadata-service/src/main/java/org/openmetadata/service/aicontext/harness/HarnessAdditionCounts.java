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

import com.fasterxml.jackson.databind.JsonNode;
import org.openmetadata.schema.api.context.AgentHarnessAdditions;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;

/** What a persona harness adds to the default, as the list's Harness column shows it. */
final class HarnessAdditionCounts {
  private static final String ARTICLE = "page";

  private HarnessAdditionCounts() {}

  static AgentHarnessAdditions of(final AgentHarness harness) {
    final AgentHarnessSpec spec = HarnessSpecs.specOf(harness);
    int terms = 0;
    int metrics = 0;
    int articles = 0;
    int rules = 0;
    for (final ContextRule rule : listOrEmpty(HarnessSpecs.contextOf(spec).getRules())) {
      if (!Boolean.TRUE.equals(rule.getPicked())) {
        rules++;
      } else if (Entity.GLOSSARY_TERM.equals(rule.getEntityType())) {
        terms += pickedIds(rule);
      } else if (Entity.METRIC.equals(rule.getEntityType())) {
        metrics += pickedIds(rule);
      } else if (ARTICLE.equals(rule.getEntityType())) {
        articles += pickedIds(rule);
      }
    }
    return new AgentHarnessAdditions()
        .withGlossaryTerms(terms)
        .withMetrics(metrics)
        .withArticles(articles)
        .withRules(rules)
        .withSkills(
            (int)
                listOrEmpty(spec.getSkills()).stream()
                    .filter(skill -> Boolean.TRUE.equals(skill.getEnabled()))
                    .count())
        .withGuardrails(listOrEmpty(spec.getGuardrails()).size());
  }

  /** A picked rule selects its entities with a `terms: {id: [...]}` clause somewhere in its filter. */
  private static int pickedIds(final ContextRule rule) {
    if (rule.getQueryFilter() == null) {
      return 0;
    }
    try {
      return idTerms(JsonUtils.readTree(rule.getQueryFilter()));
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static int idTerms(final JsonNode node) {
    final JsonNode ids = node.path("terms").path("id");
    if (ids.isArray()) {
      return ids.size();
    }
    for (final JsonNode child : node) {
      final int found = idTerms(child);
      if (found > 0) {
        return found;
      }
    }
    return 0;
  }
}
