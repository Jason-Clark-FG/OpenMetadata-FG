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

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.type.personaContext.ContextSection;
import org.openmetadata.service.Entity;

/**
 * Checks and normalizes the context settings of an agent harness: budgets, rule count, rule names
 * and ids, entity types and their sections, and the shape of each rule's filter.
 */
public final class PersonaContextDefinitionValidation {
  private static final int DEFAULT_MAX_ASSETS = 200;
  private static final int MAX_RULES = 25;
  private static final int MIN_CHARACTER_BUDGET = 10_000;
  private static final int MAX_CHARACTER_BUDGET = 2_000_000;
  private static final int MIN_CACHE_TTL_MINUTES = 1;
  private static final int MAX_CACHE_TTL_MINUTES = 1_440;
  private static final int MAX_PROMPT_LENGTH = 8_000;
  private static final Set<ContextSection> ASSET_SECTIONS =
      Set.of(
          ContextSection.DESCRIPTION,
          ContextSection.SCHEMA,
          ContextSection.CONSTRAINTS,
          ContextSection.JOINS,
          ContextSection.TAGS,
          ContextSection.GLOSSARY_TERMS,
          ContextSection.ARTICLES,
          ContextSection.METRICS,
          ContextSection.LINEAGE,
          ContextSection.PROFILE,
          ContextSection.DATA_QUALITY);
  private static final Set<ContextSection> ARTICLE_SECTIONS =
      Set.of(
          ContextSection.TITLE_SUMMARY,
          ContextSection.FULL_BODY,
          ContextSection.TAGS,
          ContextSection.GLOSSARY_TERMS,
          ContextSection.RELATED_ASSETS);
  private static final Set<ContextSection> METRIC_SECTIONS =
      Set.of(
          ContextSection.DEFINITION,
          ContextSection.FORMULA_EXPRESSION,
          ContextSection.UNIT_GRAIN,
          ContextSection.OWNER,
          ContextSection.TAGS,
          ContextSection.RELATED_ASSETS);
  private static final Set<ContextSection> GLOSSARY_TERM_SECTIONS =
      Set.of(
          ContextSection.DEFINITION,
          ContextSection.SYNONYMS,
          ContextSection.RELATED_TERMS,
          ContextSection.TAGS,
          ContextSection.RELATED_ASSETS);

  private PersonaContextDefinitionValidation() {}

  /** Validates {@code definition} in place, filling rule ids, default sections and maxAssets. */
  public static void validate(final PersonaContextDefinition definition) {
    definition.setLastGeneratedAt(null);
    definition.setCacheState(null);
    definition.setLastError(null);
    definition.setPrompt(normalizedPrompt(definition.getPrompt()));
    requireBetween(
        definition.getCharacterBudget(),
        MIN_CHARACTER_BUDGET,
        MAX_CHARACTER_BUDGET,
        "Persona context characterBudget must be between 10000 and 2000000");
    requireBetween(
        definition.getCacheTtlMinutes(),
        MIN_CACHE_TTL_MINUTES,
        MAX_CACHE_TTL_MINUTES,
        "Persona context cacheTtlMinutes must be between 1 and 1440");
    if (listOrEmpty(definition.getRules()).size() > MAX_RULES) {
      throw new IllegalArgumentException("Persona context supports at most 25 rules");
    }
    final Set<UUID> ruleIds = new HashSet<>();
    final Set<String> ruleNames = new HashSet<>();
    for (final ContextRule rule : listOrEmpty(definition.getRules())) {
      validateIdentity(rule, ruleIds, ruleNames);
      validateSelection(rule);
    }
  }

  /**
   * Whether {@code rule}'s filter must be checked with the search engine: it is new, or its entity
   * type or filter changed, or it is enabled again, compared with the same rule in {@code
   * knownRules}, matched by id and then by name.
   */
  public static boolean requiresRuleValidation(
      final ContextRule rule, final List<ContextRule> knownRules) {
    final ContextRule previous = previousContextRule(rule, knownRules);
    final boolean unchanged =
        previous != null
            && Objects.equals(previous.getEntityType(), rule.getEntityType())
            && Objects.equals(previous.getQueryFilter(), rule.getQueryFilter())
            && (!Boolean.TRUE.equals(rule.getEnabled())
                || Boolean.TRUE.equals(previous.getEnabled()));
    return !unchanged;
  }

