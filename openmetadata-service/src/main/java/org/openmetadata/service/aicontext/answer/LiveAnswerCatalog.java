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
package org.openmetadata.service.aicontext.answer;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.service.jdbi3.TimeSeriesDAOs.ProfilerDataTimeSeriesDAO.TABLE_PROFILE_EXTENSION;

import jakarta.ws.rs.core.SecurityContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.api.context.AnswerConcept;
import org.openmetadata.schema.api.context.AnswerConceptType;
import org.openmetadata.schema.api.context.AnswerFailingTest;
import org.openmetadata.schema.api.context.AnswerFreshness;
import org.openmetadata.schema.api.context.AnswerFreshnessSource;
import org.openmetadata.schema.api.context.AnswerOpenIncident;
import org.openmetadata.schema.api.context.AnswerTable;
import org.openmetadata.schema.api.context.AnswerUnresolved;
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
import org.openmetadata.service.jdbi3.TimeSeriesDAOs.ProfilerDataTimeSeriesDAO;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;
import org.openmetadata.service.util.EntityUrls;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.FullyQualifiedName;

/**
 * Reads concepts and tables from the catalog as the caller. An asset is loaded and then checked
 * with {@code VIEW_BASIC}, as {@code LiveAssetReader} does, and nothing is read about one the
 * caller may not view.
 */
@Slf4j
public final class LiveAnswerCatalog implements AnswerCatalog {
  private static final Set<String> FIELDS = Set.of("owners", "tags", "certification");
  private static final String TIER_PREFIX = "Tier.";
  private static final String FRESHNESS_TEST = "fresh";

  private final Authorizer authorizer;
  private final SecurityContext securityContext;
  private final String baseUrl;

  public LiveAnswerCatalog(
      final Authorizer authorizer, final SecurityContext securityContext, final String baseUrl) {
    this.authorizer = authorizer;
    this.securityContext = securityContext;
    this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
  }

  @Override
  public Lookup<AnswerConcept> concept(final AnswerConceptType type, final String fqn) {
    final String entityType =
        type == AnswerConceptType.METRIC ? Entity.METRIC : Entity.GLOSSARY_TERM;
    return visible(entityType, fqn, entity -> conceptOf(type, entityType, entity));
  }

  @Override
  public Lookup<AnswerTable> table(final String fqn) {
    return visible(Entity.TABLE, fqn, this::tableOf);
  }

  private <T> Lookup<T> visible(
      final String entityType, final String fqn, final Function<EntityInterface, T> reader) {
    final Optional<EntityInterface> entity = load(entityType, fqn);
    final Lookup<T> lookup;
    if (entity.isEmpty()) {
      lookup = Lookup.missing(AnswerUnresolved.NOT_FOUND);
    } else if (!canView(entityType, fqn)) {
      lookup = Lookup.missing(AnswerUnresolved.NOT_VISIBLE);
    } else {
      lookup = Lookup.found(reader.apply(entity.get()));
    }
    return lookup;
  }

  private static Optional<EntityInterface> load(final String entityType, final String fqn) {
    Optional<EntityInterface> entity = Optional.empty();
    try {
      final EntityRepository<?> repository = Entity.getEntityRepository(entityType);
      final String fields =
          repository.getAllowedFieldsCopy().stream()
              .filter(FIELDS::contains)
              .collect(Collectors.joining(","));
      entity = Optional.of(Entity.getEntityByName(entityType, fqn, fields, Include.NON_DELETED));
    } catch (EntityNotFoundException exception) {
      LOG.debug("The answer names no {} {}", entityType, fqn);
    }
    return entity;
  }

