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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.tests.type.TestCaseResult;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnProfile;
import org.openmetadata.schema.type.TableProfile;
import org.openmetadata.service.Entity;
import org.openmetadata.service.resources.feeds.MessageParser.EntityLink;
import org.openmetadata.service.security.mask.PIIMasker;
import org.openmetadata.service.util.EntityUtil;

/**
 * Loads the latest profiles and test results onto entities before they are projected, on the live
 * write and on the reindex alike. They live in time-series tables, not in the entity, so a plain
 * entity read carries none of them. What reaches the graph follows the masking a caller without
 * PII access gets: no profile for a sensitive column, no result for a test on one
 * (ADR:2026-11-18-the-rdf-graph-carries-the-latest-quality-evidence).
 */
public final class RdfQualityEvidence {
  private final RdfQualityEvidenceSource source;

  public RdfQualityEvidence(final RdfQualityEvidenceSource source) {
    this.source = source;
  }

  public static RdfQualityEvidence database() {
    return new RdfQualityEvidence(new DatabaseRdfQualityEvidenceSource());
  }

  /** Attaches the evidence of one page of {@code entityType} entities, with one read per kind. */
  public void attach(final String entityType, final List<? extends EntityInterface<?>> entities) {
    switch (entityType) {
      case Entity.TABLE -> attachProfiles(entities.stream().map(Table.class::cast).toList());
      case Entity.TEST_CASE -> attachLatestResults(
          entities.stream().map(TestCase.class::cast).toList());
      default -> {
        // Only tables, their columns and test cases carry quality evidence.
      }
    }
  }

  private void attachProfiles(final List<Table> tables) {
    final Map<String, TableProfile> tableProfiles =
        source.latestTableProfiles(tables.stream().map(Table::getFullyQualifiedName).toList());
    tables.forEach(table -> table.setProfile(tableProfiles.get(table.getFullyQualifiedName())));
    final List<Column> unmasked = unmaskedColumns(tables);
    final Map<String, ColumnProfile> columnProfiles =
        source.latestColumnProfiles(unmasked.stream().map(Column::getFullyQualifiedName).toList());
    unmasked.forEach(
        column -> column.setProfile(columnProfiles.get(column.getFullyQualifiedName())));
  }

  /** Clears every column's profile and returns the columns whose profile may be shown. */
  private static List<Column> unmaskedColumns(final List<Table> tables) {
    tables.stream()
        .flatMap(table -> EntityUtil.getFlattenedEntityField(table.getColumns()).stream())
        .forEach(column -> column.setProfile(null));
    return tables.stream()
        .flatMap(table -> PIIMasker.columnsWithUnmaskedProfiles(table.getColumns()).stream())
        .toList();
  }

  private void attachLatestResults(final List<TestCase> testCases) {
    final Map<String, TestCaseResult> results =
        source.latestTestCaseResults(
            testCases.stream().map(TestCase::getFullyQualifiedName).toList());
    final Map<String, Optional<Table>> tables = new HashMap<>();
    for (final TestCase testCase : testCases) {
      final TestCaseResult latest = results.get(testCase.getFullyQualifiedName());
      testCase.setTestCaseResult(latest != null && isUnmasked(testCase, tables) ? latest : null);
    }
  }

  private boolean isUnmasked(final TestCase testCase, final Map<String, Optional<Table>> tables) {
    final EntityLink link = EntityLink.parse(testCase.getEntityLink());
    final Table table = tables.computeIfAbsent(link.getEntityFQN(), source::liveTable).orElse(null);
    return !PIIMasker.hidesTestCaseResults(table, link);
  }
}
