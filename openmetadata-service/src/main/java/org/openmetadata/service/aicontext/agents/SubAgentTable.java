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
package org.openmetadata.service.aicontext.agents;

import java.util.List;

/** Rows an agent produced, bounded by the provider; {@code totalRows} counts all of them. */
public record SubAgentTable(List<String> columns, List<List<Object>> rows, long totalRows) {

  public SubAgentTable {
    columns = columns == null ? List.of() : List.copyOf(columns);
    rows = rows == null ? List.of() : List.copyOf(rows);
  }
}