  private static void requireBetween(
      final Integer value, final int min, final int max, final String message) {
    if (value == null || value < min || value > max) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void validateIdentity(
      final ContextRule rule, final Set<UUID> ruleIds, final Set<String> ruleNames) {
    if (rule.getId() == null) {
      rule.setId(UUID.randomUUID());
    }
    if (!ruleIds.add(rule.getId())) {
      throw new IllegalArgumentException(
          "Persona context rule IDs must be unique: " + rule.getId());
    }
    rule.setMatchedCount(null);
    final String ruleName = rule.getName() == null ? "" : rule.getName().trim();
    if (nullOrEmpty(ruleName)) {
      throw new IllegalArgumentException("Persona context rule name must not be empty");
    }
    rule.setName(ruleName);
    if (!ruleNames.add(ruleName.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Persona context rule names must be unique: " + ruleName);
    }
  }

  private static void validateSelection(final ContextRule rule) {
    final String ruleEntityType = rule.getEntityType();
    if (!PersonaContextBuilder.supportsEntityType(ruleEntityType)) {
      throw new IllegalArgumentException(
          "Unsupported persona context entity type: " + ruleEntityType);
    }
    if (PersonaContextBuilder.isKnowledgeEntityType(ruleEntityType)) {
      rule.setFullyRendered(true);
    }
    if (nullOrEmpty(rule.getSections())) {
      rule.setSections(defaultSections(ruleEntityType));
    }
    if (rule.getMaxAssets() == null) {
      rule.setMaxAssets(DEFAULT_MAX_ASSETS);
    } else if (rule.getMaxAssets() < 1 || rule.getMaxAssets() > 1000) {
      throw new IllegalArgumentException(
          "Persona context rule maxAssets must be between 1 and 1000: " + rule.getName());
    }
    if (!allowedSections(ruleEntityType).containsAll(rule.getSections())) {
      throw new IllegalArgumentException(
          "Persona context rule contains sections that do not apply to " + ruleEntityType);
    }
    if (!nullOrEmpty(rule.getQueryFilter())) {
      PersonaContextRuleValidation.query(rule.getQueryFilter());
    }
  }

  private static ContextRule previousContextRule(
      final ContextRule requested, final List<ContextRule> knownRules) {
    return knownRules.stream()
        .filter(
            known -> requested.getId() != null && Objects.equals(known.getId(), requested.getId()))
        .findFirst()
        .orElseGet(
            () ->
                knownRules.stream()
                    .filter(
                        known ->
                            !nullOrEmpty(requested.getName())
                                && requested.getName().equalsIgnoreCase(known.getName()))
                    .findFirst()
                    .orElse(null));
  }

  /** Trimmed, or null when blank so an emptied editor clears the prompt instead of storing "". */
  private static String normalizedPrompt(final String prompt) {
    final String trimmed = prompt == null ? "" : prompt.strip();
    if (trimmed.length() > MAX_PROMPT_LENGTH) {
      throw new IllegalArgumentException(
          "Persona context prompt must be at most " + MAX_PROMPT_LENGTH + " characters");
    }
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static Set<ContextSection> allowedSections(final String entityType) {
    return switch (entityType) {
      case Entity.PAGE -> ARTICLE_SECTIONS;
      case Entity.METRIC -> METRIC_SECTIONS;
      case Entity.GLOSSARY_TERM -> GLOSSARY_TERM_SECTIONS;
      default -> ASSET_SECTIONS;
    };
  }

  private static Set<ContextSection> defaultSections(final String entityType) {
    return switch (entityType) {
      case Entity.PAGE -> Set.of(
          ContextSection.TITLE_SUMMARY, ContextSection.FULL_BODY, ContextSection.TAGS);
      case Entity.METRIC -> Set.of(
          ContextSection.DEFINITION, ContextSection.FORMULA_EXPRESSION, ContextSection.UNIT_GRAIN);
      case Entity.GLOSSARY_TERM -> Set.of(ContextSection.DEFINITION);
      default -> Set.of(
          ContextSection.DESCRIPTION,
          ContextSection.SCHEMA,
          ContextSection.CONSTRAINTS,
          ContextSection.JOINS,
          ContextSection.TAGS,
          ContextSection.GLOSSARY_TERMS,
          ContextSection.ARTICLES,
          ContextSection.METRICS);
    };
  }
}
