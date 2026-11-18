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
package org.openmetadata.service.rdf.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openmetadata.service.rdf.RdfQualityFixtures.BASE;
import static org.openmetadata.service.rdf.RdfQualityFixtures.PROFILED_AT;
import static org.openmetadata.service.rdf.RdfQualityFixtures.TABLE_FQN;
import static org.openmetadata.service.rdf.RdfQualityFixtures.TABLE_URI;
import static org.openmetadata.service.rdf.RdfQualityFixtures.TEST_URI;
import static org.openmetadata.service.rdf.RdfQualityFixtures.assignedIncident;
import static org.openmetadata.service.rdf.RdfQualityFixtures.columnTest;
import static org.openmetadata.service.rdf.RdfQualityFixtures.failedResult;
import static org.openmetadata.service.rdf.RdfQualityFixtures.profiledTable;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.ColumnConstraint;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.rdf.RdfUtils;
import org.openmetadata.service.rdf.translator.JsonLdTranslator;
import org.openmetadata.service.rdf.translator.ReferenceScope;

/** Every literal the projection stores on a node is on its card, whatever kind of node it is. */
class NodeCardValuesTest {

  private static final String AMOUNT = RdfUtils.columnUri(BASE, TABLE_FQN + ".amount");
  private static final String INCIDENT = TEST_URI + "/incident/open";

  private final JsonLdTranslator translator =
      new JsonLdTranslator(
          JsonUtils.getObjectMapper(), BASE, (type, fqns) -> Map.of(), ReferenceScope.ALL);

  @BeforeAll
  static void registerEntityTypes() {
    EntityInterface.CANONICAL_ENTITY_NAME_MAP.put("table", "table");
    EntityInterface.CANONICAL_ENTITY_NAME_MAP.put("testcase", "testCase");
  }

  private static NodeCard card(final Model model, final String node) {
    return NodeCardReaderTest.reader(InMemoryGraph.of(model)).read(List.of(node)).getFirst();
  }

  private static Map<String, CardValue> values(final NodeCard card) {
    return card.values().stream().collect(Collectors.toMap(CardValue::property, value -> value));
  }

  private Model profiledOrders() {
    final Table orders = profiledTable(100.0, 4.0);
    orders.getColumns().getFirst().setConstraint(ColumnConstraint.NOT_NULL);
    return translator.toRdf(orders);
  }

  @Test
  void aTableCardShowsItsProfileAndEveryOtherStoredValue() {
    final Map<String, CardValue> values = values(card(profiledOrders(), TABLE_URI));

    assertThat(values.values())
        .extracting(CardValue::property, CardValue::type, CardValue::value)
        .contains(
            tuple("om:profileRowCount", "decimal", "100.0"),
            tuple("om:profileColumnCount", "decimal", "2.0"),
            tuple("om:profiledAt", "dateTime", "2025-11-13T02:13:20Z"),
            tuple("om:isDeleted", "boolean", "false"));
    assertThat(values).doesNotContainKeys("rdfs:label", "om:fullyQualifiedName");
  }

  @Test
  void aColumnCardShowsItsDataTypeNullabilityAndProfile() {
    final Map<String, CardValue> values = values(card(profiledOrders(), AMOUNT));

    assertThat(values.values())
        .extracting(CardValue::property, CardValue::type, CardValue::value)
        .contains(
            tuple("om:columnDataType", null, "DECIMAL"),
            tuple("om:isNullable", "boolean", "false"),
            tuple("om:profileNullProportion", "decimal", "0.04"),
            tuple("om:profileMax", "decimal", "990.5"));
  }

  @Test
  void aTestCaseCardShowsItsLatestResult() {
    final Model model = translator.toRdf(columnTest("amount", failedResult(PROFILED_AT, 10L)));

    assertThat(card(model, TEST_URI).values())
        .extracting(CardValue::property, CardValue::type, CardValue::value)
        .contains(
            tuple("om:latestResultStatus", null, "Failed"),
            tuple("om:latestResultAt", "dateTime", "2025-11-13T02:13:20Z"));
  }

  @Test
  void anOpenIncidentCardShowsItsStateAndSeverity() {
    final Model model =
        translator.toRdf(
            columnTest("amount", failedResult(PROFILED_AT, 10L))
                .withIncidentStatus(assignedIncident()));

    final NodeCard incident = card(model, INCIDENT);

    assertThat(incident.inGraph()).isTrue();
    assertThat(incident.values())
        .extracting(CardValue::property, CardValue::value)
        .contains(tuple("om:incidentState", "Assigned"), tuple("om:incidentSeverity", "Severity2"));
  }

  @Test
  void aLongValueIsCutAtTheStatedLimitAndSaysHowLongItIs() {
    final String definition = "SELECT amount FROM orders WHERE amount > 0 ".repeat(30).trim();
    final NodeCard card =
        card(turtle("<%s> om:viewDefinition \"%s\" .".formatted(TABLE_URI, definition)), TABLE_URI);

    final CardValue value = values(card).get("om:viewDefinition");

    assertThat(definition.length()).isGreaterThan(CardValue.VALUE_LIMIT);
    assertThat(value.value()).hasSize(CardValue.VALUE_LIMIT + 1).endsWith("…");
    assertThat(definition).startsWith(value.value().substring(0, CardValue.VALUE_LIMIT));
    assertThat(value.length()).isEqualTo(definition.length());
  }

  @Test
  void aPropertyWithSeveralValuesShowsTheFirstAndLastAndHowManyThereAre() {
    final NodeCard card =
        card(
            turtle(
                "<%s> om:synonym \"cart\" , \"basket\" , \"order\" , \"purchase\" , \"sale\" ."
                    .formatted(TABLE_URI)),
            TABLE_URI);

    assertThat(values(card).get("om:synonym"))
        .isEqualTo(new CardValue("om:synonym", null, "basket", "sale", 5, null));
  }

  @Test
  void aDescriptionCutInTheHeaderSaysHowLongItIs() {
    final NodeCard card =
        NodeCardReaderTest.reader(InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH))
            .read(List.of(NodeCardReaderTest.ORDERS))
            .getFirst();

    assertThat(card.facts().descriptionLength())
        .isEqualTo("Orders placed in the web shop. ".repeat(20).trim().length());
    assertThat(values(card)).doesNotContainKey("dct:description");
  }

  private static Model turtle(final String body) {
    return InMemoryGraph.model(body);
  }
}
