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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.api.context.ConceptCard;
import org.openmetadata.schema.api.context.ConceptCardApplied;
import org.openmetadata.schema.api.context.ConceptCardAppliedTo;
import org.openmetadata.schema.api.context.ConceptCardContainer;
import org.openmetadata.schema.api.context.ConceptCardKind;
import org.openmetadata.schema.api.context.ConceptCardLink;
import org.openmetadata.schema.api.context.ConceptCardMetric;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.entity.data.Metric;
import org.openmetadata.schema.type.AIContext;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EntityStatus;
import org.openmetadata.schema.type.TermRelation;
import org.openmetadata.schema.type.aicontext.ConceptBinding;
import org.openmetadata.schema.type.aicontext.ConceptContext;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.answer.EntityLinks;
import org.openmetadata.service.util.FullyQualifiedName;

/**
 * Shapes a resolved glossary term or metric into the card an MCP Apps host shows. It adds nothing
 * the caller could not read: the context it takes was built as the caller, so its relations and
 * bindings are already limited to what they may view.
 */
public final class ConceptCardBuilder {
  static final int MAX_APPLIED = 10;
  static final int MAX_RELATED = 20;
  private static final Pattern CONFUSABLE =
      Pattern.compile("confus|disambig", Pattern.CASE_INSENSITIVE);
  private static final Pattern WORD_BREAK = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

  private final EntityLinks links;

  public ConceptCardBuilder(final EntityLinks links) {
    this.links = links;
  }

  public ConceptCard build(final AIContext context, final EntityInterface<?> entity) {
    final boolean metric = entity instanceof Metric;
    final ConceptContext concept =
        context.getAssetContext() == null ? null : context.getAssetContext().getConceptContext();
    final ConceptContext details = concept == null ? new ConceptContext() : concept;
    final String entityType = metric ? Entity.METRIC : Entity.GLOSSARY_TERM;
    final ConceptCard card =
        new ConceptCard()
            .withKind(metric ? ConceptCardKind.METRIC : ConceptCardKind.GLOSSARY_TERM)
            .withName(entity.getName())
            .withDisplayName(entity.getDisplayName())
            .withFqn(entity.getFullyQualifiedName())
            .withDescription(AIContextBuilder.unescapeRichText(entity.getDescription()))
            .withUrl(links.ofFqn(entityType, entity.getFullyQualifiedName()))
            .withContainer(container(entity))
            .withStatus(
                entity.getEntityStatus() instanceof EntityStatus status ? status.value() : null)
            .withCertified(LiveTrustReader.isCertified(entity))
            .withOwners(names(context.getOwners()))
            .withReviewers(names(entity.getReviewers()))
            .withUpdatedAt(entity.getUpdatedAt())
            .withAppliedTo(appliedTo(details));
    if (entity instanceof GlossaryTerm term) {
      termFields(card, term, details);
    } else if (entity instanceof Metric metricEntity) {
      card.withMetric(metric(metricEntity, details));
    }
    return card;
  }

  private void termFields(
      final ConceptCard card, final GlossaryTerm term, final ConceptContext details) {
    final String[] path = FullyQualifiedName.split(term.getFullyQualifiedName());
    final List<String> parentPath = new ArrayList<>();
    for (int index = 1; index < path.length - 1; index++) {
      parentPath.add(FullyQualifiedName.unquoteName(path[index]));
    }
    final List<ConceptCardLink> related = new ArrayList<>();
    final List<ConceptCardLink> confusable = new ArrayList<>();
    for (final TermRelation relation : listOrEmpty(details.getRelatedTerms())) {
      final ConceptCardLink link = link(relation);
      if (CONFUSABLE.matcher(nullToEmpty(relation.getRelationType())).find()) {
        confusable.add(link.withName(qualified(relation.getTerm())));
      } else if (related.size() < MAX_RELATED) {
        related.add(link);
      }
    }
    card.withParentPath(parentPath)
        .withSynonyms(listOrEmpty(details.getSynonyms()))
        .withRelated(related)
        .withConfusedWith(confusable.isEmpty() ? null : confusable);
  }

  private ConceptCardLink link(final TermRelation relation) {
    final EntityReference term = relation.getTerm();
    final String label =
        relation.getRelationshipType() != null
                && !nullOrEmpty(relation.getRelationshipType().getDisplayName())
            ? relation.getRelationshipType().getDisplayName()
            : relationLabel(relation.getRelationType());
    return new ConceptCardLink()
        .withName(nullOrEmpty(term.getDisplayName()) ? term.getName() : term.getDisplayName())
        .withFqn(term.getFullyQualifiedName())
        .withUrl(links.ofFqn(Entity.GLOSSARY_TERM, term.getFullyQualifiedName()))
        .withRelationType(label)
        .withNote(firstSentence(term.getDescription()));
  }

  /** A term mistaken for another is told apart by its glossary: Finance › Customer. */
  private static String qualified(final EntityReference term) {
    final List<String> parts = new ArrayList<>();
    for (final String part : FullyQualifiedName.split(term.getFullyQualifiedName())) {
      parts.add(FullyQualifiedName.unquoteName(part));
    }
    return String.join(" › ", parts);
  }

  /** {@code relatedTo} reads as "related to". */
  static String relationLabel(final String relationType) {
    return nullOrEmpty(relationType)
        ? "related to"
        : WORD_BREAK.matcher(relationType).replaceAll(" ").toLowerCase(Locale.ROOT);
  }

