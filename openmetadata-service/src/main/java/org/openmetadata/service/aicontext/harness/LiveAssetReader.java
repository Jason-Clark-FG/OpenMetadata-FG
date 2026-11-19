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

import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.tests.type.TestCaseResolutionStatus;
import org.openmetadata.schema.tests.type.TestCaseResolutionStatusTypes;
import org.openmetadata.schema.tests.type.TestCaseResult;
import org.openmetadata.schema.tests.type.TestCaseStatus;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EntityStatus;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.harness.ContextItem.AssetRef;
import org.openmetadata.service.aicontext.harness.GuardrailEvaluators.LiveAsset;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.jdbi3.ListFilter;
import org.openmetadata.service.search.SearchResultListMapper;
import org.openmetadata.service.search.SearchSortFilter;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.mask.PIIMasker;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;

/**
 * Reads the assets a tool call names as they are in the catalog now, for the guardrails and the
 * PostToolUse incident notes. Assets the caller cannot view are skipped, so a decision never tells
 * the caller about an asset they could not look up themselves.
 */
@Slf4j
final class LiveAssetReader {
  static final int MAX_ASSETS = 20;
  private static final Set<String> FIELDS = Set.of("owners", "tags", "columns", "certification");

  private final Authorizer authorizer;
  private final SecurityContext securityContext;

  LiveAssetReader(final Authorizer authorizer, final SecurityContext securityContext) {
    this.authorizer = authorizer;
    this.securityContext = securityContext;
  }

  List<LiveAsset> read(final Collection<AssetRef> refs) {
    return refs.stream()
        .distinct()
        .limit(MAX_ASSETS)
        .map(this::read)
        .flatMap(Optional::stream)
        .toList();
  }

  private Optional<LiveAsset> read(final AssetRef ref) {
    return load(ref).filter(entity -> canView(ref)).map(entity -> liveAsset(ref, entity));
  }

  private LiveAsset liveAsset(final AssetRef ref, final EntityInterface entity) {
    final List<String> testCases = testCaseNames(ref.fullyQualifiedName());
    final boolean certified = isCertified(entity);
    return new LiveAsset(
        ref,
        isDeprecated(entity),
        piiColumns(entity),
        authorizer.authorizePII(securityContext, entity.getOwners()),
        openIncidents(testCases),
        failedTests(testCases),
        certified,
        !certified && hasCertifiedAlternative(ref, entity),
        ownerNames(entity));
  }

  private static List<String> ownerNames(final EntityInterface entity) {
    final List<EntityReference> owners = entity.getOwners();
    return listOrEmpty(owners).stream()
        .map(
            owner -> nullOrEmpty(owner.getDisplayName()) ? owner.getName() : owner.getDisplayName())
        .filter(name -> !nullOrEmpty(name))
        .toList();
  }

  private static Optional<EntityInterface> load(final AssetRef ref) {
    Optional<EntityInterface> entity = Optional.empty();
    try {
      final EntityRepository<?> repository = Entity.getEntityRepository(ref.entityType());
      final String fields =
          repository.getAllowedFieldsCopy().stream()
              .filter(FIELDS::contains)
              .collect(Collectors.joining(","));
      entity =
          Optional.of(
              Entity.getEntityByName(
                  ref.entityType(), ref.fullyQualifiedName(), fields, Include.NON_DELETED));
    } catch (EntityNotFoundException exception) {
      LOG.debug("Tool call names no {} {}", ref.entityType(), ref.fullyQualifiedName());
    }
    return entity;
  }

  private boolean canView(final AssetRef ref) {
    boolean visible = true;
    try {
      authorizer.authorize(
          securityContext,
          new OperationContext(ref.entityType(), MetadataOperation.VIEW_BASIC),
          new ResourceContext<>(ref.entityType(), null, ref.fullyQualifiedName()));
    } catch (AuthorizationException exception) {
      visible = false;
    }
    return visible;
  }

  private static boolean isDeprecated(final EntityInterface entity) {
    return entity.getEntityStatus() instanceof EntityStatus status
        && status == EntityStatus.DEPRECATED;
  }

  private static boolean isCertified(final EntityInterface entity) {
    return entity.getCertification() != null && entity.getCertification().getTagLabel() != null;
  }

