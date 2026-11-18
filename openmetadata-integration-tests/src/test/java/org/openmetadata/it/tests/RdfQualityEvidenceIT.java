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
package org.openmetadata.it.tests;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.openmetadata.it.util.RdfGraphAssertions.BASE_URI;
import static org.openmetadata.it.util.RdfGraphAssertions.awaitCount;
import static org.openmetadata.it.util.RdfGraphAssertions.awaitPresent;
import static org.openmetadata.it.util.RdfGraphAssertions.iri;
import static org.openmetadata.it.util.RdfGraphAssertions.om;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openmetadata.it.bootstrap.TestSuiteBootstrap;
import org.openmetadata.it.util.RdfTestUtils;
import org.openmetadata.it.util.SdkClients;
import org.openmetadata.it.util.TestNamespace;
import org.openmetadata.it.util.TestNamespaceExtension;
import org.openmetadata.schema.api.configuration.rdf.RdfConfiguration;
import org.openmetadata.schema.api.data.CreateTableProfile;
import org.openmetadata.schema.api.data.UpdateColumn;
import org.openmetadata.schema.api.tests.CreateTestCaseResult;
import org.openmetadata.schema.entity.data.Database;
import org.openmetadata.schema.entity.data.DatabaseSchema;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.entity.services.DatabaseService;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.tests.type.TestCaseStatus;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnDataType;
import org.openmetadata.schema.type.ColumnProfile;
import org.openmetadata.schema.type.TableProfile;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.sdk.client.OpenMetadataClient;
import org.openmetadata.sdk.fluent.DatabaseSchemas;
import org.openmetadata.sdk.fluent.DatabaseServices;
import org.openmetadata.sdk.fluent.Databases;
import org.openmetadata.sdk.fluent.Tables;
import org.openmetadata.sdk.fluent.builders.TestCaseBuilder;
import org.openmetadata.sdk.network.HttpMethod;
import org.openmetadata.sdk.network.RequestOptions;
import org.openmetadata.service.Entity;
import org.openmetadata.service.rdf.RdfUpdater;

/**
 * Profiles, test results and incidents written through the API reach the live graph as their
 * latest values only, follow the PII rule and leave with their entities
 * (ADR:2026-11-18-the-rdf-graph-carries-the-latest-quality-evidence).
 */
@ExtendWith(TestNamespaceExtension.class)
public class RdfQualityEvidenceIT {
  private static final Map<String, String> HARD_DELETE =
      Map.of("hardDelete", "true", "recursive", "true");
  private static final long MINUTE = 60_000L;

  private final OpenMetadataClient client = SdkClients.adminClient();

  @BeforeAll
  static void enableLiveProjection() {
    assumeTrue(RdfTestUtils.isRdfEnabled(), "Run with the RDF integration-test profile");
    if (!RdfUpdater.isEnabled()) {
      RdfUpdater.initialize(
          new RdfConfiguration()
              .withEnabled(true)
              .withBaseUri(URI.create(BASE_URI))
              .withStorageType(RdfConfiguration.StorageType.FUSEKI)
              .withRemoteEndpoint(URI.create(TestSuiteBootstrap.getFusekiEndpoint()))
              .withDataset("openmetadata")
              .withUsername("admin")
              .withPassword("test-admin"));
    }
  }

  @Test
  void aProfileReachesTheGraphAsLatestFactsAndLeavesWithTheTable(final TestNamespace namespace) {
    final Table table = table(namespace);
    final long first = System.currentTimeMillis() - MINUTE;
    putProfile(table, first, 100.0, 4.0);
    awaitCount(fact(iri("table", table), "profileRowCount", "100"), 1);
    awaitCount(fact(column(table, "amount"), "profileNullCount", "4"), 1);

    putProfile(table, first + MINUTE / 2, 250.0, 9.0);
    awaitCount(fact(iri("table", table), "profileRowCount", "250"), 1);
    awaitCount(column(table, "amount") + " " + om("profileNullCount") + " ?value", 1);
    awaitCount(iri("table", table) + " " + om("profileRowCount") + " ?value", 1);
    awaitCount(profileFacts(column(table, "email")), 0);

    client.tables().delete(table.getId().toString(), HARD_DELETE);
    awaitCount(profileFacts(iri("table", table)), 0);
    awaitCount(profileFacts(column(table, "amount")), 0);
  }

  @Test
  void aTestCarriesItsLatestResultAndOpenIncidentUntilTheColumnTurnsSensitive(
      final TestNamespace namespace) throws Exception {
    final Table table = table(namespace);
    final TestCase onAmount = columnTest(namespace, table, "amount");
    final String test = "<" + BASE_URI + "entity/testCase/" + onAmount.getId() + ">";
    final long ranAt = System.currentTimeMillis() - MINUTE;

    postResult(onAmount, ranAt, TestCaseStatus.Failed, 3L);
    awaitCount(test + " " + om("latestResultStatus") + " \"Failed\"", 1);
    awaitCount(
        test + " " + om("hasTestResult") + "/" + om("failedRows") + " ?rows FILTER(?rows = 3)", 1);
    awaitPresent(test + " " + om("hasOpenIncident") + "/" + om("incidentState") + " ?state");

    enableAutoCloseIncident(onAmount);
    postResult(onAmount, ranAt + MINUTE / 2, TestCaseStatus.Success, 0L);
    awaitCount(test + " " + om("latestResultStatus") + " \"Success\"", 1);
    awaitCount(test + " " + om("hasTestResult") + "/" + om("failedRows") + " ?rows", 1);
    awaitCount(test + " " + om("hasOpenIncident") + " ?incident", 0);

    tagSensitive(table, "amount");
    awaitCount(test + " " + om("hasTestResult") + " ?result", 0);
    awaitCount(test + " " + om("latestResultStatus") + " ?status", 0);

    client.testCases().delete(onAmount.getId().toString(), HARD_DELETE);
    awaitCount(
        "{ "
            + test
            + " ?p ?o } UNION { <"
            + BASE_URI
            + "entity/testCase/"
            + onAmount.getId()
            + "/result/latest> ?p ?o }",
        0);
  }

