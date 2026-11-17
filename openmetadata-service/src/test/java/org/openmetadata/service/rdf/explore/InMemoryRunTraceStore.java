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

package org.openmetadata.service.rdf.explore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openmetadata.schema.utils.JsonUtils;

/** Keeps steps as the SQL store does, serialized, so a step that does not round-trip fails. */
final class InMemoryRunTraceStore implements RunTraceStore {

  private final Map<UUID, List<String>> steps = new HashMap<>();

  @Override
  public void append(final UUID run, final RunStep step) {
    steps.computeIfAbsent(run, ignored -> new ArrayList<>()).add(JsonUtils.pojoToJson(step));
  }

  @Override
  public List<RunStep> steps(final UUID run) {
    return steps.getOrDefault(run, List.of()).stream()
        .map(json -> JsonUtils.readValue(json, RunStep.class))
        .toList();
  }

  List<String> storedJson(final UUID run) {
    return List.copyOf(steps.getOrDefault(run, List.of()));
  }
}
