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

package org.openmetadata.service.rdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.update.UpdateAction;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.api.configuration.rdf.RdfConfiguration;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.tests.type.TestCaseResult;
import org.openmetadata.schema.tests.type.TestCaseStatus;
import org.openmetadata.schema.tests.type.TestResultValue;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnDataType;
import org.openmetadata.schema.type.ColumnProfile;
import org.openmetadata.schema.type.TableProfile;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.rdf.storage.RdfStorageInterface;
import org.openmetadata.service.rdf.translator.JsonLdTranslator;
import org.openmetadata.service.rdf.translator.ReferenceScope;

class RdfQualityEvidenceTest {
  static final String BASE = "https://open-metadata.org/";
  static final String OM = BASE + "ontology/";
  static final String DQV = "http://www.w3.org/ns/dqv#";
  static final String PROV = "http://www.w3.org/ns/prov#";
  static final String TABLE_FQN = "svc.db.schema.orders";
  static final UUID TABLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  static final String TABLE_URI = BASE + "entity/table/" + TABLE_ID;
  static final long PROFILED_AT = 1_763_000_000_000L;
  static final String GRAPH = BASE + "graph/knowledge";
  static final UUID TEST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  static final String TEST_URI = BASE + "entity/testCase/" + TEST_ID;

  private final JsonLdTranslator translator =
      new JsonLdTranslator(
          JsonUtils.getObjectMapper(), BASE, (type, fqns) -> Map.of(), ReferenceScope.ALL);

  @BeforeAll
  static void registerEntityTypes() {
    EntityInterface.CANONICAL_ENTITY_NAME_MAP.put("table", "table");
    EntityInterface.CANONICAL_ENTITY_NAME_MAP.put("testcase", "testCase");
  }

  @Test
  void aProfiledTableEmitsItsLatestMeasuresWithStableNames() {
    final Model model = translator.toRdf(profiledTable(100.0, 4.0));

    assertEquals(
        Map.of("ColumnCountMetric", "2.0", "RowCountMetric", "100.0"),
        measures(model, model.createResource(TABLE_URI)));
    assertEquals(
        new TreeMap<>(
            Map.of(
                "DistinctCountMetric", "95.0",
                "MaxMetric", "990.5",
                "MeanMetric", "120.25",
                "MinMetric", "-3.0",
                "NullCountMetric", "4.0",
                "NullProportionMetric", "0.04",
                "UniqueCountMetric", "90.0",
                "ValuesCountMetric", "96.0")),
        measures(model, column(model, "amount")));
    assertEquals(
        Map.of("MaxLengthMetric", "12.0", "MinLengthMetric", "3.0", "NullCountMetric", "0.0"),
        measures(model, column(model, "status")));
    assertEquals(
        Set.of(
            TABLE_URI + "/measurement/RowCountMetric",
            TABLE_URI + "/measurement/ColumnCountMetric"),
        measurementIris(model, model.createResource(TABLE_URI)));
    assertEquals(
        Set.of(timestamp(model)),
        Set.copyOf(
            model.listObjectsOfProperty(model.createProperty(PROV, "generatedAtTime")).toList()));
  }

  @Test
  void theLatestStoredProfilesAreAttachedToTheTableAndItsNestedColumnsInOneRead() {
    final Table table = profiledTable(100.0, 4.0);
    final FakeEvidence evidence = FakeEvidence.storing(table);
    final Column zip = nestedColumn("status", "zip");
    table.getColumns().getLast().withChildren(List.of(zip));
    evidence.columnProfiles.put(
        zip.getFullyQualifiedName(), new ColumnProfile().withNullCount(7.0));
    clearProfiles(table);

    new RdfQualityEvidence(evidence).attach("table", List.of(table));

    assertEquals(100.0, table.getProfile().getRowCount());
    assertEquals(4.0, table.getColumns().getFirst().getProfile().getNullCount());
    assertEquals(7.0, zip.getProfile().getNullCount());
    assertEquals(
        List.of(List.of(TABLE_FQN + ".amount", TABLE_FQN + ".status", zip.getFullyQualifiedName())),
        evidence.columnReads);
  }

