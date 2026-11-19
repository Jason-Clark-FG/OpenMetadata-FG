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
package org.openmetadata.service.aicontext.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.AnswerCitation;
import org.openmetadata.schema.api.context.AnswerConcept;
import org.openmetadata.schema.api.context.AnswerConceptType;
import org.openmetadata.schema.api.context.AnswerExecution;
import org.openmetadata.schema.api.context.AnswerExecutor;
import org.openmetadata.schema.api.context.AnswerFailingTest;
import org.openmetadata.schema.api.context.AnswerFreshness;
import org.openmetadata.schema.api.context.AnswerFreshnessSource;
import org.openmetadata.schema.api.context.AnswerOpenIncident;
import org.openmetadata.schema.api.context.AnswerResult;
import org.openmetadata.schema.api.context.AnswerResultBreakdown;
import org.openmetadata.schema.api.context.AnswerTable;
import org.openmetadata.schema.api.context.AnswerTimeWindow;
import org.openmetadata.schema.api.context.AnswerTrustChecked;
import org.openmetadata.schema.api.context.AnswerUnresolved;
import org.openmetadata.schema.api.context.AnswerWindowResolver;

class AnswerMarkdownTest {
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  private static final String ARR_DAILY = "svc.db.sch.arr_daily";

  private static AnswerCitation citation(final String headline, final AnswerExecution execution) {
    return new AnswerCitation()
        .withQuestion("What was last month's ARR?")
        .withHeadline(headline)
        .withExecution(execution);
  }

  private static String block(final String... lines) {
    return String.join("\n", lines) + "\n";
  }

  @Test
  void aCertifiedMetricAndTableForAQueryThatWasNotRun() {
    final AnswerCitation citation =
        citation(
                "ARR, October 2026: not computed. The query was not run in this chat.",
                new AnswerExecution()
                    .withExecutor(AnswerExecutor.NONE)
                    .withReason("no warehouse connector is connected here"))
            .withConcepts(
                List.of(
                    new AnswerConcept()
                        .withPhrase("ARR")
                        .withEntityType(AnswerConceptType.METRIC)
                        .withFqn("ARR")
                        .withDisplayName("Annual Recurring Revenue")
                        .withCertified(true)
                        .withVersion(3.0)
                        .withOwners(List.of("Finance Analytics"))
                        .withUrl("http://om/metric/ARR")))
            .withTimeWindow(
                new AnswerTimeWindow()
                    .withPhrase("last month")
                    .withLabel("October 2026")
                    .withStart("2026-10-01")
                    .withEnd("2026-10-31")
                    .withTimeZone("America/Chicago")
                    .withFiscalYearStart("01-01")
                    .withResolvedBy(AnswerWindowResolver.SERVER))
            .withTables(
                List.of(
                    new AnswerTable()
                        .withFqn(ARR_DAILY)
                        .withTier("Tier1")
                        .withCertified(true)
                        .withUrl("http://om/table/" + ARR_DAILY)
                        .withFreshness(
                            new AnswerFreshness()
                                .withSource(AnswerFreshnessSource.PROFILE)
                                .withAt(Instant.parse("2026-11-18T09:00:00Z").toEpochMilli()))))
            .withSql("SELECT 1");

    assertEquals(
        block(
            "ARR, October 2026: not computed. The query was not run in this chat.",
            "",
            "**How I got this**",
            "",
            "1. \"ARR\" → metric [Annual Recurring Revenue](http://om/metric/ARR) (certified, owner"
                + " Finance Analytics, v3)",
            "2. \"last month\" → October 2026, 2026-10-01 to 2026-10-31 (America/Chicago)",
            "3. Data → [`svc.db.sch.arr_daily`](http://om/table/svc.db.sch.arr_daily) (Tier 1,"
                + " certified), profiled 3 h ago",
            "4. Trust → no failing tests, no open incidents",
            "5. Not run: no warehouse connector is connected here. SQL below.",
            "",
            "```sql",
            "SELECT 1",
            "```"),
        AnswerMarkdown.render(citation, NOW));
  }

