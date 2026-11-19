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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.ConceptRelations;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.TermRelation;
import org.openmetadata.service.aicontext.ConceptRelationsBuilder.Catalog;
import org.openmetadata.service.aicontext.ConceptRelationsBuilder.ForeignKey;
import org.openmetadata.service.aicontext.ConceptRelationsBuilder.MappedTable;
import org.openmetadata.service.aicontext.answer.EntityLinks;

class ConceptRelationsBuilderTest {
  private final Map<String, MappedTable> tables = new HashMap<>();
  private final Map<String, List<ForeignKey>> keys = new HashMap<>();
  private final Set<String> hidden = new HashSet<>();
  private final Catalog catalog =
      new Catalog() {
        @Override
        public boolean canView(String entityType, String fqn) {
          return !hidden.contains(fqn);
        }

        @Override
        public Optional<MappedTable> tableOf(String termFqn) {
          return Optional.ofNullable(tables.get(termFqn));
        }

        @Override
        public List<ForeignKey> foreignKeys(String tableFqn) {
          return keys.getOrDefault(tableFqn, List.of());
        }
      };
  private final ConceptRelationsBuilder builder =
      new ConceptRelationsBuilder(new EntityLinks("https://acme.test"), catalog);

  private static GlossaryTerm term(String name, String fqn, String... related) {
    List<TermRelation> relations =
        java.util.Arrays.stream(related)
            .map(
                relatedFqn ->
                    new TermRelation()
                        .withRelationType("relatedTo")
                        .withTerm(
                            new EntityReference()
                                .withType("glossaryTerm")
                                .withName(relatedFqn.substring(relatedFqn.indexOf('.') + 1))
                                .withFullyQualifiedName(relatedFqn)))
            .toList();
    return new GlossaryTerm()
        .withName(name)
        .withFullyQualifiedName(fqn)
        .withGlossary(new EntityReference().withType("glossary").withName("Commercial"))
        .withRelatedTerms(relations);
  }

  @Test
  void eachRelatedTermCarriesItsTable() {
    tables.put("Commercial.Order", new MappedTable("db.analytics.fct_orders", true));
    tables.put("Commercial.Revenue", new MappedTable("db.analytics.fct_revenue", false));

    ConceptRelations result =
        builder.build(
            term(
                "Customer",
                "Commercial.Customer",
                "Commercial.Order",
                "Commercial.Revenue",
                "Commercial.Party"),
            null);

    assertThat(result.getConcept().getName()).isEqualTo("Customer");
    assertThat(result.getGlossary()).isEqualTo("Commercial");
    assertThat(result.getTotalRelations()).isEqualTo(3);
    assertThat(result.getRelations())
        .extracting(
            relation ->
                relation.getRelation()
                    + "|"
                    + relation.getTerm().getName()
                    + "|"
                    + (relation.getTable() == null
                        ? null
                        : relation.getTable().getName() + relation.getTable().getCertified()))
        .containsExactly(
            "related to|Order|fct_orderstrue",
            "related to|Revenue|fct_revenuefalse",
            "related to|Party|null");
    assertThat(result.getRelations().get(0).getTable().getUrl())
        .isEqualTo("https://acme.test/table/db.analytics.fct_orders");
    assertThat(result.getRelations().get(0).getTerm().getUrl())
        .isEqualTo("https://acme.test/glossary/Commercial.Order");
    assertThat(result.getJoinPath()).isEmpty();
    assertThat(result.getTarget()).isNull();
  }

  @Test
  void aRelatedTermTheCallerCannotViewIsNeitherNamedNorCounted() {
    hidden.add("Commercial.Secret");

    ConceptRelations result =
        builder.build(
            term("Customer", "Commercial.Customer", "Commercial.Order", "Commercial.Secret"), null);

    assertThat(result.getRelations())
        .extracting(relation -> relation.getTerm().getName())
        .containsExactly("Order");
    assertThat(result.getTotalRelations()).isEqualTo(1);
  }

  @Test
  void relationsAreCappedAtEightAndTheTotalSaysHowManyThereAre() {
    String[] related =
        java.util.stream.IntStream.range(0, 11)
            .mapToObj(index -> "Commercial.T" + index)
            .toArray(String[]::new);

    ConceptRelations result = builder.build(term("Customer", "Commercial.Customer", related), null);

    assertThat(result.getRelations()).hasSize(8);
    assertThat(result.getTotalRelations()).isEqualTo(11);
  }