  private static String fact(final String subject, final String predicate, final String number) {
    return subject + " " + om(predicate) + " ?value FILTER(?value = " + number + ")";
  }

  private static String profileFacts(final String subject) {
    return subject + " ?p ?value FILTER(STRSTARTS(STR(?p), \"" + BASE_URI + "ontology/profile\"))";
  }

  private static String column(final Table table, final String name) {
    final String fqn = table.getFullyQualifiedName() + "." + name;
    return "<" + BASE_URI + "entity/column/" + URLEncoder.encode(fqn, StandardCharsets.UTF_8) + ">";
  }

  private static Table table(final TestNamespace namespace) {
    final String tag = namespace.uniqueShortId();
    final DatabaseService service =
        namespace.trackRoot(
            Entity.DATABASE_SERVICE,
            DatabaseServices.builder()
                .name("qsvc_" + tag)
                .connection(
                    DatabaseServices.postgresConnection()
                        .hostPort("localhost:5432")
                        .username("test")
                        .build())
                .create());
    final Database database =
        Databases.create().name("qdb_" + tag).in(service.getFullyQualifiedName()).execute();
    final DatabaseSchema schema =
        DatabaseSchemas.create().name("qsch_" + tag).in(database.getFullyQualifiedName()).execute();
    return Tables.create()
        .name("orders_" + tag)
        .inSchema(schema.getFullyQualifiedName())
        .withColumns(
            List.of(
                new Column().withName("amount").withDataType(ColumnDataType.DOUBLE),
                new Column().withName("status").withDataType(ColumnDataType.STRING),
                new Column()
                    .withName("email")
                    .withDataType(ColumnDataType.STRING)
                    .withTags(List.of(sensitive()))))
        .execute();
  }

  private void putProfile(
      final Table table, final long timestamp, final double rowCount, final double nullCount) {
    final CreateTableProfile profile =
        new CreateTableProfile()
            .withTableProfile(
                new TableProfile()
                    .withTimestamp(timestamp)
                    .withRowCount(rowCount)
                    .withColumnCount(3.0))
            .withColumnProfile(
                List.of(
                    new ColumnProfile()
                        .withName("amount")
                        .withTimestamp(timestamp)
                        .withNullCount(nullCount)
                        .withMin(-3.0)
                        .withMax(99.0),
                    new ColumnProfile()
                        .withName("email")
                        .withTimestamp(timestamp)
                        .withNullCount(0.0)
                        .withMinLength(5.0)));
    client
        .getHttpClient()
        .execute(
            HttpMethod.PUT, "/v1/tables/" + table.getId() + "/tableProfile", profile, Table.class);
  }

  private TestCase columnTest(final TestNamespace namespace, final Table table, final String name) {
    return TestCaseBuilder.create(client)
        .name(namespace.prefix(name + "_not_null"))
        .forColumn(table, name)
        .testDefinition("columnValuesToBeNotNull")
        .create();
  }

  private void postResult(
      final TestCase testCase,
      final long timestamp,
      final TestCaseStatus status,
      final long failed) {
    client
        .testCaseResults()
        .create(
            testCase.getFullyQualifiedName(),
            new CreateTestCaseResult()
                .withTimestamp(timestamp)
                .withTestCaseStatus(status)
                .withResult(status.value())
                .withPassedRows(10L - failed)
                .withFailedRows(failed));
  }

  private void enableAutoCloseIncident(final TestCase testCase) {
    client
        .getHttpClient()
        .executeForString(
            HttpMethod.PATCH,
            "/v1/dataQuality/testCases/" + testCase.getId(),
            "[{\"op\":\"add\",\"path\":\"/autoCloseIncident\",\"value\":true}]",
            RequestOptions.builder().header("Content-Type", "application/json-patch+json").build());
  }

  private void tagSensitive(final Table table, final String name) {
    client
        .getHttpClient()
        .executeForString(
            HttpMethod.PUT,
            "/v1/columns/name/"
                + URLEncoder.encode(
                    table.getFullyQualifiedName() + "." + name, StandardCharsets.UTF_8)
                + "?entityType=table",
            new UpdateColumn().withTags(List.of(sensitive())));
  }

  private static TagLabel sensitive() {
    return new TagLabel()
        .withTagFQN("PII.Sensitive")
        .withSource(TagLabel.TagSource.CLASSIFICATION)
        .withLabelType(TagLabel.LabelType.MANUAL);
  }
}
