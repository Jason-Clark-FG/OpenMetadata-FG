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

import jakarta.ws.rs.core.SecurityContext;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.LineageCardBuilder.NodeFacts;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.security.Authorizer;

/**
 * Reads the tier, certification, owning teams and failing checks of the assets a lineage card
 * names. The assets come from a graph the caller's permissions already filtered, so nothing here
 * widens what the caller sees. The failing checks of all tables are read together.
 */
@Slf4j
public final class LiveLineageFacts implements LineageCardBuilder.Facts {
  private static final Set<String> FIELDS = Set.of("tags", "owners", "certification", "dataModel");
  private static final String CERTIFICATION_PREFIX = "Certification.";

  private final LiveTrustReader trust;

  public LiveLineageFacts(final Authorizer authorizer, final SecurityContext securityContext) {
    this.trust = new LiveTrustReader(authorizer, securityContext);
  }

  @Override
  public Map<UUID, NodeFacts> read(final List<EntityReference> nodes, final Set<UUID> checked) {
    final Map<UUID, EntityInterface<?>> loaded = new HashMap<>();
    for (final EntityReference node : nodes) {
      load(node).ifPresent(entity -> loaded.put(node.getId(), entity));
    }
    final Map<String, List<TestCase>> failing = failing(nodes, checked);
    final Map<UUID, NodeFacts> facts = new HashMap<>();
    for (final EntityReference node : nodes) {
      final EntityInterface<?> entity = loaded.get(node.getId());
      if (entity != null) {
        facts.put(
            node.getId(),
            factsOf(entity, failing.getOrDefault(node.getFullyQualifiedName(), List.of())));
      }
    }
    return facts;
  }

  private Optional<EntityInterface<?>> load(final EntityReference node) {
    Optional<EntityInterface<?>> entity = Optional.empty();
    try {
      final EntityRepository<?> repository = Entity.getEntityRepository(node.getType());
      final String fields =
          repository.getAllowedFieldsCopy().stream()
              .filter(FIELDS::contains)
              .collect(Collectors.joining(","));
      entity = Optional.of(Entity.getEntity(node, fields, Include.NON_DELETED));
    } catch (EntityNotFoundException exception) {
      LOG.debug("Lineage node {} is gone", node.getFullyQualifiedName());
    }
    return entity;
  }

  private Map<String, List<TestCase>> failing(
      final List<EntityReference> nodes, final Set<UUID> checked) {
    final List<String> tables =
        nodes.stream()
            .filter(node -> checked.contains(node.getId()))
            .map(EntityReference::getFullyQualifiedName)
            .toList();
    return tables.isEmpty() ? Map.of() : trust.failingByEntity(tables);
  }

  private static NodeFacts factsOf(final EntityInterface<?> entity, final List<TestCase> failing) {
    return new NodeFacts(
        LiveTrustReader.tierOf(entity),
        certification(entity),
        entity instanceof Table table ? detail(table) : null,
        listOrEmpty(entity.getOwners()).stream()
            .filter(owner -> Entity.TEAM.equals(owner.getType()))
            .map(owner -> owner.getName())
            .distinct()
            .toList(),
        failingLabel(failing));
  }

  private static String certification(final EntityInterface<?> entity) {
    final String label =
        LiveTrustReader.isCertified(entity)
            ? entity.getCertification().getTagLabel().getTagFQN()
            : null;
    return label != null && label.startsWith(CERTIFICATION_PREFIX)
        ? label.substring(CERTIFICATION_PREFIX.length())
        : label;
  }

  /** dbt when a dbt model builds the table, else the service type it comes from. */
  private static String detail(final Table table) {
    final String detail;
    if (table.getDataModel() != null && table.getDataModel().getModelType() != null) {
      detail = table.getDataModel().getModelType().value().toLowerCase(Locale.ROOT);
    } else {
      detail =
          table.getServiceType() == null
              ? null
              : table.getServiceType().value().toLowerCase(Locale.ROOT);
    }
    return detail;
  }

  static String failingLabel(final List<TestCase> failing) {
    final String label;
    if (failing.isEmpty()) {
      label = null;
    } else if (failing.stream().anyMatch(LiveTrustReader::isFreshnessTest)) {
      label = "Freshness failing";
    } else if (failing.size() == 1) {
      label = "1 check failing";
    } else {
      label = failing.size() + " checks failing";
    }
    return label;
  }
}