  @Test
  void aPiiSensitiveColumnAndTheFieldsBeneathItCarryNoProfile() {
    final Table table = profiledTable(100.0, 4.0);
    final FakeEvidence evidence = FakeEvidence.storing(table);
    final Column ssn = nestedColumn("status", "ssn");
    evidence.columnProfiles.put(
        ssn.getFullyQualifiedName(), new ColumnProfile().withNullCount(1.0));
    table.getColumns().getFirst().withTags(List.of(sensitive()));
    table.getColumns().getLast().withTags(List.of(sensitive())).withChildren(List.of(ssn));
    clearProfiles(table);

    new RdfQualityEvidence(evidence).attach("table", List.of(table));

    assertNull(table.getColumns().getFirst().getProfile());
    assertNull(table.getColumns().getLast().getProfile());
    assertNull(ssn.getProfile());
    assertEquals("amount", table.getColumns().getFirst().getName());
    assertEquals(List.of(), evidence.columnReads.stream().flatMap(List::stream).toList());
    assertEquals(100.0, table.getProfile().getRowCount());
  }

  @Test
  void aNewProfileLeavesOnlyItsValuesInTheGraphAfterTheLiveRefresh() {
    final Table stored = profiledTable(100.0, 4.0);
    final FakeEvidence evidence = FakeEvidence.storing(stored);
    final Dataset dataset = DatasetFactory.create();
    final RdfRepository repository = liveRepository(dataset, evidence, stored);

    repository.refreshEntity("table", TABLE_ID);
    final Table corrected = profiledTable(250.0, 9.0);
    evidence.tableProfiles.put(TABLE_FQN, corrected.getProfile());
    evidence.columnProfiles.put(
        TABLE_FQN + ".amount", corrected.getColumns().getFirst().getProfile());
    repository.refreshEntity("table", TABLE_ID);

    final Model graph = dataset.getNamedModel(GRAPH);
    assertEquals("250.0", measures(graph, graph.createResource(TABLE_URI)).get("RowCountMetric"));
    assertEquals("9.0", measures(graph, column(graph, "amount")).get("NullCountMetric"));
    assertEquals("0.09", measures(graph, column(graph, "amount")).get("NullProportionMetric"));
    dataset.close();
  }

  private RdfRepository liveRepository(
      final Dataset dataset, final FakeEvidence evidence, final Table stored) {
    final RdfStorageInterface storage = mock(RdfStorageInterface.class);
    doAnswer(
            call -> {
              final String uri = BASE + "entity/" + call.getArgument(0) + "/" + call.getArgument(1);
              UpdateAction.parseExecute(RdfRepository.buildEntityDeleteUpdate(uri), dataset);
              dataset.getNamedModel(GRAPH).add(call.getArgument(2, Model.class));
              return null;
            })
        .when(storage)
        .storeEntity(anyString(), any(UUID.class), any(Model.class));
    final BiFunction<String, UUID, EntityInterface<?>> unprofiledRead =
        (type, id) -> {
          final Table read = JsonUtils.deepCopy(stored, Table.class);
          clearProfiles(read);
          return read;
        };
    return new RdfRepository(
        new RdfConfiguration().withEnabled(true).withBaseUri(URI.create(BASE)),
        storage,
        translator,
        () -> null,
        new RdfProjectionLoaders(
            RdfProjectionLoaders.withQualityEvidence(
                unprofiledRead, new RdfQualityEvidence(evidence)),
            null,
            null,
            null));
  }

  @Test
  void aTestCaseLinksWhatItTestsAndItsLatestResultWithObservedValuesAndBounds() {
    final JsonLdTranslator liveTables =
        new JsonLdTranslator(
            JsonUtils.getObjectMapper(),
            BASE,
            (type, fqns) ->
                "table".equals(type) && fqns.contains(TABLE_FQN)
                    ? Map.of(TABLE_FQN, TABLE_ID)
                    : Map.of(),
            ReferenceScope.LIVE);
    final TestCase testCase = columnTest(failedResult(PROFILED_AT, 10L));

    final Model model = liveTables.toRdf(testCase);

    final Resource test = model.createResource(TEST_URI);
    final Resource result = model.createResource(TEST_URI + "/result/latest");
    assertEquals(Set.of(model.createResource(TABLE_URI)), objects(model, test, OM + "testsEntity"));
    assertEquals(Set.of(column(model, "amount")), objects(model, test, OM + "testsColumn"));
    assertEquals(Set.of(result), objects(model, test, OM + "hasTestResult"));
    assertEquals(
        Map.of(
            RDF.type.getURI(),
            OM + "TestCaseResult",
            OM + "testResultStatus",
            "Failed",
            OM + "testResultMessage",
            "Found min=-3, expected >= 0",
            PROV + "generatedAtTime",
            Instant.ofEpochMilli(PROFILED_AT).toString(),
            OM + "passedRows",
            "90",
            OM + "failedRows",
            "10",
            OM + "minBound",
            "0.0",
            OM + "maxBound",
            "1000.0",
            OM + "hasResultValue",
            TEST_URI + "/result/latest/value/0"),
        properties(model, result));
    assertEquals(
        Map.of(
            RDF.type.getURI(),
            OM + "TestResultValue",
            RDFS.label.getURI(),
            "min",
            DQV + "value",
            "-3"),
        properties(model, model.createResource(TEST_URI + "/result/latest/value/0")));
  }

