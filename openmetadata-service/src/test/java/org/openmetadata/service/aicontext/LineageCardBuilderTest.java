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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.LineageCard;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.type.ColumnLineage;
import org.openmetadata.schema.type.Edge;
import org.openmetadata.schema.type.EntityLineage;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.LineageDetails;
import org.openmetadata.service.aicontext.LineageCardBuilder.NodeFacts;
import org.openmetadata.service.aicontext.answer.EntityLinks;

class LineageCardBuilderTest {
  private final Map<UUID, NodeFacts> facts = new HashMap<>();
  private final List<Set<UUID>> checkedRequests = new ArrayList<>();
  private final int[] readCalls = {0};
  private final LineageCardBuilder builder =
      new LineageCardBuilder(
          new EntityLinks("https://acme.test"),
          (nodes, checked) -> {
            readCalls[0]++;
            checkedRequests.add(checked);
            return facts;
          });

  private static EntityReference node(String type, String name, String fqn) {
    return new EntityReference()
        .withId(UUID.nameUUIDFromBytes(fqn.getBytes()))
        .withType(type)
        .withName(name)
        .withFullyQualifiedName(fqn);
  }

  private static Edge edge(EntityReference from, EntityReference to) {
    return new Edge().withFromEntity(from.getId()).withToEntity(to.getId());
  }

  private EntityLineage sample() {
    EntityReference root = node("table", "fct_revenue", "sf.analytics.fct_revenue");
    EntityReference orders = node("table", "orders", "sf.raw.orders");
    EntityReference refunds = node("table", "refunds", "sf.raw.refunds");
    EntityReference fx = node("table", "stg_fx_rates", "sf.stg.stg_fx_rates");
    EntityReference deep = node("table", "src", "sf.ext.src");
    EntityReference overview = node("dashboard", "Revenue Overview", "bi.revenue_overview");
    EntityReference qbr = node("dashboard", "Exec QBR", "bi.exec_qbr");
    EntityReference churn = node("mlmodel", "churn_forecast", "ml.churn_forecast");
    facts.put(root.getId(), new NodeFacts("Tier1", "Gold", "dbt", List.of(), null));
    facts.put(refunds.getId(), new NodeFacts(null, null, null, List.of(), "Freshness failing"));
    facts.put(overview.getId(), new NodeFacts(null, null, null, List.of("finance", "bi"), null));
    facts.put(qbr.getId(), new NodeFacts(null, null, null, List.of("finance"), null));
    facts.put(churn.getId(), new NodeFacts(null, null, null, List.of("ds"), null));
    Edge mapped =
        edge(orders, root)
            .withLineageDetails(
                new LineageDetails()
                    .withColumnsLineage(
                        List.of(
                            new ColumnLineage()
                                .withFromColumns(List.of("sf.raw.orders.amount"))
                                .withToColumn("sf.analytics.fct_revenue.net_amount"))));
    return new EntityLineage()
        .withEntity(root)
        .withNodes(List.of(orders, refunds, fx, deep, overview, qbr, churn))
        .withUpstreamEdges(List.of(mapped, edge(refunds, root), edge(fx, refunds), edge(deep, fx)))
        .withDownstreamEdges(List.of(edge(root, overview), edge(root, qbr), edge(root, churn)));
  }

  @Test
  void theCardHoldsTwoHopsEachWayWithTheRootFacts() {
    LineageCard card = builder.build(sample(), 0);

    assertThat(card.getRoot().getName()).isEqualTo("fct_revenue");
    assertThat(card.getRoot().getTier()).isEqualTo("Tier1");
    assertThat(card.getRoot().getCertification()).isEqualTo("Gold");
    assertThat(card.getRoot().getDetail()).isEqualTo("dbt");
    assertThat(card.getRoot().getUrl())
        .isEqualTo("https://acme.test/table/sf.analytics.fct_revenue");
    assertThat(card.getUpstream())
        .extracting(node -> node.getName() + "@" + node.getHop())
        .containsExactly("orders@1", "refunds@1", "stg_fx_rates@2");
    assertThat(card.getUpstreamTotal()).isEqualTo(3);
    assertThat(card.getUpstreamHops()).isEqualTo(3);
    assertThat(card.getDownstream())
        .extracting(node -> node.getEntityType() + ":" + node.getName())
        .containsExactly(
            "dashboard:Exec QBR", "dashboard:Revenue Overview", "mlmodel:churn_forecast");
    assertThat(card.getDownstreamHops()).isEqualTo(1);
  }

