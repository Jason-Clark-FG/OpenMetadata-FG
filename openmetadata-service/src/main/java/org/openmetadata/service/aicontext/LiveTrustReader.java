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
import static org.openmetadata.service.jdbi3.TimeSeriesDAOs.ProfilerDataTimeSeriesDAO.TABLE_PROFILE_EXTENSION;

import jakarta.ws.rs.core.SecurityContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import org.openmetadata.schema.type.EntityProfile;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EntityStatus;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.jdbi3.ListFilter;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;
import org.openmetadata.service.util.FullyQualifiedName;

/**
 * Reads the live trust facts of an asset as the caller: certification, tier, owners, deprecation,
 * failing test cases, open incidents and the inputs to freshness. The guardrails and the answer
 * citation both read through it, so they cannot disagree about an asset. An asset is loaded and
 * then checked with {@code VIEW_BASIC}; nothing is derived from one the caller may not view.
 */
@Slf4j
public final class LiveTrustReader {
  private static final String TIER_PREFIX = "Tier.";
  private static final String FRESHNESS_TEST = "fresh";

  /** An incident that is not resolved, with the test case it belongs to. */
  public record OpenIncident(TestCaseResolutionStatus status, TestCase testCase) {}

  /**
   * What the test cases of one asset say right now. Failing tests are ordered by name and
   * incidents by id. {@code freshnessTestAt} is the latest run of a freshness test, if any.
   */
  public record TestTrust(
      List<TestCase> failingTests,
      List<OpenIncident> openIncidents,
      Optional<Long> freshnessTestAt) {}

  private final Authorizer authorizer;
  private final SecurityContext securityContext;

  public LiveTrustReader(final Authorizer authorizer, final SecurityContext securityContext) {
    this.authorizer = authorizer;
    this.securityContext = securityContext;
  }

  /** The entity with those of {@code fields} its type allows, or empty when it does not exist. */
  public Optional<EntityInterface> load(
      final String entityType, final String fqn, final Set<String> fields) {
    Optional<EntityInterface> entity = Optional.empty();
    try {
      final EntityRepository<?> repository = Entity.getEntityRepository(entityType);
      final String allowed =
          repository.getAllowedFieldsCopy().stream()
              .filter(fields::contains)
              .collect(Collectors.joining(","));
      entity = Optional.of(Entity.getEntityByName(entityType, fqn, allowed, Include.NON_DELETED));
    } catch (EntityNotFoundException exception) {
      LOG.debug("No {} {} to read trust facts from", entityType, fqn);
    }
    return entity;
  }

  public boolean canView(final String entityType, final String fqn) {
    boolean visible = true;
    try {
      authorizer.authorize(
          securityContext,
          new OperationContext(entityType, MetadataOperation.VIEW_BASIC),
          new ResourceContext<>(entityType, null, fqn));
    } catch (AuthorizationException exception) {
      visible = false;
    }
    return visible;
  }

  /** One test-case listing and one batched time-series read each for results and incidents. */
  public TestTrust testTrust(final String entityFqn) {
    final Map<String, TestCase> byHash = hashed(testCases(entityFqn));
    final Map<String, TestCaseResult> results = latestResults(byHash);
    return new TestTrust(
        failingTests(byHash, results), openIncidents(byHash), freshnessTestAt(byHash, results));
  }

  /**
   * The failing test cases of each entity, for many entities at once: one listing per entity, then
   * a single batched read of the latest results of all of them.
   */
  public Map<String, List<TestCase>> failingByEntity(final Collection<String> entityFqns) {
    final Map<String, TestCase> byHash = new HashMap<>();
    final Map<String, String> entityOfTest = new HashMap<>();
    for (final String entityFqn : entityFqns) {
      testCases(entityFqn)
          .forEach(
              testCase -> {
                final String hash = FullyQualifiedName.buildHash(testCase.getFullyQualifiedName());
                byHash.put(hash, testCase);
                entityOfTest.put(hash, entityFqn);
              });
    }
    final Map<String, List<TestCase>> failing = new HashMap<>();
    failingTests(byHash, latestResults(byHash))
        .forEach(
            testCase ->
                failing
                    .computeIfAbsent(
                        entityOfTest.get(
                            FullyQualifiedName.buildHash(testCase.getFullyQualifiedName())),
                        key -> new ArrayList<>())
                    .add(testCase));
    return failing;
  }

  public Optional<Long> profiledAt(final String tableFqn) {
    final String json =
        Entity.getCollectionDAO()
            .profilerDataTimeSeriesDao()
            .getLatestExtension(tableFqn, TABLE_PROFILE_EXTENSION);
    return Optional.ofNullable(json)
        .map(text -> JsonUtils.readValue(text, EntityProfile.class))
        .map(EntityProfile::getTimestamp);
  }

  /** The replacing table's name, when the entity is a table with a live replacement the caller may view. */
  public Optional<String> replacement(final EntityInterface entity) {
    return Optional.of(entity)
        .filter(Table.class::isInstance)
        .map(table -> ((Table) table).getReplacedBy())
        .filter(replacement -> !Boolean.TRUE.equals(replacement.getDeleted()))
        .map(EntityReference::getFullyQualifiedName)
        .filter(fqn -> canView(Entity.TABLE, fqn));
  }

