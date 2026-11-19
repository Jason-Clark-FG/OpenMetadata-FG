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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.ConceptCard;
import org.openmetadata.schema.api.context.ConceptCardKind;
import org.openmetadata.schema.api.data.MetricExpression;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.entity.data.Metric;
import org.openmetadata.schema.type.AIContext;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EntityStatus;
import org.openmetadata.schema.type.MetricExpressionLanguage;
import org.openmetadata.schema.type.TermRelation;
import org.openmetadata.schema.type.aicontext.AssetContext;
import org.openmetadata.schema.type.aicontext.ConceptBinding;
import org.openmetadata.schema.type.aicontext.ConceptContext;
import org.openmetadata.service.aicontext.answer.EntityLinks;

class ConceptCardBuilderTest {
  private static final String BASE = "https://acme.test";
  private final ConceptCardBuilder builder = new ConceptCardBuilder(new EntityLinks(BASE));

  private static EntityReference reference(String type, String name, String fqn) {
    return new EntityReference().withType(type).withName(name).withFullyQualifiedName(fqn);
  }

  private static ConceptBinding binding(String asset, String column, String type) {
    return new ConceptBinding()
        .withAssetFqn(asset)
        .withAssetType(type)
        .withColumn(column)
        .withDataType("BIGINT");
  }

  private static AIContext context(ConceptContext concept, EntityReference... owners) {
    return new AIContext()
        .withOwners(List.of(owners))
        .withAssetContext(new AssetContext().withConceptContext(concept));
  }

  @Test
  void aTermCardCarriesWhatTheCallerMayRead() {
    GlossaryTerm term =
        new GlossaryTerm()
            .withName("ActiveCustomer")
            .withDisplayName("Active customer")
            .withFullyQualifiedName("Sales.Customer.ActiveCustomer")
            .withDescription("An account that billed in the last 90 days. Trials are excluded.")
            .withGlossary(reference("glossary", "Sales", "Sales").withDisplayName("Sales"))
            .withEntityStatus(EntityStatus.APPROVED)
            .withReviewers(List.of(reference("user", "ana", "ana").withDisplayName("Ana")))
            .withUpdatedAt(1798963200000L);
    ConceptContext concept =
        new ConceptContext()
            .withSynonyms(List.of("Active account", "Paying customer"))
            .withRelatedTerms(
                List.of(
                    new TermRelation()
                        .withRelationType("relatedTo")
                        .withTerm(reference("glossaryTerm", "Revenue", "Sales.Revenue")),
                    new TermRelation()
                        .withRelationType("notToBeConfusedWith")
                        .withTerm(
                            reference("glossaryTerm", "Customer", "Finance.Customer")
                                .withDescription(
                                    "Counts every billing account. It includes inactive ones."))))
            .withBindings(
                List.of(
                    binding("db.analytics.dim_customer", null, "table"),
                    binding(
                        "db.analytics.dim_customer",
                        "db.analytics.dim_customer.is_active",
                        "table"),
                    binding("db.analytics.fct_subscriptions", null, "table")))
            .withTotalAssets(42)
            .withTruncated(false);

    ConceptCard card =
        builder.build(
            context(
                concept, reference("team", "SalesOps", "SalesOps").withDisplayName("Sales Ops")),
            term);

    assertThat(card.getKind()).isEqualTo(ConceptCardKind.GLOSSARY_TERM);
    assertThat(card.getName()).isEqualTo("ActiveCustomer");
    assertThat(card.getDisplayName()).isEqualTo("Active customer");
    assertThat(card.getUrl()).isEqualTo("https://acme.test/glossary/Sales.Customer.ActiveCustomer");
    assertThat(card.getContainer().getName()).isEqualTo("Sales");
    assertThat(card.getContainer().getUrl()).isEqualTo("https://acme.test/glossary/Sales");
    assertThat(card.getParentPath()).containsExactly("Customer");
    assertThat(card.getStatus()).isEqualTo("Approved");
    assertThat(card.getOwners()).containsExactly("Sales Ops");
    assertThat(card.getReviewers()).containsExactly("Ana");
    assertThat(card.getUpdatedAt()).isEqualTo(1798963200000L);
    assertThat(card.getSynonyms()).containsExactly("Active account", "Paying customer");
    assertThat(card.getRelated()).hasSize(1);
    assertThat(card.getRelated().get(0).getName()).isEqualTo("Revenue");
    assertThat(card.getRelated().get(0).getRelationType()).isEqualTo("related to");
    assertThat(card.getConfusedWith()).hasSize(1);
    assertThat(card.getConfusedWith().get(0).getName()).isEqualTo("Finance › Customer");
    assertThat(card.getConfusedWith().get(0).getNote()).isEqualTo("Counts every billing account.");
    assertThat(card.getMetric()).isNull();
  }