  @Test
  void theAgentsNumbersGetACompactBlockMarkedAsReportedByTheAgent() {
    final AnswerCitation citation =
        citation(
                "Q3 revenue was $48.2M.",
                new AnswerExecution().withExecutor(AnswerExecutor.NONE).withReason("no connector"))
            .withResult(
                new AnswerResult()
                    .withLabel("Q3 2026 revenue by region")
                    .withValue("$48.2M")
                    .withChange("+12.4% vs Q2")
                    .withBreakdown(
                        List.of(
                            new AnswerResultBreakdown()
                                .withLabel("NA")
                                .withValue("$21.6M")
                                .withChange("+9%"),
                            new AnswerResultBreakdown().withLabel("EMEA").withValue("$14.1M"))));

    assertEquals(
        block(
            "Q3 revenue was $48.2M.",
            "",
            "**$48.2M** +12.4% vs Q2 · Q3 2026 revenue by region _(reported by the agent, not"
                + " computed by OpenMetadata)_",
            "",
            "- NA: $21.6M +9%",
            "- EMEA: $14.1M",
            "",
            "**How I got this**",
            "",
            "1. Not run: no connector."),
        AnswerMarkdown.render(citation, NOW));
  }

  @Test
  void deprecatedStaleFailingAndIncidentTablesForAQueryTheHostRan() {
    final String testUrl = "http://om/test-case/" + ARR_DAILY + ".arr_not_null/test-case-results";
    final String incidentUrl = "http://om/test-case/" + ARR_DAILY + ".arr_not_null/issues";
    final String incidentId = "11111111-1111-1111-1111-111111111111";
    final AnswerCitation citation =
        citation(
                "ARR was 12.4M.",
                new AnswerExecution()
                    .withExecutor(AnswerExecutor.HOST_CONNECTOR)
                    .withConnector("Snowflake")
                    .withRowCount(1)
                    .withSelfReported(true))
            .withTables(
                List.of(
                    new AnswerTable()
                        .withFqn("svc.db.sch.arr_v1")
                        .withDeprecated(true)
                        .withReplacedBy(ARR_DAILY)
                        .withOwners(List.of("Finance Analytics", "Data Platform"))
                        .withFreshness(
                            new AnswerFreshness().withSource(AnswerFreshnessSource.UNKNOWN)),
                    new AnswerTable()
                        .withFqn(ARR_DAILY)
                        .withTier("Tier1")
                        .withCertified(true)
                        .withUrl("http://om/table/" + ARR_DAILY)
                        .withFreshness(
                            new AnswerFreshness()
                                .withSource(AnswerFreshnessSource.TEST)
                                .withAt(Instant.parse("2026-11-16T12:00:00Z").toEpochMilli())
                                .withStale(true))
                        .withFailingTests(
                            List.of(
                                new AnswerFailingTest().withName("arr_not_null").withUrl(testUrl)))
                        .withOpenIncidents(
                            List.of(
                                new AnswerOpenIncident()
                                    .withId(incidentId)
                                    .withTestCase("arr_not_null")
                                    .withStatus("Assigned")
                                    .withSeverity("Severity2")
                                    .withUrl(incidentUrl)))))
            .withSql("SELECT 2")
            .withShowSql(false);

    assertEquals(
        block(
            "ARR was 12.4M.",
            "",
            "**How I got this**",
            "",
            "1. Data → `svc.db.sch.arr_v1` (not certified, deprecated, owners Finance Analytics, Data"
                + " Platform), freshness unknown",
            "2. Data → [`svc.db.sch.arr_daily`](http://om/table/svc.db.sch.arr_daily) (Tier 1,"
                + " certified), ⚠ freshness tested 2 d ago, stale",
            "3. ⚠ `svc.db.sch.arr_v1` is deprecated, replaced by `svc.db.sch.arr_daily`.",
            "4. ⚠ Failing test on `svc.db.sch.arr_daily`: [arr\\_not\\_null](" + testUrl + ")",
            "5. ⚠ Open incident `"
                + incidentId
                + "` on `svc.db.sch.arr_daily` (Assigned, Severity2):"
                + " [arr\\_not\\_null]("
                + incidentUrl
                + ")",
            "6. Ran through your Snowflake connector, 1 row. Self-reported: OpenMetadata did not run"
                + " it."),
        AnswerMarkdown.render(citation, NOW));
  }

  @Test
  void namesTheCallerCannotSeeNeverAppearAndUnknownNamesAreSaidToBeMissing() {
    final AnswerCitation citation =
        citation("Cannot say.", new AnswerExecution().withExecutor(AnswerExecutor.NONE))
            .withConcepts(
                List.of(
                    new AnswerConcept()
                        .withPhrase("churn")
                        .withEntityType(AnswerConceptType.GLOSSARY_TERM)
                        .withUnresolved(AnswerUnresolved.NOT_VISIBLE),
                    new AnswerConcept()
                        .withPhrase("ARR")
                        .withEntityType(AnswerConceptType.METRIC)
                        .withFqn("finance.arr")
                        .withUnresolved(AnswerUnresolved.NOT_FOUND)))
            .withTables(
                List.of(
                    new AnswerTable().withUnresolved(AnswerUnresolved.NOT_VISIBLE),
                    new AnswerTable()
                        .withFqn("svc.db.nope")
                        .withUnresolved(AnswerUnresolved.NOT_FOUND)));

    assertEquals(
        block(
            "Cannot say.",
            "",
            "**How I got this**",
            "",
            "1. \"churn\" → a glossary term that is not visible to you",
            "2. \"ARR\" → `finance.arr` not found in OpenMetadata",
            "3. Data → a table that is not visible to you",
            "4. Data → `svc.db.nope` not found in OpenMetadata",
            "5. Not run: the query was not run in this chat."),
        AnswerMarkdown.render(citation, NOW));
  }