  @Test
  void aFailingUpstreamAssetIsFlaggedAndCountedInTheImpact() {
    LineageCard card = builder.build(sample(), 0);

    assertThat(card.getUpstream().get(1).getHasFailingCheck()).isTrue();
    assertThat(card.getUpstream().get(1).getFailingLabel()).isEqualTo("Freshness failing");
    assertThat(card.getUpstream().get(0).getHasFailingCheck()).isFalse();
    assertThat(card.getImpact().getFailingUpstream()).containsExactly("raw.refunds");
    assertThat(card.getImpact().getDownstream())
        .extracting(group -> group.getEntityType() + "=" + group.getCount())
        .containsExactly("dashboard=2", "mlmodel=1");
    assertThat(card.getImpact().getTeams()).isEqualTo(3);
  }

  @Test
  void aSingleBatchedLookupAsksForTheFailingChecksOfTheShownTablesOnly() {
    LineageCard card = builder.build(sample(), 2);

    assertThat(readCalls[0]).isEqualTo(1);
    assertThat(checkedRequests.get(0))
        .containsExactlyInAnyOrder(
            UUID.nameUUIDFromBytes("sf.raw.orders".getBytes()),
            UUID.nameUUIDFromBytes("sf.raw.refunds".getBytes()),
            UUID.nameUUIDFromBytes("sf.stg.stg_fx_rates".getBytes()));
    assertThat(card.getHiddenNodes()).isEqualTo(2);
  }

  @Test
  void columnMappingsComeFromTheEdgesThatTouchTheAsset() {
    LineageCard card = builder.build(sample(), 0);

    assertThat(card.getColumns())
        .singleElement()
        .satisfies(
            mapping -> {
              assertThat(mapping.getFrom()).isEqualTo("orders.amount");
              assertThat(mapping.getTo()).isEqualTo("fct_revenue.net_amount");
            });
    assertThat(card.getColumnsTotal()).isEqualTo(1);
  }

  @Test
  void aSideIsCappedAndSaysHowManyThereAre() {
    EntityReference root = node("table", "root", "s.d.sc.root");
    List<EntityReference> sources = new ArrayList<>();
    List<Edge> edges = new ArrayList<>();
    for (int index = 0; index < 9; index++) {
      EntityReference source = node("table", "t" + index, "s.d.sc.t" + index);
      sources.add(source);
      edges.add(edge(source, root));
    }
    EntityLineage lineage =
        new EntityLineage().withEntity(root).withNodes(sources).withUpstreamEdges(edges);

    LineageCard card = builder.build(lineage, 0);

    assertThat(card.getUpstream()).hasSize(6);
    assertThat(card.getUpstreamTotal()).isEqualTo(9);
    assertThat(card.getDownstream()).isEmpty();
    assertThat(card.getImpact().getDownstream()).isEmpty();
  }

  @Test
  void aCycleDoesNotLoopForever() {
    EntityReference root = node("table", "a", "s.d.sc.a");
    EntityReference other = node("table", "b", "s.d.sc.b");
    EntityLineage lineage =
        new EntityLineage()
            .withEntity(root)
            .withNodes(List.of(other))
            .withUpstreamEdges(List.of(edge(other, root), edge(root, other)));

    LineageCard card = builder.build(lineage, 0);

    assertThat(card.getUpstream()).extracting(node -> node.getName()).containsExactly("b");
  }

  private static TestCase failing(String name, String definition) {
    return new TestCase()
        .withName(name)
        .withTestDefinition(new EntityReference().withName(definition));
  }

  @Test
  void aFailingFreshnessTestIsNamedAndOtherFailuresAreCounted() {
    assertThat(LiveLineageFacts.failingLabel(List.of())).isNull();
    assertThat(LiveLineageFacts.failingLabel(List.of(failing("a", "tableRowCountToEqual"))))
        .isEqualTo("1 check failing");
    assertThat(
            LiveLineageFacts.failingLabel(
                List.of(
                    failing("a", "tableRowCountToEqual"), failing("b", "columnValuesToBeUnique"))))
        .isEqualTo("2 checks failing");
    assertThat(
            LiveLineageFacts.failingLabel(
                List.of(
                    failing("a", "columnValuesToBeUnique"), failing("b", "tableDataFreshness"))))
        .isEqualTo("Freshness failing");
  }
}