  @Test
  void aTestCaseWhoseTableIsNotLiveLinksNoTable() {
    final Model model = translator.toRdf(columnTest(failedResult(PROFILED_AT, 10L)));

    assertEquals(Set.of(), objects(model, model.createResource(TEST_URI), OM + "testsEntity"));
  }

  @Test
  void theLatestResultIsAttachedUnlessTheTestedColumnIsPiiSensitive() {
    final Table table = profiledTable(100.0, 4.0);
    final FakeEvidence evidence = FakeEvidence.storing(table);
    final TestCase onAmount = columnTest(null);
    final TestCase onStatus =
        columnTest(null)
            .withFullyQualifiedName(TABLE_FQN + ".status.not_null")
            .withEntityLink("<#E::table::" + TABLE_FQN + "::columns::status>");
    evidence.results.put(onAmount.getFullyQualifiedName(), failedResult(PROFILED_AT, 10L));
    evidence.results.put(onStatus.getFullyQualifiedName(), failedResult(PROFILED_AT, 1L));
    table.getColumns().getLast().withTags(List.of(sensitive()));

    new RdfQualityEvidence(evidence).attach("testCase", List.of(onAmount, onStatus));

    assertEquals(10L, onAmount.getTestCaseResult().getFailedRows());
    assertNull(onStatus.getTestCaseResult());
  }

  static TestCase columnTest(final TestCaseResult latest) {
    return new TestCase()
        .withId(TEST_ID)
        .withName("amount_positive")
        .withFullyQualifiedName(TABLE_FQN + ".amount.amount_positive")
        .withEntityLink("<#E::table::" + TABLE_FQN + "::columns::amount>")
        .withEntityFQN(TABLE_FQN + ".amount")
        .withTestCaseResult(latest);
  }

  static TestCaseResult failedResult(final long timestamp, final long failedRows) {
    return new TestCaseResult()
        .withTimestamp(timestamp)
        .withTestCaseStatus(TestCaseStatus.Failed)
        .withResult("Found min=-3, expected >= 0")
        .withSampleData("row 17: -3")
        .withPassedRows(100L - failedRows)
        .withFailedRows(failedRows)
        .withMinBound(0.0)
        .withMaxBound(1000.0)
        .withTestResultValue(List.of(new TestResultValue().withName("min").withValue("-3")));
  }

  private static Set<Resource> objects(
      final Model model, final Resource subject, final String predicate) {
    return Set.copyOf(
        model
            .listObjectsOfProperty(subject, model.createProperty(predicate))
            .mapWith(RDFNode::asResource)
            .toList());
  }

  /** Every property of {@code subject}, by predicate IRI, with an IRI or lexical-form value. */
  static Map<String, String> properties(final Model model, final Resource subject) {
    final Map<String, String> properties = new TreeMap<>();
    model
        .listStatements(subject, null, (RDFNode) null)
        .forEachRemaining(
            statement ->
                properties.merge(
                    statement.getPredicate().getURI(),
                    statement.getObject().isResource()
                        ? statement.getResource().getURI()
                        : statement.getLiteral().getLexicalForm(),
                    (first, second) -> first + "," + second));
    return properties;
  }

  static Table profiledTable(final double rowCount, final double nullCount) {
    final Column amount =
        new Column()
            .withName("amount")
            .withFullyQualifiedName(TABLE_FQN + ".amount")
            .withDataType(ColumnDataType.DECIMAL)
            .withProfile(
                new ColumnProfile()
                    .withName("amount")
                    .withTimestamp(PROFILED_AT)
                    .withValuesCount(96.0)
                    .withNullCount(nullCount)
                    .withNullProportion(nullCount / 100.0)
                    .withDistinctCount(95.0)
                    .withUniqueCount(90.0)
                    .withMin(-3.0)
                    .withMax(990.5)
                    .withMean(120.25)
                    .withSum(11_544.0)
                    .withStddev(8.5)
                    .withMedian(99.0));
    final Column status =
        new Column()
            .withName("status")
            .withFullyQualifiedName(TABLE_FQN + ".status")
            .withDataType(ColumnDataType.VARCHAR)
            .withProfile(
                new ColumnProfile()
                    .withName("status")
                    .withTimestamp(PROFILED_AT)
                    .withNullCount(0.0)
                    .withMin("CANCELLED")
                    .withMax("SHIPPED")
                    .withMinLength(3.0)
                    .withMaxLength(12.0));
    return new Table()
        .withId(TABLE_ID)
        .withName("orders")
        .withFullyQualifiedName(TABLE_FQN)
        .withColumns(List.of(amount, status))
        .withProfile(
            new TableProfile()
                .withTimestamp(PROFILED_AT)
                .withRowCount(rowCount)
                .withColumnCount(2.0)
                .withSizeInByte(null));
  }