  static String firstSentence(final String text) {
    final String plain = text == null ? "" : AIContextBuilder.unescapeRichText(text).strip();
    final int end = plain.indexOf(". ");
    final String sentence = end < 0 ? plain : plain.substring(0, end + 1);
    return sentence.isEmpty() ? null : sentence;
  }

  private ConceptCardContainer container(final EntityInterface<?> entity) {
    final ConceptCardContainer container;
    if (entity instanceof GlossaryTerm term && term.getGlossary() != null) {
      final EntityReference glossary = term.getGlossary();
      container =
          new ConceptCardContainer()
              .withLabel("Glossary")
              .withName(
                  nullOrEmpty(glossary.getDisplayName())
                      ? glossary.getName()
                      : glossary.getDisplayName())
              .withUrl(links.ofFqn(Entity.GLOSSARY, glossary.getFullyQualifiedName()));
    } else if (!listOrEmpty(entity.getDomains()).isEmpty()) {
      final EntityReference domain = entity.getDomains().get(0);
      container =
          new ConceptCardContainer()
              .withLabel("Metric")
              .withName(
                  nullOrEmpty(domain.getDisplayName())
                      ? domain.getName()
                      : domain.getDisplayName());
    } else {
      container = null;
    }
    return container;
  }

  private static ConceptCardMetric metric(final Metric metric, final ConceptContext details) {
    final var expression = metric.getMetricExpression();
    return new ConceptCardMetric()
        .withExpression(expression == null ? null : expression.getCode())
        .withLanguage(
            expression == null || expression.getLanguage() == null
                ? null
                : expression.getLanguage().value())
        .withFilters(details.getFilters())
        .withGranularity(details.getGranularity())
        .withUnit(details.getUnit())
        .withMetricType(metric.getMetricType() == null ? null : metric.getMetricType().value())
        .withRelatedMetrics(
            listOrEmpty(metric.getRelatedMetrics()).stream()
                .map(ConceptCardBuilder::name)
                .filter(name -> !nullOrEmpty(name))
                .toList());
  }

  /**
   * One binding per asset first, so one wide table does not use every row, then the rest in the
   * order the context lists them.
   */
  private ConceptCardAppliedTo appliedTo(final ConceptContext details) {
    final List<ConceptBinding> bindings = listOrEmpty(details.getBindings());
    final Set<String> seenAssets = new LinkedHashSet<>();
    final List<ConceptBinding> ordered = new ArrayList<>();
    final List<ConceptBinding> rest = new ArrayList<>();
    for (final ConceptBinding binding : bindings) {
      if (seenAssets.add(binding.getAssetFqn())) {
        ordered.add(binding);
      } else {
        rest.add(binding);
      }
    }
    ordered.addAll(rest);
    final int total =
        details.getTotalAssets() == null ? seenAssets.size() : details.getTotalAssets();
    return new ConceptCardAppliedTo()
        .withTotal(total)
        .withTruncated(Boolean.TRUE.equals(details.getTruncated()))
        .withItems(ordered.stream().limit(MAX_APPLIED).map(this::applied).toList());
  }

  private ConceptCardApplied applied(final ConceptBinding binding) {
    final boolean column = !nullOrEmpty(binding.getColumn());
    final String[] path =
        FullyQualifiedName.split(column ? binding.getColumn() : binding.getAssetFqn());
    final int shown = column ? 2 : 1;
    final List<String> tail = new ArrayList<>();
    for (int index = Math.max(path.length - shown, 0); index < path.length; index++) {
      tail.add(FullyQualifiedName.unquoteName(path[index]));
    }
    return new ConceptCardApplied()
        .withKind(column ? "Column" : typeLabel(binding.getAssetType()))
        .withName(String.join(".", tail))
        .withUrl(links.ofFqn(binding.getAssetType(), binding.getAssetFqn()))
        .withDetail(
            column && binding.getDataType() != null
                ? binding.getDataType().toLowerCase(Locale.ROOT)
                : null);
  }

  /** Types whose camelCase name does not spell out as people read them. */
  private static final Map<String, String> IRREGULAR_TYPE_LABELS =
      Map.of(
          "mlmodel", "ML model",
          "apiEndpoint", "API endpoint",
          "apiCollection", "API collection",
          "databaseSchema", "Schema");

  static String typeLabel(final String entityType) {
    return IRREGULAR_TYPE_LABELS.getOrDefault(nullToEmpty(entityType), spelledOut(entityType));
  }

  private static String spelledOut(final String entityType) {
    final String spaced =
        WORD_BREAK.matcher(nullToEmpty(entityType)).replaceAll(" ").toLowerCase(Locale.ROOT);
    return spaced.isEmpty()
        ? "Asset"
        : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
  }

  private static List<String> names(final List<EntityReference> references) {
    return listOrEmpty(references).stream()
        .map(ConceptCardBuilder::name)
        .filter(name -> !nullOrEmpty(name))
        .toList();
  }

  private static String name(final EntityReference reference) {
    return nullOrEmpty(reference.getDisplayName())
        ? reference.getName()
        : reference.getDisplayName();
  }

  private static String nullToEmpty(final String value) {
    return value == null ? "" : value;
  }
}