  public static boolean isDeprecated(final EntityInterface entity) {
    return entity.getEntityStatus() == EntityStatus.DEPRECATED;
  }

  public static boolean isCertified(final EntityInterface entity) {
    return entity.getCertification() != null && entity.getCertification().getTagLabel() != null;
  }

  public static String tierOf(final EntityInterface entity) {
    final List<TagLabel> tags = entity.getTags();
    return listOrEmpty(tags).stream()
        .map(TagLabel::getTagFQN)
        .filter(tag -> tag != null && tag.startsWith(TIER_PREFIX))
        .map(tag -> tag.substring(TIER_PREFIX.length()))
        .findFirst()
        .orElse(null);
  }

  public static List<String> ownerNames(final EntityInterface entity) {
    final List<EntityReference> owners = entity.getOwners();
    return listOrEmpty(owners).stream()
        .map(LiveTrustReader::ownerName)
        .filter(name -> !nullOrEmpty(name))
        .toList();
  }

  private static String ownerName(final EntityReference owner) {
    return nullOrEmpty(owner.getDisplayName()) ? owner.getName() : owner.getDisplayName();
  }

  private static List<TestCase> testCases(final String entityFqn) {
    @SuppressWarnings("unchecked")
    final EntityRepository<TestCase> repository =
        (EntityRepository<TestCase>) Entity.getEntityRepository(Entity.TEST_CASE);
    final ListFilter filter = new ListFilter(Include.NON_DELETED);
    filter.addQueryParam("entityFQN", entityFqn);
    filter.addQueryParam("includeAllTests", "true");
    return repository.listAll(repository.getFields("testDefinition"), filter);
  }

  private static Map<String, TestCase> hashed(final List<TestCase> testCases) {
    final Map<String, TestCase> byHash = new HashMap<>();
    testCases.forEach(
        testCase ->
            byHash.put(FullyQualifiedName.buildHash(testCase.getFullyQualifiedName()), testCase));
    return byHash;
  }

  private static List<String> namesOf(final Map<String, TestCase> byHash) {
    return byHash.values().stream().map(TestCase::getFullyQualifiedName).toList();
  }

  private static Map<String, TestCaseResult> latestResults(final Map<String, TestCase> byHash) {
    final Map<String, TestCaseResult> results = new HashMap<>();
    if (!byHash.isEmpty()) {
      Entity.getCollectionDAO()
          .dataQualityDataTimeSeriesDao()
          .getLatestRecordBatch(namesOf(byHash))
          .stream()
          .filter(record -> record.getJson() != null)
          .forEach(
              record ->
                  results.put(
                      record.getEntityFQNHash(),
                      JsonUtils.readValue(record.getJson(), TestCaseResult.class)));
    }
    return results;
  }

  private static List<TestCase> failingTests(
      final Map<String, TestCase> byHash, final Map<String, TestCaseResult> results) {
    return results.entrySet().stream()
        .filter(entry -> entry.getValue().getTestCaseStatus() == TestCaseStatus.Failed)
        .map(entry -> byHash.get(entry.getKey()))
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(TestCase::getFullyQualifiedName))
        .toList();
  }

  private static List<OpenIncident> openIncidents(final Map<String, TestCase> byHash) {
    final Map<String, OpenIncident> byState = new HashMap<>();
    if (!byHash.isEmpty()) {
      Entity.getCollectionDAO()
          .testCaseResolutionStatusTimeSeriesDao()
          .getLatestRecordBatch(namesOf(byHash))
          .stream()
          .filter(record -> record.getJson() != null)
          .forEach(
              record -> {
                final TestCaseResolutionStatus status =
                    JsonUtils.readValue(record.getJson(), TestCaseResolutionStatus.class);
                final TestCase testCase = byHash.get(record.getEntityFQNHash());
                if (isUnresolved(status) && testCase != null) {
                  byState.putIfAbsent(
                      status.getStateId().toString(), new OpenIncident(status, testCase));
                }
              });
    }
    return byState.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue)
        .toList();
  }

  private static boolean isUnresolved(final TestCaseResolutionStatus status) {
    return status != null
        && status.getStateId() != null
        && status.getTestCaseResolutionStatusType() != TestCaseResolutionStatusTypes.Resolved;
  }

  private static Optional<Long> freshnessTestAt(
      final Map<String, TestCase> byHash, final Map<String, TestCaseResult> results) {
    return results.entrySet().stream()
        .filter(entry -> isFreshnessTest(byHash.get(entry.getKey())))
        .map(entry -> entry.getValue().getTimestamp())
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder());
  }

  public static boolean isFreshnessTest(final TestCase testCase) {
    return testCase != null
        && testCase.getTestDefinition() != null
        && testCase.getTestDefinition().getName() != null
        && testCase.getTestDefinition().getName().toLowerCase(Locale.ROOT).contains(FRESHNESS_TEST);
  }
}