  static Column nestedColumn(final String parent, final String name) {
    return new Column()
        .withName(name)
        .withFullyQualifiedName(TABLE_FQN + "." + parent + "." + name)
        .withDataType(ColumnDataType.VARCHAR);
  }

  static TagLabel sensitive() {
    return new TagLabel()
        .withTagFQN("PII.Sensitive")
        .withSource(TagLabel.TagSource.CLASSIFICATION)
        .withLabelType(TagLabel.LabelType.MANUAL)
        .withState(TagLabel.State.CONFIRMED);
  }

  static void clearProfiles(final Table table) {
    table.setProfile(null);
    table.getColumns().forEach(column -> column.setProfile(null));
  }

  /** Stored evidence read the way the database source reads it: latest rows, by FQN. */
  static final class FakeEvidence implements RdfQualityEvidenceSource {
    final Map<String, TableProfile> tableProfiles = new HashMap<>();
    final Map<String, ColumnProfile> columnProfiles = new HashMap<>();
    final Map<String, TestCaseResult> results = new HashMap<>();
    final Map<String, Table> tables = new HashMap<>();
    final List<List<String>> columnReads = new ArrayList<>();

    static FakeEvidence storing(final Table table) {
      final FakeEvidence evidence = new FakeEvidence();
      evidence.tableProfiles.put(table.getFullyQualifiedName(), table.getProfile());
      table
          .getColumns()
          .forEach(
              column ->
                  evidence.columnProfiles.put(column.getFullyQualifiedName(), column.getProfile()));
      evidence.tables.put(table.getFullyQualifiedName(), table);
      return evidence;
    }

    @Override
    public Map<String, TableProfile> latestTableProfiles(final List<String> tableFqns) {
      return pick(tableProfiles, tableFqns);
    }

    @Override
    public Map<String, ColumnProfile> latestColumnProfiles(final List<String> columnFqns) {
      columnReads.add(List.copyOf(columnFqns));
      return pick(columnProfiles, columnFqns);
    }

    @Override
    public Map<String, TestCaseResult> latestTestCaseResults(final List<String> testCaseFqns) {
      return pick(results, testCaseFqns);
    }

    @Override
    public Optional<Table> liveTable(final String tableFqn) {
      return Optional.ofNullable(tables.get(tableFqn));
    }

    private static <T> Map<String, T> pick(final Map<String, T> stored, final List<String> fqns) {
      final Map<String, T> picked = new HashMap<>();
      fqns.stream().filter(stored::containsKey).forEach(fqn -> picked.put(fqn, stored.get(fqn)));
      return picked;
    }
  }

  static Resource column(final Model model, final String name) {
    return model.createResource(RdfUtils.columnUri(BASE, TABLE_FQN + "." + name));
  }

  /** Each measure the subject carries, by metric local name, with its value's lexical form. */
  static Map<String, String> measures(final Model model, final Resource subject) {
    final Map<String, String> measures = new TreeMap<>();
    final Property isMeasurementOf = model.createProperty(DQV, "isMeasurementOf");
    final Property value = model.createProperty(DQV, "value");
    for (RDFNode node :
        model
            .listObjectsOfProperty(subject, model.createProperty(DQV, "hasQualityMeasurement"))
            .toList()) {
      final Resource measurement = node.asResource();
      final String metric =
          measurement.getRequiredProperty(isMeasurementOf).getResource().getLocalName();
      final Statement measured = measurement.getRequiredProperty(value);
      measures.merge(metric, measured.getLiteral().getLexicalForm(), (a, b) -> a + "," + b);
    }
    return measures;
  }

  private static Set<String> measurementIris(final Model model, final Resource subject) {
    return Set.copyOf(
        model
            .listObjectsOfProperty(subject, model.createProperty(DQV, "hasQualityMeasurement"))
            .mapWith(node -> node.asResource().getURI())
            .toList());
  }

  private static RDFNode timestamp(final Model model) {
    return model.createTypedLiteral(
        Instant.ofEpochMilli(PROFILED_AT).toString(), XSDDatatype.XSDdateTime);
  }
}
