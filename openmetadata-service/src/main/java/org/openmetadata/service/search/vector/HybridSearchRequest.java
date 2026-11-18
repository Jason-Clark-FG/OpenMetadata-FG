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
package org.openmetadata.service.search.vector;

import java.util.List;
import java.util.Map;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/**
 * One hybrid search: the question, the word query the search settings build for it, the indexes
 * or aliases to search, the filters both legs apply, how many entities to return, and the caller.
 */
public record HybridSearchRequest(
    String query,
    String lexicalQuery,
    List<String> indexes,
    Map<String, List<String>> filters,
    int size,
    SubjectContext subjectContext) {

  public HybridSearchRequest {
    indexes = List.copyOf(indexes);
    filters = Map.copyOf(filters);
  }
}
