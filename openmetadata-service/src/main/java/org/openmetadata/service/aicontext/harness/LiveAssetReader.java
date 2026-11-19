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

import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.LiveTrustReader;
import org.openmetadata.service.aicontext.LiveTrustReader.TestTrust;
import org.openmetadata.service.aicontext.harness.ContextItem.AssetRef;
import org.openmetadata.service.aicontext.harness.GuardrailEvaluators.LiveAsset;
import org.openmetadata.service.search.SearchResultListMapper;
import org.openmetadata.service.search.SearchSortFilter;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.mask.PIIMasker;

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
  private final LiveTrustReader trust;

  LiveAssetReader(final Authorizer authorizer, final SecurityContext securityContext) {
    this.authorizer = authorizer;
    this.securityContext = securityContext;
    this.trust = new LiveTrustReader(authorizer, securityContext);
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
    return trust
        .load(ref.entityType(), ref.fullyQualifiedName(), FIELDS)
        .filter(entity -> trust.canView(ref.entityType(), ref.fullyQualifiedName()))
        .map(entity -> liveAsset(ref, entity));
  }

  private LiveAsset liveAsset(final AssetRef ref, final EntityInterface entity) {
    final TestTrust tests = trust.testTrust(ref.fullyQualifiedName());
    final boolean certified = LiveTrustReader.isCertified(entity);
    return new LiveAsset(
        ref,
        LiveTrustReader.isDeprecated(entity),
        piiColumns(entity),
        authorizer.authorizePII(securityContext, entity.getOwners()),
        tests.openIncidents().size(),
        tests.failingTests().size(),
        certified,
        !certified && hasCertifiedAlternative(ref, entity),
        LiveTrustReader.ownerNames(entity));
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
