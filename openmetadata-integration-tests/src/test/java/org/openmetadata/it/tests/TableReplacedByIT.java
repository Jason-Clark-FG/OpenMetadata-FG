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
package org.openmetadata.it.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openmetadata.it.tests.mcp.McpTestBase;
import org.openmetadata.schema.entity.data.Table;

/** A table records the table that replaces it, as a relationship the fields parameter loads. */
@Execution(ExecutionMode.CONCURRENT)
class TableReplacedByIT extends McpTestBase {

  @BeforeAll
  static void setup() throws Exception {
    initAuth();
  }

  @Test
  void aReplacementIsSetByPatchLoadedByFieldsAndRecordedAsAChange() throws Exception {
    final Table old = createServiceDatabaseSchemaTable("repl_old");
    final Table replacement = createServiceDatabaseSchemaTable("repl_new");

    final JsonNode patched = patch("tables/" + old.getId(), addReplacement(replacement));

    assertThat(patched.path("changeDescription").path("fieldsAdded").toString())
        .contains("\"name\":\"replacedBy\"");
    assertThat(patched.path("version").asDouble()).isEqualTo(0.2);
    assertThat(
            get("tables/" + old.getId() + "?fields=replacedBy", JsonNode.class)
                .path("replacedBy")
                .path("fullyQualifiedName")
                .asText())
        .isEqualTo(replacement.getFullyQualifiedName());
    assertThat(get("tables/" + old.getId(), JsonNode.class).has("replacedBy")).isFalse();
    assertThat(
            get("tables/name/" + old.getFullyQualifiedName() + "?fields=replacedBy", JsonNode.class)
                .path("replacedBy")
                .path("id")
                .asText())
        .isEqualTo(replacement.getId().toString());
    assertThat(
            get(
                    "tables?fields=replacedBy&databaseSchema="
                        + old.getDatabaseSchema().getFullyQualifiedName(),
                    JsonNode.class)
                .path("data")
                .get(0)
                .path("replacedBy")
                .path("id")
                .asText())
        .as("the list path loads it in bulk")
        .isEqualTo(replacement.getId().toString());

    final JsonNode cleared =
        patch("tables/" + old.getId(), "[{\"op\":\"remove\",\"path\":\"/replacedBy\"}]");

    assertThat(cleared.path("changeDescription").path("fieldsDeleted").toString())
        .contains("\"name\":\"replacedBy\"");
    assertThat(
            get("tables/" + old.getId() + "?fields=replacedBy", JsonNode.class).has("replacedBy"))
        .isFalse();
  }

  @Test
  void aCreateRequestNamesTheReplacementAndAPutThatOmitsItKeepsIt() throws Exception {
    final Table replacement = createServiceDatabaseSchemaTable("repl_create_new");
    final Table old = createServiceDatabaseSchemaTable("repl_create_old");
    final Map<String, Object> request =
        Map.of(
            "name", old.getName(),
            "databaseSchema", old.getDatabaseSchema().getFullyQualifiedName(),
            "columns", List.of(Map.of("name", "id", "dataType", "BIGINT")),
            "replacedBy", replacement.getFullyQualifiedName());

    put("tables", request, JsonNode.class);

    assertThat(replacedByOf(old)).isEqualTo(replacement.getFullyQualifiedName());
    final Map<String, Object> ingestion = new java.util.HashMap<>(request);
    ingestion.remove("replacedBy");
    ingestion.put("description", "refreshed by ingestion");
    put("tables", ingestion, JsonNode.class);
    assertThat(replacedByOf(old))
        .as("an ingestion PUT does not know the field and must not clear it")
        .isEqualTo(replacement.getFullyQualifiedName());
  }

  @Test
  void theReplacementMustBeAnotherLiveTable() throws Exception {
    final Table table = createServiceDatabaseSchemaTable("repl_bad");
    final Table gone = createServiceDatabaseSchemaTable("repl_gone");
    delete("tables/" + gone.getId());

    assertThatThrownBy(() -> patch("tables/" + table.getId(), addReplacement(table)))
        .hasMessageContaining("HTTP 400")
        .hasMessageContaining("cannot be replaced by itself");
    assertThatThrownBy(
            () ->
                patch(
                    "tables/" + table.getId(),
                    addReplacement(table.getDatabaseSchema().getId(), "databaseSchema")))
        .hasMessageContaining("HTTP 400")
        .hasMessageContaining("must reference a table");
    assertThatThrownBy(() -> patch("tables/" + table.getId(), addReplacement(gone)))
        .hasMessageContaining("HTTP 404");
    assertThatThrownBy(
            () -> patch("tables/" + table.getId(), addReplacement(UUID.randomUUID(), "table")))
        .hasMessageContaining("HTTP 404");
  }

  @Test
  void hardDeletingTheReplacementDropsTheLink() throws Exception {
    final Table old = createServiceDatabaseSchemaTable("repl_hd_old");
    final Table replacement = createServiceDatabaseSchemaTable("repl_hd_new");
    patch("tables/" + old.getId(), addReplacement(replacement));

    delete("tables/" + replacement.getId() + "?hardDelete=true&recursive=true");

    assertThat(
            get("tables/" + old.getId() + "?fields=replacedBy", JsonNode.class).has("replacedBy"))
        .isFalse();
  }

  @Test
  void hardDeletingTheReplacedTableLeavesTheReplacementUntouched() throws Exception {
    final Table old = createServiceDatabaseSchemaTable("repl_hd2_old");
    final Table replacement = createServiceDatabaseSchemaTable("repl_hd2_new");
    patch("tables/" + old.getId(), addReplacement(replacement));

    delete("tables/" + old.getId() + "?hardDelete=true&recursive=true");

    assertThat(get("tables/" + replacement.getId(), JsonNode.class).path("deleted").asBoolean())
        .isFalse();
  }

  private static String replacedByOf(Table table) throws Exception {
    return get(
            "tables/name/" + table.getFullyQualifiedName() + "?fields=replacedBy", JsonNode.class)
        .path("replacedBy")
        .path("fullyQualifiedName")
        .asText();
  }

  private static String addReplacement(Table replacement) {
    return addReplacement(replacement.getId(), "table");
  }

  private static String addReplacement(UUID id, String type) {
    return "[{\"op\":\"add\",\"path\":\"/replacedBy\",\"value\":{\"id\":\""
        + id
        + "\",\"type\":\""
        + type
        + "\"}}]";
  }
}
