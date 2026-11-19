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

import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.api.configuration.OpenMetadataBaseUrlConfiguration;
import org.openmetadata.schema.settings.SettingsType;
import org.openmetadata.service.resources.settings.SettingsCache;
import org.openmetadata.service.util.EntityUrls;
import org.openmetadata.service.util.EntityUtil;

/** Links to where the UI shows an entity, under the server's configured base URL. */
public final class EntityLinks {
  private final String baseUrl;

  public EntityLinks(final String baseUrl) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
  }

  /** Links under the base URL configured in the server settings. */
  public static EntityLinks fromSettings() {
    final OpenMetadataBaseUrlConfiguration urls =
        SettingsCache.getSetting(
            SettingsType.OPEN_METADATA_BASE_URL_CONFIGURATION,
            OpenMetadataBaseUrlConfiguration.class);
    return new EntityLinks(urls == null ? null : urls.getOpenMetadataUrl());
  }

  public String of(final String entityType, final EntityInterface<?> entity) {
    return EntityUrls.buildEntityUrl(
        entityType,
        entity,
        (prefix, fqn, extra) ->
            baseUrl
                + "/"
                + prefix
                + "/"
                + EntityUtil.encodeEntityFqnSafe(fqn)
                + (nullOrEmpty(extra) ? "" : "/" + extra));
  }
}
