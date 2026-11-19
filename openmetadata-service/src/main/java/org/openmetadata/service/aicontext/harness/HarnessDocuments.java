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
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.SkillDefinition;
import org.openmetadata.schema.type.AIContext;
import org.openmetadata.schema.type.PersonaContext;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.aicontext.FieldContext;
import org.openmetadata.schema.type.aicontext.KnowledgeItem;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.type.personaContext.RuleResult;
import org.openmetadata.schema.type.personaContext.SearchScope;
import org.openmetadata.schema.type.personaContext.SearchScopeRule;
import org.openmetadata.schema.type.personaContext.SharedKnowledge;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.SearchScopeNote;
import org.openmetadata.service.aicontext.harness.CompileSelector.Document;
import org.openmetadata.service.aicontext.harness.ContextItem.AssetRef;
import org.openmetadata.service.aicontext.harness.ContextItem.Origin;

/** Turns a caller's materialized persona context into the items the compiler selects from. */
final class HarnessDocuments {
  /**
   * Hosts show the finish_answer card only when the model calls the tool, and a model that is not
   * told to call it answers in prose. The line is the same for every caller, so it is not
   * configurable, and it is short to stay under every host's hook cap.
   */
  static final String ANSWER_RULE =
      "When you answer a question about data, finish by calling finish_answer with the terms,"
          + " tables, time window, SQL and any numbers you used, and show its result.";

  private static final int MAX_DESCRIPTION_CHARS = 600;
  private static final int MAX_COLUMNS = 30;
  private static final Map<String, String> KNOWLEDGE_KINDS =
      Map.of(
          Entity.GLOSSARY_TERM, ContextItem.GLOSSARY,
          Entity.METRIC, ContextItem.METRICS,
          Entity.PAGE, ContextItem.ARTICLES);

  /** Everything the document is made from: the effective settings and the built context. */
  record Sources(
      AgentHarnessSpec effective,
      AgentHarnessSpec defaults,
      PersonaContextDefinition definition,
      PersonaContext context) {}

  private HarnessDocuments() {}

  static Document from(final Sources sources) {
    return new Document(fixedItems(sources), knowledgeItems(sources));
  }

  private static List<ContextItem> fixedItems(final Sources sources) {
    final List<ContextItem> items = new ArrayList<>();
    if (!nullOrEmpty(sources.effective().getCompanyInstructions())) {
      items.add(
          ContextItem.fixed(
              Origin.DEFAULT,
              ContextItem.INSTRUCTIONS,
              "instructions",
              "## Company instructions\n\n" + sources.effective().getCompanyInstructions()));
    }
    if (!nullOrEmpty(sources.definition().getPrompt())) {
      items.add(
          ContextItem.fixed(
              Origin.PERSONA,
              ContextItem.PROMPT,
              "prompt",
              "## Your users\n\n" + sources.definition().getPrompt()));
    }
    final String searchScope = SearchScopeNote.render(displayScope(sources.context())).strip();
    if (!searchScope.isEmpty()) {
      items.add(
          ContextItem.fixed(Origin.PERSONA, ContextItem.SEARCH_SCOPE, "searchScope", searchScope));
    }
    items.add(
        ContextItem.fixed(Origin.DEFAULT, ContextItem.INSTRUCTIONS, "answerCards", ANSWER_RULE));
    items.addAll(skillItems(sources));
    return items;
  }

  /** The rule names carry the harness prefix the merge added, which the model need not see. */
  private static SearchScope displayScope(final PersonaContext context) {
    final SearchScope scope = context.getSearchScope();
    if (scope == null) {
      return null;
    }
    return new SearchScope()
        .withEntityTypes(scope.getEntityTypes())
        .withRules(
            listOrEmpty(scope.getRules()).stream()
                .map(
                    rule ->
                        new SearchScopeRule()
                            .withRuleName(unprefixed(rule.getRuleName()))
                            .withEntityType(rule.getEntityType())
                            .withQueryFilter(rule.getQueryFilter()))
                .toList());
  }

  private static String unprefixed(final String ruleName) {
    return Stream.of(HarnessResolver.DEFAULT_RULE_PREFIX, HarnessResolver.PERSONA_RULE_PREFIX)
        .filter(ruleName::startsWith)
        .findFirst()
        .map(prefix -> ruleName.substring(prefix.length()))
        .orElse(ruleName);
  }

  private static List<ContextItem> skillItems(final Sources sources) {
    final Map<String, SkillDefinition> definitions =
        SkillRegistry.definitions().stream()
            .collect(Collectors.toMap(SkillDefinition::getName, Function.identity()));
    return listOrEmpty(sources.effective().getSkills()).stream()
        .filter(skill -> Boolean.TRUE.equals(skill.getEnabled()))
        .map(skill -> definitions.get(skill.getName()))
        .filter(Objects::nonNull)
        .map(
            skill ->
                ContextItem.fixed(
                    SkillRegistry.isOfferedByDefault(
                            sources.defaults().getSkills(), skill.getName())
                        ? Origin.DEFAULT
                        : Origin.PERSONA,
                    ContextItem.SKILLS,
                    "skill:" + skill.getName(),
                    "Skill `" + skill.getName() + "`: " + skill.getDescription()))
        .toList();
  }

