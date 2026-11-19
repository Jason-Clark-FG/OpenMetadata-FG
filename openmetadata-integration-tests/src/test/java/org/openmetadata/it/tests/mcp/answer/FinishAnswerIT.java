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
package org.openmetadata.it.tests.mcp.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openmetadata.it.tests.mcp.McpTestBase;
import org.openmetadata.it.tests.mcp.McpTestUtils;
import org.openmetadata.it.util.SdkClients;
import org.openmetadata.schema.api.tests.CreateTestCase;
import org.openmetadata.schema.api.tests.CreateTestCaseResult;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.tests.type.TestCaseStatus;
import org.openmetadata.sdk.client.OpenMetadataClient;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.TestCaseResolutionStatusRepository;

/**
 * {@code finish_answer} cites what the catalog holds, as the caller may read it. The caller is a
 * user with no admin rights who may view everything except tables tagged {@code PII.Sensitive}.
 */
@Execution(ExecutionMode.CONCURRENT)
class FinishAnswerIT extends McpTestBase {
  private static final String HIDDEN_TAG_FQN = "PII.Sensitive";
  private static final String VERBATIM =
      "Show the answer and the \"How I got this\" list above to the user as written. Do not reword it"
          + " or add facts to it.";

  private static final long PROFILE_AGE_SECONDS = 3 * 3600 + 600;

  private static String suffix;
  private static String analystToken;
  private static String termFqn;
  private static Table certified;
  private static Table deprecated;
  private static Table hidden;
  private static String failingTestName;
  private static String incidentId;

  @BeforeAll
  static void setup() throws Exception {
    initAuth();
    suffix = UUID.randomUUID().toString().substring(0, 8);
    JsonNode team =
        post(
            "teams",
            Map.of(
                "name",
                "faFinance" + suffix,
                "displayName",
                "Finance Analytics",
                "teamType",
                "Group"),
            JsonNode.class);
    Map<String, Object> owner = Map.of("id", team.get("id").asText(), "type", "team");

    termFqn = createTerm(owner);
    analystToken = "Bearer " + tokenFor(createAnalyst());
    certified = createCertifiedTableWithAFailingTest(owner);
    deprecated = createDeprecatedTable(certified);
    hidden = createHiddenTable();
  }

  @Test
  void citesTrustSignalsAsTheCallerSeesThemAndNeverNamesWhatIsHidden() throws Exception {
    assertThat(
            getResponse("tables/name/" + hidden.getFullyQualifiedName(), analystToken).statusCode())
        .as("the fixture hides the table from the analyst")
        .isEqualTo(403);
    assertThat(
            getResponse("tables/name/" + certified.getFullyQualifiedName(), analystToken)
                .statusCode())
        .isEqualTo(200);

    JsonNode result = finishAnswer(analystToken, arguments());

    assertThat(result.path("isError").asBoolean()).isFalse();
    String text = result.path("content").get(0).path("text").asText();
    YearMonth lastMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(1);
    String monthLabel =
        lastMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            + " "
            + lastMonth.getYear();

    assertThat(text).startsWith("ARR was 12.4M.\n\n**How I got this**\n");
    assertThat(text).contains("\"ARR\" → glossary term [");
    assertThat(text).contains("owner Finance Analytics");
    assertThat(text)
        .contains("\"last month\" → " + monthLabel + ", " + lastMonth.atDay(1) + " to ");
    assertThat(text)
        .contains("Data → [`" + certified.getFullyQualifiedName() + "`](http")
        .contains(
            "/table/"
                + certified.getFullyQualifiedName()
                + ") (Tier 1, certified, owner Finance Analytics), profiled 3 h ago");
    assertThat(text)
        .contains(
            "⚠ Failing test on `"
                + certified.getFullyQualifiedName()
                + "`: ["
                + failingTestName
                + "](");
    assertThat(text)
        .contains(
            "⚠ Open incident `"
                + incidentId
                + "` on `"
                + certified.getFullyQualifiedName()
                + "` (");
    assertThat(text).contains("(not certified, deprecated)");
    assertThat(text)
        .contains(
            "⚠ `"
                + deprecated.getFullyQualifiedName()
                + "` is deprecated, replaced by `"
                + certified.getFullyQualifiedName()
                + "`.");
    assertThat(text).contains("Data → a table that is not visible to you");
    assertThat(text).contains("Data → `svc.does.not.exist` not found in OpenMetadata");
    assertThat(text).contains("Not run: no warehouse connector is connected here. SQL below.");
    assertThat(text).contains("```sql\nSELECT SUM(arr) FROM arr_daily\n```");
    assertThat(text).endsWith(VERBATIM);

    String structured = result.path("structuredContent").toString();
    assertThat(text)
        .doesNotContain(hidden.getName())
        .doesNotContain(hidden.getFullyQualifiedName());
    assertThat(structured)
        .doesNotContain(hidden.getName())
        .doesNotContain(hidden.getFullyQualifiedName());

    JsonNode tables = result.path("structuredContent").path("tables");
    assertThat(tables.get(0).path("certified").asBoolean()).isTrue();
    assertThat(tables.get(0).path("tier").asText()).isEqualTo("Tier1");
    assertThat(tables.get(0).path("failingTests").get(0).path("name").asText())
        .isEqualTo(failingTestName);
    assertThat(tables.get(0).path("openIncidents").get(0).path("id").asText())
        .isEqualTo(incidentId);
    assertThat(tables.get(1).path("deprecated").asBoolean()).isTrue();
    assertThat(tables.get(1).path("replacedBy").asText())
        .isEqualTo(certified.getFullyQualifiedName());
    assertThat(tables.get(2).path("unresolved").asText()).isEqualTo("notVisible");
    assertThat(tables.get(2).has("fqn")).isFalse();
    assertThat(tables.get(3).path("unresolved").asText()).isEqualTo("notFound");
    assertThat(result.path("structuredContent").path("planChecked").asBoolean()).isFalse();
  }

