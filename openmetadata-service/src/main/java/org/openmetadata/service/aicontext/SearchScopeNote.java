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

import org.openmetadata.schema.type.personaContext.SearchScope;
import org.openmetadata.schema.type.personaContext.SearchScopeRule;

public final class SearchScopeNote {
  private SearchScopeNote() {}

  /**
   * Search-scoped rules preload nothing, so without this note the context would give no hint that
   * searches are being narrowed and the model could not explain a thin result set. Empty when the
   * scope narrows nothing.
   */
  public static String render(final SearchScope scope) {
    if (scope == null || nullOrEmpty(scope.getEntityTypes())) {
      return "";
    }
    final StringBuilder markdown = new StringBuilder("\n## Search scope\n\n");
    markdown
        .append("Your administrator configured default search rules for this persona. ")
        .append(
            "Search tools are asked to apply this scope by default, so results may be narrowed to"
                + " these assets. This is a relevance default, not a permission boundary, and a"
                + " tool may search outside it.\n\n");
    for (final SearchScopeRule rule : listOrEmpty(scope.getRules())) {
      markdown
          .append("- ")
          .append(rule.getRuleName())
          .append(" — ")
          .append(rule.getEntityType())
          .append(nullOrEmpty(rule.getQueryFilter()) ? " (every entity of this type)" : "")
          .append('\n');
    }
    return markdown.append('\n').toString();
  }
}