  @Test
  void theJoinPathFollowsDeclaredForeignKeysBetweenTheTwoTables() {
    tables.put("Commercial.Customer", new MappedTable("db.analytics.dim_customer", true));
    tables.put("Commercial.Revenue", new MappedTable("db.analytics.fct_revenue", true));
    keys.put(
        "db.analytics.fct_revenue",
        List.of(new ForeignKey("order_id", "db.analytics.fct_orders", "order_id")));
    keys.put(
        "db.analytics.fct_orders",
        List.of(new ForeignKey("customer_id", "db.analytics.dim_customer", "customer_id")));

    ConceptRelations result =
        builder.build(
            term("Customer", "Commercial.Customer"), term("Revenue", "Commercial.Revenue"));

    assertThat(result.getTarget().getName()).isEqualTo("Revenue");
    assertThat(result.getJoinPath())
        .extracting(step -> step.getTable() + "." + step.getColumn())
        .containsExactly(
            "dim_customer.customer_id",
            "fct_orders.customer_id",
            "fct_orders.order_id",
            "fct_revenue.order_id");
  }

  @Test
  void aKeyThatPointsTheOtherWayIsFoundFromTheTargetEnd() {
    tables.put("Commercial.Customer", new MappedTable("db.analytics.dim_customer", true));
    tables.put("Commercial.Order", new MappedTable("db.analytics.fct_orders", true));
    keys.put(
        "db.analytics.fct_orders",
        List.of(new ForeignKey("customer_id", "db.analytics.dim_customer", "id")));

    ConceptRelations result =
        builder.build(term("Customer", "Commercial.Customer"), term("Order", "Commercial.Order"));

    assertThat(result.getJoinPath())
        .extracting(step -> step.getTable() + "." + step.getColumn())
        .containsExactly("dim_customer.id", "fct_orders.customer_id");
  }

  @Test
  void twoTablesThatShareADimensionJoinThroughIt() {
    tables.put("Commercial.ARR", new MappedTable("db.finance.arr_daily", true));
    tables.put("Commercial.Bookings", new MappedTable("db.core.orders", true));
    keys.put(
        "db.finance.arr_daily", List.of(new ForeignKey("customer_id", "db.core.customers", "id")));
    keys.put("db.core.orders", List.of(new ForeignKey("customer_id", "db.core.customers", "id")));

    ConceptRelations result =
        builder.build(term("ARR", "Commercial.ARR"), term("Bookings", "Commercial.Bookings"));

    assertThat(result.getJoinPath())
        .extracting(step -> step.getTable() + "." + step.getColumn())
        .containsExactly("arr_daily.customer_id", "customers.id", "orders.customer_id");
  }

  @Test
  void aPathLongerThanTheHopLimitThroughASharedTableIsNotReturned() {
    tables.put("Commercial.A", new MappedTable("db.s.a", true));
    tables.put("Commercial.B", new MappedTable("db.s.b", true));
    keys.put("db.s.a", List.of(new ForeignKey("x", "db.s.a2", "x")));
    keys.put("db.s.a2", List.of(new ForeignKey("y", "db.s.hub", "y")));
    keys.put("db.s.b", List.of(new ForeignKey("x", "db.s.b2", "x")));
    keys.put("db.s.b2", List.of(new ForeignKey("y", "db.s.hub", "y")));

    ConceptRelations result = builder.build(term("A", "Commercial.A"), term("B", "Commercial.B"));

    assertThat(result.getJoinPath()).isEmpty();
  }

  @Test
  void noDeclaredKeyMeansNoJoinPathRatherThanAGuess() {
    tables.put("Commercial.Customer", new MappedTable("db.analytics.dim_customer", true));
    tables.put("Commercial.Revenue", new MappedTable("db.analytics.fct_revenue", true));

    ConceptRelations result =
        builder.build(
            term("Customer", "Commercial.Customer"), term("Revenue", "Commercial.Revenue"));

    assertThat(result.getTarget().getName()).isEqualTo("Revenue");
    assertThat(result.getJoinPath()).isEmpty();
  }

  @Test
  void aTableTheCallerCannotViewIsNotWalkedThrough() {
    tables.put("Commercial.Customer", new MappedTable("db.analytics.dim_customer", true));
    tables.put("Commercial.Revenue", new MappedTable("db.analytics.fct_revenue", true));
    keys.put(
        "db.analytics.fct_revenue",
        List.of(new ForeignKey("order_id", "db.analytics.fct_orders", "order_id")));
    keys.put(
        "db.analytics.fct_orders",
        List.of(new ForeignKey("customer_id", "db.analytics.dim_customer", "customer_id")));
    hidden.add("db.analytics.fct_orders");

    ConceptRelations result =
        builder.build(
            term("Customer", "Commercial.Customer"), term("Revenue", "Commercial.Revenue"));

    assertThat(result.getJoinPath()).isEmpty();
  }
}