  private static List<String> piiColumns(final EntityInterface entity) {
    final List<String> names = new ArrayList<>();
    if (entity instanceof Table table) {
      collectPiiColumns(table.getColumns(), names);
    }
    return names;
  }

  private static void collectPiiColumns(final List<Column> columns, final List<String> names) {
    for (final Column column : listOrEmpty(columns)) {
      if (hasTag(column.getTags(), PIIMasker.SENSITIVE_PII_TAG)) {
        names.add(column.getName());
      }
      collectPiiColumns(column.getChildren(), names);
    }
  }

  private static boolean hasTag(final List<TagLabel> tags, final String tagFqn) {
    return listOrEmpty(tags).stream().anyMatch(tag -> tagFqn.equals(tag.getTagFQN()));
  }

  private static List<String> testCaseNames(final String entityFqn) {
    final EntityRepository<TestCase> repository =
        (EntityRepository<TestCase>) Entity.getEntityRepository(Entity.TEST_CASE);
    final ListFilter filter = new ListFilter(Include.NON_DELETED);
    filter.addQueryParam("entityFQN", entityFqn);
    filter.addQueryParam("includeAllTests", "true");
    return repository.listAll(repository.getFields(""), filter).stream()
        .map(TestCase::getFullyQualifiedName)
        .toList();
  }

  private static int openIncidents(final List<String> testCases) {
    return testCases.isEmpty()
        ? 0
        : (int)
            Entity.getCollectionDAO()
                .testCaseResolutionStatusTimeSeriesDao()
                .getLatestRecordBatch(testCases)
                .stream()
                .map(record -> latest(record, TestCaseResolutionStatus.class))
                .filter(LiveAssetReader::isUnresolved)
                .map(TestCaseResolutionStatus::getStateId)
                .distinct()
                .count();
  }

  private static int failedTests(final List<String> testCases) {
    return testCases.isEmpty()
        ? 0
        : (int)
            Entity.getCollectionDAO()
                .dataQualityDataTimeSeriesDao()
                .getLatestRecordBatch(testCases)
                .stream()
                .map(record -> latest(record, TestCaseResult.class))
                .filter(
                    result -> result != null && result.getTestCaseStatus() == TestCaseStatus.Failed)
                .count();
  }

  private static <T> T latest(
      final CollectionDAO.LatestRecordWithFQNHash record, final Class<T> type) {
    return record.getJson() == null ? null : JsonUtils.readValue(record.getJson(), type);
  }

  private static boolean isUnresolved(final TestCaseResolutionStatus status) {
    return status != null
        && status.getStateId() != null
        && status.getTestCaseResolutionStatusType() != TestCaseResolutionStatusTypes.Resolved;
  }

  private static boolean hasCertifiedAlternative(final AssetRef ref, final EntityInterface entity) {
    final List<TagLabel> tags = listOrEmpty(entity.getTags());
    final List<String> glossaryTerms =
        tags.stream()
            .filter(tag -> tag.getSource() == TagLabel.TagSource.GLOSSARY)
            .map(TagLabel::getTagFQN)
            .toList();
    return Entity.TABLE.equals(ref.entityType())
        && !glossaryTerms.isEmpty()
        && certifiedTablesWith(glossaryTerms) > 0;
  }

  private static long certifiedTablesWith(final List<String> glossaryTerms) {
    final String filter =
        String.format(
            """
            {"query": {"bool": {"must": [
              {"exists": {"field": "certification.tagLabel.tagFQN"}},
              {"terms": {"tags.tagFQN": %s}}
            ]}}}""",
            JsonUtils.pojoToJson(glossaryTerms));
    long matches = 0;
    try {
      final SearchResultListMapper result =
          Entity.getSearchRepository()
              .listWithDeepPagination(
                  Entity.TABLE,
                  null,
                  filter,
                  new String[] {"fullyQualifiedName"},
                  new SearchSortFilter("fullyQualifiedName", "asc", null, null),
                  1,
                  null);
      matches = result.getTotal();
    } catch (IOException | RuntimeException exception) {
      LOG.warn(
          "Could not look up certified tables sharing glossary terms: {}", exception.getMessage());
    }
    return matches;
  }
}
