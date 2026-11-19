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

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.jdbi3.RdfInfraDAOs.RdfExplorationRunDAO;

/**
 * Keeps run steps in the server database, which every server node shares, so a run continued on
 * another node traces as one. Each step expires {@link #RETENTION} after it was recorded: appends
 * purge expired steps and reads ignore them, so a run longer than that loses its oldest steps.
 */
public final class SqlRunTraceStore implements RunTraceStore {

  private final RdfExplorationRunDAO dao;
  private final Clock clock;

  public SqlRunTraceStore(final RdfExplorationRunDAO dao, final Clock clock) {
    this.dao = Objects.requireNonNull(dao);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public void append(final UUID run, final RunStep step) {
    final long now = clock.millis();
    dao.deleteBefore(expiryCutoff(now));
    dao.insert(run.toString(), now, JsonUtils.pojoToJson(step));
  }

  @Override
  public List<RunStep> steps(final UUID run) {
    return dao.listSteps(run.toString(), expiryCutoff(clock.millis())).stream()
        .map(json -> JsonUtils.readValue(json, RunStep.class))
        .toList();
  }

  private static long expiryCutoff(final long now) {
    return now - RETENTION.toMillis();
  }
}
