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

package org.openmetadata.mcp.tools;

import java.util.List;
import org.openmetadata.service.rdf.explore.SearchHit;

/**
 * The catalog half of a find: its hits in rank order, and why semantic search did not take part
 * when it was on and could not run. {@code semanticSearchSkipped} is null otherwise.
 */
public record CatalogHits(List<SearchHit> hits, String semanticSearchSkipped) {

  public CatalogHits {
    hits = List.copyOf(hits);
  }

  static CatalogHits of(final List<SearchHit> hits) {
    return new CatalogHits(hits, null);
  }

  static CatalogHits skipped(final List<SearchHit> hits, final String reason) {
    return new CatalogHits(
        hits,
        "Semantic search did not take part (%s); these are word matches only.".formatted(reason));
  }
}