  private static List<ContextItem> knowledgeItems(final Sources sources) {
    final Map<String, ContextRule> rules =
        listOrEmpty(sources.definition().getRules()).stream()
            .collect(Collectors.toMap(ContextRule::getName, Function.identity(), (a, b) -> a));
    final List<ContextItem> items = new ArrayList<>();
    for (final RuleResult result : listOrEmpty(sources.context().getRules())) {
      final ContextRule rule = rules.get(result.getRuleName());
      for (final AIContext entity : listOrEmpty(result.getEntities())) {
        items.add(ruleItem(result, rule, entity));
      }
    }
    items.addAll(sharedItems(sources.context().getSharedKnowledge(), sharedOrigin(sources)));
    return items;
  }

  private static ContextItem ruleItem(
      final RuleResult result, final ContextRule rule, final AIContext entity) {
    final String entityType =
        entity.getEntityType() == null ? result.getEntityType() : entity.getEntityType();
    final String kind = KNOWLEDGE_KINDS.getOrDefault(entityType, ContextItem.ASSETS);
    return new ContextItem(
        originOf(result.getRuleName()),
        kind,
        entityType + ':' + entity.getFullyQualifiedName(),
        card(kind, entity),
        names(entity.getName(), entity.getDisplayName(), entity.getFullyQualifiedName()),
        rule != null && Boolean.TRUE.equals(rule.getAlwaysInContext()),
        ContextItem.ASSETS.equals(kind)
            ? new AssetRef(entityType, entity.getFullyQualifiedName())
            : null,
        knowledgeRef(kind, entityType, entity.getFullyQualifiedName()));
  }

  /**
   * Knowledge attached to the selected assets is shared by every rule, so it has no rule of its
   * own. It counts as the persona's when the persona adds rules, so it is cut before the
   * default's items.
   */
  private static Origin sharedOrigin(final Sources sources) {
    return listOrEmpty(sources.definition().getRules()).stream()
            .anyMatch(rule -> originOf(rule.getName()) == Origin.PERSONA)
        ? Origin.PERSONA
        : Origin.DEFAULT;
  }

  private static List<ContextItem> sharedItems(final SharedKnowledge shared, final Origin origin) {
    final List<ContextItem> items = new ArrayList<>();
    if (shared != null) {
      Stream.of(
              listOrEmpty(shared.getGlossaryTerms()),
              listOrEmpty(shared.getMetrics()),
              listOrEmpty(shared.getArticles()))
          .flatMap(List::stream)
          .map(item -> sharedItem(item, origin))
          .forEach(items::add);
    }
    return items;
  }

  private static ContextItem sharedItem(final KnowledgeItem item, final Origin origin) {
    final String entityType = item.getType().value();
    final String kind = KNOWLEDGE_KINDS.getOrDefault(entityType, ContextItem.GLOSSARY);
    return new ContextItem(
        origin,
        kind,
        entityType + ':' + item.getFullyQualifiedName(),
        heading(kind, label(item.getName(), item.getDisplayName()), item.getFullyQualifiedName())
            + truncated(item.getContent()),
        names(item.getName(), item.getDisplayName(), item.getFullyQualifiedName()),
        false,
        null,
        knowledgeRef(kind, entityType, item.getFullyQualifiedName()));
  }

  private static AssetRef knowledgeRef(
      final String kind, final String entityType, final String fqn) {
    return ContextItem.GLOSSARY.equals(kind) || ContextItem.METRICS.equals(kind)
        ? new AssetRef(entityType, fqn)
        : null;
  }

  static Origin originOf(final String ruleName) {
    return ruleName != null && ruleName.startsWith(HarnessResolver.PERSONA_RULE_PREFIX)
        ? Origin.PERSONA
        : Origin.DEFAULT;
  }

  private static String card(final String kind, final AIContext entity) {
    return heading(
            kind, label(entity.getName(), entity.getDisplayName()), entity.getFullyQualifiedName())
        + truncated(entity.getDescription())
        + columns(entity);
  }

  private static String heading(final String kind, final String label, final String fqn) {
    return "### " + kind + ": " + label + "\n`" + fqn + "`\n";
  }

  private static String columns(final AIContext entity) {
    final List<FieldContext> fields =
        Optional.ofNullable(entity.getAssetContext())
            .map(asset -> asset.getTable())
            .map(table -> listOrEmpty(table.getColumns()))
            .orElse(List.of());
    return fields.isEmpty()
        ? ""
        : "\nColumns: "
            + fields.stream()
                .limit(MAX_COLUMNS)
                .map(field -> field.getName() + " " + nullToEmpty(field.getDataType()))
                .map(String::strip)
                .collect(Collectors.joining(", "));
  }

  private static String truncated(final String text) {
    final String value = nullToEmpty(text).strip();
    return value.length() <= MAX_DESCRIPTION_CHARS
        ? value
        : value.substring(0, MAX_DESCRIPTION_CHARS) + "…";
  }

  private static String label(final String name, final String displayName) {
    return nullOrEmpty(displayName) ? nullToEmpty(name) : displayName;
  }

  private static List<String> names(final String... values) {
    return Stream.of(values).filter(value -> !nullOrEmpty(value)).distinct().toList();
  }

  private static String nullToEmpty(final String value) {
    return value == null ? "" : value;
  }
}
