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

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Where the steps of a run are kept, outside the knowledge graph, for a bounded time. */
public interface RunTraceStore {

  /** How long a run's trace is kept after each step is recorded. */
  Duration RETENTION = Duration.ofHours(24);

  void append(UUID run, RunStep step);

  /** The run's live steps in the order they were recorded; empty for an unknown or expired run. */
  List<RunStep> steps(UUID run);
}
