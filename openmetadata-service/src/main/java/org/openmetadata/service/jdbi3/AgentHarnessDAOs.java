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
package org.openmetadata.service.jdbi3;

import static org.openmetadata.service.jdbi3.locator.ConnectionType.MYSQL;
import static org.openmetadata.service.jdbi3.locator.ConnectionType.POSTGRES;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.CreateSqlObject;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.service.jdbi3.locator.ConnectionAwareSqlUpdate;
import org.openmetadata.service.util.jdbi.BindJson;
import org.openmetadata.service.util.jdbi.BindUUID;

public interface AgentHarnessDAOs {
  @CreateSqlObject
  AgentHarnessDAO agentHarnessDAO();

  @CreateSqlObject
  AgentHarnessDraftDAO agentHarnessDraftDAO();

  interface AgentHarnessDAO extends EntityDAO<AgentHarness> {
    @Override
    default String getTableName() {
      return "agent_harness_entity";
    }

    @Override
    default Class<AgentHarness> getEntityClass() {
      return AgentHarness.class;
    }

    @Override
    default String getNameHashColumn() {
      return "fqnHash";
    }

    @SqlQuery("SELECT json FROM agent_harness_entity WHERE personaId = :personaId")
    String findByPersonaId(@BindUUID("personaId") UUID personaId);

    @SqlQuery("SELECT json FROM agent_harness_entity WHERE personaId IN (<personaIds>)")
    List<String> findByPersonaIds(@BindList("personaIds") List<String> personaIds);
  }

  /** The stored draft of one harness. {@code json} is null when the harness has no draft. */
  record DraftRow(int revision, String json) {}

  /**
   * One row per harness for the harness's whole life. Every change adds one to {@code revision},
   * discarding and publishing included, so a revision a client read can match only the content it
   * read. Each write names the revision it replaces and reports whether it applied.
   */
  interface AgentHarnessDraftDAO {
    @SqlUpdate(
        "INSERT INTO agent_harness_draft (harnessId, json, revision, updatedBy, updatedAt) "
            + "VALUES (:harnessId, NULL, 0, :updatedBy, :updatedAt)")
    void insertEmpty(
        @BindUUID("harnessId") UUID harnessId,
        @Bind("updatedBy") String updatedBy,
        @Bind("updatedAt") long updatedAt);

    @SqlQuery("SELECT revision, json FROM agent_harness_draft WHERE harnessId = :harnessId")
    @RegisterRowMapper(DraftRowMapper.class)
    DraftRow find(@BindUUID("harnessId") UUID harnessId);

    @SqlQuery(
        "SELECT harnessId FROM agent_harness_draft "
            + "WHERE json IS NOT NULL AND harnessId IN (<harnessIds>)")
    List<String> findWithDraft(@BindList("harnessIds") List<String> harnessIds);

    @ConnectionAwareSqlUpdate(
        value =
            "UPDATE agent_harness_draft SET json = :json, revision = revision + 1, "
                + "updatedBy = :updatedBy, updatedAt = :updatedAt "
                + "WHERE harnessId = :harnessId AND revision = :expectedRevision",
        connectionType = MYSQL)
    @ConnectionAwareSqlUpdate(
        value =
            "UPDATE agent_harness_draft SET json = (:json :: jsonb), revision = revision + 1, "
                + "updatedBy = :updatedBy, updatedAt = :updatedAt "
                + "WHERE harnessId = :harnessId AND revision = :expectedRevision",
        connectionType = POSTGRES)
    int write(
        @BindUUID("harnessId") UUID harnessId,
        @Bind("expectedRevision") int expectedRevision,
        @BindJson("json") String json,
        @Bind("updatedBy") String updatedBy,
        @Bind("updatedAt") long updatedAt);

    @SqlUpdate(
        "UPDATE agent_harness_draft SET json = NULL, revision = revision + 1, "
            + "updatedBy = :updatedBy, updatedAt = :updatedAt "
            + "WHERE harnessId = :harnessId AND revision = :expectedRevision")
    int clear(
        @BindUUID("harnessId") UUID harnessId,
        @Bind("expectedRevision") int expectedRevision,
        @Bind("updatedBy") String updatedBy,
        @Bind("updatedAt") long updatedAt);

    @SqlUpdate("DELETE FROM agent_harness_draft WHERE harnessId = :harnessId")
    void delete(@BindUUID("harnessId") UUID harnessId);

    class DraftRowMapper implements RowMapper<DraftRow> {
      @Override
      public DraftRow map(ResultSet resultSet, StatementContext context) throws SQLException {
        return new DraftRow(resultSet.getInt("revision"), resultSet.getString("json"));
      }
    }
  }
}