  @Test
  void aTermWithoutAConfusableRelationHasNoNotToBeConfusedWith() {
    GlossaryTerm term =
        new GlossaryTerm().withName("Revenue").withFullyQualifiedName("Sales.Revenue");
    ConceptContext concept =
        new ConceptContext()
            .withRelatedTerms(
                List.of(
                    new TermRelation()
                        .withRelationType("broader")
                        .withTerm(reference("glossaryTerm", "Income", "Sales.Income"))));

    ConceptCard card = builder.build(context(concept), term);

    assertThat(card.getConfusedWith()).isNull();
    assertThat(card.getRelated().get(0).getRelationType()).isEqualTo("broader");
  }

  @Test
  void appliedToListsOneBindingPerAssetFirstAndCountsAssets() {
    GlossaryTerm term = new GlossaryTerm().withName("T").withFullyQualifiedName("G.T");
    ConceptContext concept =
        new ConceptContext()
            .withBindings(
                List.of(
                    binding("db.s.a", null, "table"),
                    binding("db.s.a", "db.s.a.id", "table"),
                    binding("db.s.a", "db.s.a.name", "table"),
                    binding("db.s.b", "db.s.b.ref", "table"),
                    binding("db.s.c", null, "dashboard")))
            .withTotalAssets(3)
            .withTruncated(true);

    ConceptCard card = builder.build(context(concept), term);

    assertThat(card.getAppliedTo().getTotal()).isEqualTo(3);
    assertThat(card.getAppliedTo().getTruncated()).isTrue();
    assertThat(card.getAppliedTo().getItems())
        .extracting(item -> item.getKind() + "|" + item.getName() + "|" + item.getDetail())
        .containsExactly(
            "Table|a|null",
            "Column|b.ref|bigint",
            "Dashboard|c|null",
            "Column|a.id|bigint",
            "Column|a.name|bigint");
    assertThat(card.getAppliedTo().getItems().get(0).getUrl())
        .isEqualTo("https://acme.test/table/db.s.a");
    assertThat(card.getAppliedTo().getItems().get(2).getUrl())
        .isEqualTo("https://acme.test/dashboard/db.s.c");
  }

  @Test
  void appliedToIsCappedAtTenItems() {
    GlossaryTerm term = new GlossaryTerm().withName("T").withFullyQualifiedName("G.T");
    List<ConceptBinding> bindings =
        java.util.stream.IntStream.range(0, 25)
            .mapToObj(index -> binding("db.s.t" + index, null, "table"))
            .toList();

    ConceptCard card = builder.build(context(new ConceptContext().withBindings(bindings)), term);

    assertThat(card.getAppliedTo().getItems()).hasSize(10);
    assertThat(card.getAppliedTo().getTotal()).isEqualTo(25);
  }

  @Test
  void aMetricCardCarriesWhatDefinesItsNumber() {
    Metric metric =
        new Metric()
            .withName("arr")
            .withDisplayName("ARR")
            .withFullyQualifiedName("arr")
            .withMetricExpression(
                new MetricExpression()
                    .withLanguage(MetricExpressionLanguage.SQL)
                    .withCode("SUM(arr)"))
            .withRelatedMetrics(List.of(reference("metric", "mrr", "mrr").withDisplayName("MRR")))
            .withDomains(
                List.of(reference("domain", "Finance", "Finance").withDisplayName("Finance")));
    ConceptContext concept =
        new ConceptContext()
            .withFilters(List.of("status = 'active'"))
            .withGranularity("MONTH")
            .withUnit("USD");

    ConceptCard card = builder.build(context(concept), metric);

    assertThat(card.getKind()).isEqualTo(ConceptCardKind.METRIC);
    assertThat(card.getContainer().getLabel()).isEqualTo("Metric");
    assertThat(card.getContainer().getName()).isEqualTo("Finance");
    assertThat(card.getMetric().getExpression()).isEqualTo("SUM(arr)");
    assertThat(card.getMetric().getLanguage()).isEqualTo("SQL");
    assertThat(card.getMetric().getFilters()).containsExactly("status = 'active'");
    assertThat(card.getMetric().getGranularity()).isEqualTo("MONTH");
    assertThat(card.getMetric().getUnit()).isEqualTo("USD");
    assertThat(card.getMetric().getRelatedMetrics()).containsExactly("MRR");
    assertThat(card.getUrl()).isEqualTo("https://acme.test/metric/arr");
  }
}