  @Test
  void aTermAndAMetricThatDoNotExistAreNotFoundAndNothingIsRequiredFirst() throws Exception {
    JsonNode result =
        finishAnswer(
            analystToken,
            Map.of(
                "question",
                "What is churn?",
                "answer",
                "Not computed.",
                "concepts",
                List.of("finance.nothing")));

    String text = result.path("content").get(0).path("text").asText();
    assertThat(text).contains("\"finance.nothing\" → `finance.nothing` not found in OpenMetadata");
    assertThat(text).contains("Not run: the query was not run in this chat.");
  }

  @Test
  void theToolIsListedAsReadOnly() throws Exception {
    JsonNode tools =
        executeMcpRequest(McpTestUtils.createJsonRpcRequest("tools/list", Map.of()), analystToken)
            .path("result")
            .path("tools");

    JsonNode tool = null;
    for (JsonNode candidate : tools) {
      if ("finish_answer".equals(candidate.path("name").asText())) {
        tool = candidate;
      }
    }
    assertThat(tool).isNotNull();
    assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
  }

  private JsonNode finishAnswer(String token, Map<String, Object> arguments) throws Exception {
    return executeMcpRequest(McpTestUtils.createToolCallRequest("finish_answer", arguments), token)
        .path("result");
  }

  private static Map<String, Object> arguments() {
    return Map.of(
        "question",
        "What was last month's ARR?",
        "answer",
        "ARR was 12.4M.",
        "concepts",
        List.of(Map.of("phrase", "ARR", "entityType", "glossaryTerm", "fqn", termFqn)),
        "timeWindow",
        Map.of("phrase", "last month"),
        "tables",
        List.of(
            Map.of("fqn", certified.getFullyQualifiedName()),
            Map.of("fqn", deprecated.getFullyQualifiedName()),
            Map.of("fqn", hidden.getFullyQualifiedName()),
            Map.of("fqn", "svc.does.not.exist")),
        "sql",
        "SELECT SUM(arr) FROM arr_daily",
        "execution",
        Map.of("executor", "none", "reason", "no warehouse connector is connected here"));
  }

  private static String createTerm(Map<String, Object> owner) throws Exception {
    JsonNode glossary =
        post(
            "glossaries",
            Map.of("name", "faGlossary" + suffix, "description", "Finance terms"),
            JsonNode.class);
    JsonNode term =
        post(
            "glossaryTerms",
            Map.of(
                "name",
                "ARR",
                "glossary",
                glossary.get("fullyQualifiedName").asText(),
                "description",
                "Annual recurring revenue",
                "owners",
                List.of(owner)),
            JsonNode.class);
    return term.get("fullyQualifiedName").asText();
  }

