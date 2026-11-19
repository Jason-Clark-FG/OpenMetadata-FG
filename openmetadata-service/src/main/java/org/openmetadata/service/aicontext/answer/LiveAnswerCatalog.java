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

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.api.context.AnswerConcept;
import org.openmetadata.schema.api.context.AnswerConceptType;
import org.openmetadata.schema.api.context.AnswerFailingTest;
import org.openmetadata.schema.api.context.AnswerFreshness;
import org.openmetadata.schema.api.context.AnswerFreshnessSource;
import org.openmetadata.schema.api.context.AnswerOpenIncident;
import org.openmetadata.schema.api.context.AnswerTable;
import org.openmetadata.schema.api.context.AnswerUnresolved;
import org.openmetadata.schema.type.EntityStatus;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.LiveTrustReader;
import org.openmetadata.service.aicontext.LiveTrustReader.OpenIncident;
import org.openmetadata.service.aicontext.LiveTrustReader.TestTrust;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.util.EntityUrls;
import org.openmetadata.service.util.EntityUtil;

/**
 * Shapes the live trust facts of {@link LiveTrustReader} into the answer citation. An asset is
 * loaded and then checked with {@code VIEW_BASIC}, and nothing is read about one the caller may
 * not view.
 */
public final class LiveAnswerCatalog implements AnswerCatalog {
  private static final Set<String> FIELDS = Set.of("owners", "tags", "certification");

  private final LiveTrustReader trust;
  private final String baseUrl;

  public LiveAnswerCatalog(
      final Authorizer authorizer, final SecurityContext securityContext, final String baseUrl) {
    this.trust = new LiveTrustReader(authorizer, securityContext);
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
    final Optional<EntityInterface> entity = trust.load(entityType, fqn, FIELDS);
    final Lookup<T> lookup;
    if (entity.isEmpty()) {
      lookup = Lookup.missing(AnswerUnresolved.NOT_FOUND);
    } else if (!trust.canView(entityType, fqn)) {
      lookup = Lookup.missing(AnswerUnresolved.NOT_VISIBLE);
    } else {
      lookup = Lookup.found(reader.apply(entity.get()));
    }
    return lookup;
  }

  private AnswerConcept conceptOf(
      final AnswerConceptType type, final String entityType, final EntityInterface entity) {
    return new AnswerConcept()
        .withEntityType(type)
        .withFqn(entity.getFullyQualifiedName())
        .withDisplayName(
            nullOrEmpty(entity.getDisplayName()) ? entity.getName() : entity.getDisplayName())
        .withStatus(type == AnswerConceptType.GLOSSARY_TERM ? statusOf(entity) : null)
        .withCertified(LiveTrustReader.isCertified(entity))
        .withVersion(entity.getVersion())
        .withOwners(LiveTrustReader.ownerNames(entity))
        .withUrl(url(entityType, entity));
  }

  private static String statusOf(final EntityInterface entity) {
    return entity.getEntityStatus() instanceof EntityStatus status ? status.value() : null;
  }

  private AnswerTable tableOf(final EntityInterface entity) {
    final String fqn = entity.getFullyQualifiedName();
    final TestTrust tests = trust.testTrust(fqn);
    return new AnswerTable()
        .withFqn(fqn)
        .withTier(LiveTrustReader.tierOf(entity))
        .withCertified(LiveTrustReader.isCertified(entity))
        .withDeprecated(LiveTrustReader.isDeprecated(entity))
        .withOwners(LiveTrustReader.ownerNames(entity))
        .withFreshness(freshness(fqn, tests))
        .withFailingTests(failingTests(tests))
        .withOpenIncidents(tests.openIncidents().stream().map(this::incident).toList())
        .withUrl(url(Entity.TABLE, entity));
  }

  /** The latest freshness test's last run, else the latest profile, else unknown. */
  private AnswerFreshness freshness(final String tableFqn, final TestTrust tests) {
    final AnswerFreshness freshness;
    if (tests.freshnessTestAt().isPresent()) {
      freshness =
          new AnswerFreshness()
              .withSource(AnswerFreshnessSource.TEST)
              .withAt(tests.freshnessTestAt().get());
    } else {
      freshness =
          trust
              .profiledAt(tableFqn)
              .map(at -> new AnswerFreshness().withSource(AnswerFreshnessSource.PROFILE).withAt(at))
              .orElseGet(() -> new AnswerFreshness().withSource(AnswerFreshnessSource.UNKNOWN));
    }
    return freshness;
  }

  private List<AnswerFailingTest> failingTests(final TestTrust tests) {
    return tests.failingTests().stream()
        .map(
            testCase ->
                new AnswerFailingTest()
                    .withName(testCase.getName())
                    .withUrl(url(Entity.TEST_CASE, testCase)))
        .toList();
  }

  private AnswerOpenIncident incident(final OpenIncident incident) {
    return new AnswerOpenIncident()
        .withId(incident.status().getStateId().toString())
        .withTestCase(incident.testCase().getName())
        .withStatus(incident.status().getTestCaseResolutionStatusType().value())
        .withSeverity(
            incident.status().getSeverity() == null
                ? null
                : incident.status().getSeverity().value())
        .withUrl(
            EntityUrls.buildEntityUrl(Entity.TEST_CASE, incident.testCase(), formatter())
                .replaceFirst("/test-case-results$", "/issues"));
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
}