  private boolean canView(final String entityType, final String fqn) {
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

  private AnswerConcept conceptOf(
      final AnswerConceptType type, final String entityType, final EntityInterface entity) {
    return new AnswerConcept()
        .withEntityType(type)
        .withFqn(entity.getFullyQualifiedName())
        .withDisplayName(
            nullOrEmpty(entity.getDisplayName()) ? entity.getName() : entity.getDisplayName())
        .withStatus(type == AnswerConceptType.GLOSSARY_TERM ? statusOf(entity) : null)
        .withCertified(isCertified(entity))
        .withVersion(entity.getVersion())
        .withOwners(ownerNames(entity))
        .withUrl(url(entityType, entity));
  }

  private static String statusOf(final EntityInterface entity) {
    return entity.getEntityStatus() instanceof EntityStatus status ? status.value() : null;
  }

  private AnswerTable tableOf(final EntityInterface entity) {
    final String fqn = entity.getFullyQualifiedName();
    final List<TestCase> testCases = testCases(fqn);
    final Map<String, TestCase> byHash = hashed(testCases);
    final Map<String, TestCaseResult> results = latestResults(byHash);
    return new AnswerTable()
        .withFqn(fqn)
        .withTier(tierOf(entity))
        .withCertified(isCertified(entity))
        .withDeprecated(entity.getEntityStatus() == EntityStatus.DEPRECATED)
        .withOwners(ownerNames(entity))
        .withFreshness(freshness(fqn, byHash, results))
        .withFailingTests(failingTests(byHash, results))
        .withOpenIncidents(openIncidents(byHash))
        .withUrl(url(Entity.TABLE, entity));
  }

  private static List<TestCase> testCases(final String tableFqn) {
    @SuppressWarnings("unchecked")
    final EntityRepository<TestCase> repository =
        (EntityRepository<TestCase>) Entity.getEntityRepository(Entity.TEST_CASE);
    final ListFilter filter = new ListFilter(Include.NON_DELETED);
    filter.addQueryParam("entityFQN", tableFqn);
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

  private static List<String> namesOf(final Map<String, TestCase> byHash) {
    return byHash.values().stream().map(TestCase::getFullyQualifiedName).toList();
  }

  private List<AnswerFailingTest> failingTests(
      final Map<String, TestCase> byHash, final Map<String, TestCaseResult> results) {
    return results.entrySet().stream()
        .filter(entry -> entry.getValue().getTestCaseStatus() == TestCaseStatus.Failed)
        .map(entry -> byHash.get(entry.getKey()))
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(TestCase::getFullyQualifiedName))
        .map(
            testCase ->
                new AnswerFailingTest()
                    .withName(testCase.getName())
                    .withUrl(url(Entity.TEST_CASE, testCase)))
        .toList();
  }

  private List<AnswerOpenIncident> openIncidents(final Map<String, TestCase> byHash) {
    final List<AnswerOpenIncident> incidents = new ArrayList<>();
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
                  incidents.add(incident(status, testCase));
                }
              });
    }
    incidents.sort(Comparator.comparing(AnswerOpenIncident::getId));
    return incidents;
  }

  private AnswerOpenIncident incident(
      final TestCaseResolutionStatus status, final TestCase testCase) {
    return new AnswerOpenIncident()
        .withId(status.getStateId().toString())
        .withTestCase(testCase.getName())
        .withStatus(status.getTestCaseResolutionStatusType().value())
        .withSeverity(status.getSeverity() == null ? null : status.getSeverity().value())
        .withUrl(
            EntityUrls.buildEntityUrl(Entity.TEST_CASE, testCase, formatter())
                .replaceFirst("/test-case-results$", "/issues"));
  }

  private static boolean isUnresolved(final TestCaseResolutionStatus status) {
    return status != null
        && status.getStateId() != null
        && status.getTestCaseResolutionStatusType() != TestCaseResolutionStatusTypes.Resolved;
  }

  /** The latest freshness test's last run, else the latest profile, else unknown. */
  private static AnswerFreshness freshness(
      final String tableFqn,
      final Map<String, TestCase> byHash,
      final Map<String, TestCaseResult> results) {
    final Optional<Long> tested =
        results.entrySet().stream()
            .filter(entry -> isFreshnessTest(byHash.get(entry.getKey())))
            .map(entry -> entry.getValue().getTimestamp())
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder());
    final AnswerFreshness freshness;
    if (tested.isPresent()) {
      freshness = new AnswerFreshness().withSource(AnswerFreshnessSource.TEST).withAt(tested.get());
    } else {
      freshness =
          profiledAt(tableFqn)
              .map(at -> new AnswerFreshness().withSource(AnswerFreshnessSource.PROFILE).withAt(at))
              .orElseGet(() -> new AnswerFreshness().withSource(AnswerFreshnessSource.UNKNOWN));
    }
    return freshness;
  }

  private static boolean isFreshnessTest(final TestCase testCase) {
    return testCase != null
        && testCase.getTestDefinition() != null
        && testCase.getTestDefinition().getName() != null
        && testCase.getTestDefinition().getName().toLowerCase(Locale.ROOT).contains(FRESHNESS_TEST);
  }

  private static Optional<Long> profiledAt(final String tableFqn) {
    final ProfilerDataTimeSeriesDAO profiles =
        Entity.getCollectionDAO().profilerDataTimeSeriesDao();
    final String json = profiles.getLatestExtension(tableFqn, TABLE_PROFILE_EXTENSION);
    return Optional.ofNullable(json)
        .map(text -> JsonUtils.readValue(text, EntityProfile.class))
        .map(EntityProfile::getTimestamp);
  }

  private static String tierOf(final EntityInterface entity) {
    final List<TagLabel> tags = entity.getTags();
    return listOrEmpty(tags).stream()
        .map(TagLabel::getTagFQN)
        .filter(tag -> tag != null && tag.startsWith(TIER_PREFIX))
        .map(tag -> tag.substring(TIER_PREFIX.length()))
        .findFirst()
        .orElse(null);
  }

  private static boolean isCertified(final EntityInterface entity) {
    return entity.getCertification() != null && entity.getCertification().getTagLabel() != null;
  }

  private static List<String> ownerNames(final EntityInterface entity) {
    final List<EntityReference> owners = entity.getOwners();
    return listOrEmpty(owners).stream()
        .map(LiveAnswerCatalog::ownerName)
        .filter(name -> !nullOrEmpty(name))
        .toList();
  }

  private String url(final String entityType, final EntityInterface entity) {
    return EntityUrls.buildEntityUrl(entityType, entity, formatter());
  }

  private EntityUrls.LinkFormatter formatter() {
    return (prefix, fqn, extra) ->
        baseUrl
            + "/"
            + prefix
            + "/"
            + EntityUtil.encodeEntityFqnSafe(fqn)
            + (nullOrEmpty(extra) ? "" : "/" + extra);
  }

  private static String ownerName(final EntityReference owner) {
    return nullOrEmpty(owner.getDisplayName()) ? owner.getName() : owner.getDisplayName();
  }
}