  private static String createAnalyst() throws Exception {
    Map<String, Object> allow =
        Map.of(
            "name",
            "AllowViewing",
            "effect",
            "allow",
            "operations",
            List.of("ViewAll"),
            "resources",
            List.of("All"));
    Map<String, Object> deny =
        Map.of(
            "name",
            "DenyHiddenTables",
            "effect",
            "deny",
            "operations",
            List.of("ViewAll", "ViewBasic"),
            "resources",
            List.of(Entity.TABLE),
            "condition",
            "matchAnyTag('" + HIDDEN_TAG_FQN + "')");
    JsonNode policy =
        post(
            "policies",
            Map.of("name", "faAnalyst" + suffix, "rules", List.of(allow, deny)),
            JsonNode.class);
    JsonNode role =
        post(
            "roles",
            Map.of(
                "name",
                "faAnalyst" + suffix,
                "policies",
                List.of(policy.get("fullyQualifiedName").asText())),
            JsonNode.class);
    String name = "fa_analyst_" + suffix;
    post(
        "users",
        Map.of(
            "name",
            name,
            "email",
            name + "@test.openmetadata.org",
            "roles",
            List.of(role.get("id").asText())),
        JsonNode.class);
    return name;
  }

  private static Table createCertifiedTableWithAFailingTest(Map<String, Object> owner)
      throws Exception {
    Table table = createServiceDatabaseSchemaTable("fa_cert");
    patch(
        "tables/" + table.getId(),
        OBJECT_MAPPER.writeValueAsString(
            List.of(
                Map.of("op", "add", "path", "/owners", "value", List.of(owner)),
                Map.of("op", "add", "path", "/tags/-", "value", label("Tier.Tier1")),
                Map.of(
                    "op",
                    "add",
                    "path",
                    "/certification",
                    "value",
                    Map.of("tagLabel", label("Certification.Gold"))))));

    put(
        "tables/" + table.getId() + "/tableProfile",
        Map.of(
            "tableProfile",
            Map.of(
                "timestamp",
                Instant.now().minusSeconds(PROFILE_AGE_SECONDS).toEpochMilli(),
                "columnCount",
                3,
                "rowCount",
                10)),
        JsonNode.class);

    OpenMetadataClient client = SdkClients.adminClient();
    failingTestName = "failingNotNull" + suffix;
    TestCase testCase =
        client
            .testCases()
            .create(
                new CreateTestCase()
                    .withName(failingTestName)
                    .withEntityLink(
                        "<#E::table::" + table.getFullyQualifiedName() + "::columns::id>")
                    .withTestDefinition("columnValuesToBeNotNull"));
    client
        .testCaseResults()
        .create(
            testCase.getFullyQualifiedName(),
            new CreateTestCaseResult()
                .withTimestamp(System.currentTimeMillis())
                .withTestCaseStatus(TestCaseStatus.Failed)
                .withResult("Test failed"));
    incidentId =
        ((TestCaseResolutionStatusRepository)
                Entity.getEntityTimeSeriesRepository(Entity.TEST_CASE_RESOLUTION_STATUS))
            .getLatestRecord(testCase.getFullyQualifiedName())
            .getStateId()
            .toString();
    return table;
  }

  private static Table createDeprecatedTable(Table replacement) throws Exception {
    Table table = createServiceDatabaseSchemaTable("fa_dep");
    patch(
        "tables/" + table.getId(),
        OBJECT_MAPPER.writeValueAsString(
            List.of(
                Map.of("op", "add", "path", "/entityStatus", "value", "Deprecated"),
                Map.of(
                    "op",
                    "add",
                    "path",
                    "/replacedBy",
                    "value",
                    Map.of("id", replacement.getId().toString(), "type", "table")))));
    return table;
  }

  private static Table createHiddenTable() throws Exception {
    Table table = createServiceDatabaseSchemaTable("fa_hidden");
    patch(
        "tables/" + table.getId(),
        OBJECT_MAPPER.writeValueAsString(
            List.of(Map.of("op", "add", "path", "/tags/-", "value", label(HIDDEN_TAG_FQN)))));
    return table;
  }

  private static Map<String, Object> label(String tagFqn) {
    return Map.of(
        "tagFQN", tagFqn, "source", "Classification", "labelType", "Manual", "state", "Confirmed");
  }
}