  @Test
  void aWindowTheAgentChoseIsLabelledAndAFiscalCalendarIsNamed() {
    final AnswerCitation agent =
        citation("Done.", new AnswerExecution().withExecutor(AnswerExecutor.NONE))
            .withTimeWindow(
                new AnswerTimeWindow()
                    .withPhrase("since the launch")
                    .withStart("2026-06-01")
                    .withEnd("2026-06-30")
                    .withResolvedBy(AnswerWindowResolver.AGENT))
            .withWarnings(List.of("\"since the launch\" is not a phrase the calendar knows."));
    final AnswerCitation fiscal =
        citation("Done.", new AnswerExecution().withExecutor(AnswerExecutor.NONE))
            .withTimeWindow(
                new AnswerTimeWindow()
                    .withPhrase("last quarter")
                    .withLabel("fiscal Q3 FY2026")
                    .withStart("2026-08-01")
                    .withEnd("2026-10-31")
                    .withTimeZone("America/Chicago")
                    .withFiscalYearStart("02-01")
                    .withResolvedBy(AnswerWindowResolver.SERVER));

    assertEquals(
        block(
            "Done.",
            "",
            "**How I got this**",
            "",
            "1. \"since the launch\" → 2026-06-01 to 2026-06-30 (dates from the agent, not checked"
                + " against the calendar)",
            "2. Not run: the query was not run in this chat.",
            "3. ⚠ \"since the launch\" is not a phrase the calendar knows."),
        AnswerMarkdown.render(agent, NOW));
    assertEquals(
        block(
            "Done.",
            "",
            "**How I got this**",
            "",
            "1. \"last quarter\" → fiscal Q3 FY2026, 2026-08-01 to 2026-10-31 (America/Chicago, fiscal"
                + " year starts Feb 1)",
            "2. Not run: the query was not run in this chat."),
        AnswerMarkdown.render(fiscal, NOW));
  }

  @Test
  void trustSignalsTheHarnessIgnoresAreNotClaimedAsChecked() {
    final AnswerTable clean =
        new AnswerTable()
            .withFqn(ARR_DAILY)
            .withCertified(true)
            .withFreshness(new AnswerFreshness().withSource(AnswerFreshnessSource.UNKNOWN));
    final AnswerCitation incidentsOnly =
        citation("Done.", new AnswerExecution().withExecutor(AnswerExecutor.NONE))
            .withTables(List.of(clean))
            .withTrustChecked(
                new AnswerTrustChecked().withFailingTests(false).withOpenIncidents(true));

    assertEquals(
        block(
            "Done.",
            "",
            "**How I got this**",
            "",
            "1. Data → `svc.db.sch.arr_daily` (certified), freshness unknown",
            "2. Trust → no open incidents",
            "3. Not run: the query was not run in this chat."),
        AnswerMarkdown.render(incidentsOnly, NOW));
  }

  @Test
  void catalogTextIsEscapedAndSqlCannotCloseItsOwnFence() {
    final AnswerCitation citation =
        citation("Done.", new AnswerExecution().withExecutor(AnswerExecutor.NONE))
            .withConcepts(
                List.of(
                    new AnswerConcept()
                        .withPhrase("a [b]")
                        .withEntityType(AnswerConceptType.METRIC)
                        .withFqn("m")
                        .withDisplayName("<script>alert(1)</script> *x*")
                        .withOwners(List.of())))
            .withSql("SELECT '```'");

    assertEquals(
        block(
            "Done.",
            "",
            "**How I got this**",
            "",
            "1. \"a \\[b\\]\" → metric \\<script\\>alert(1)\\</script\\> \\*x\\* (no owner)",
            "2. Not run: the query was not run in this chat. SQL below.",
            "",
            "````sql",
            "SELECT '```'",
            "````"),
        AnswerMarkdown.render(citation, NOW));
  }
}
