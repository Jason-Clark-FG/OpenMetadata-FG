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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.AnswerCitation;
import org.openmetadata.schema.api.context.AnswerConcept;
import org.openmetadata.schema.api.context.AnswerConceptType;
import org.openmetadata.schema.api.context.AnswerExecutor;
import org.openmetadata.schema.api.context.AnswerFailingTest;
import org.openmetadata.schema.api.context.AnswerFreshness;
import org.openmetadata.schema.api.context.AnswerFreshnessSource;
import org.openmetadata.schema.api.context.AnswerOpenIncident;
import org.openmetadata.schema.api.context.AnswerTable;
import org.openmetadata.schema.api.context.AnswerUnresolved;
import org.openmetadata.schema.api.context.AnswerWindowResolver;
import org.openmetadata.schema.api.context.FinishAnswer;
import org.openmetadata.schema.api.context.FinishAnswerConcept;
import org.openmetadata.schema.api.context.FinishAnswerExecution;
import org.openmetadata.schema.api.context.FinishAnswerTable;
import org.openmetadata.schema.api.context.FinishAnswerTimeWindow;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.aicontext.answer.AnswerCatalog.Lookup;

class AnswerCitationBuilderTest {
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

  /** The catalog boundary: what a caller can read, keyed by name. */
  private static final class FakeCatalog implements AnswerCatalog {
    final Map<String, AnswerConcept> terms = new HashMap<>();
    final Map<String, AnswerConcept> metrics = new HashMap<>();
    final Map<String, AnswerTable> tables = new HashMap<>();
    final List<String> hidden = new ArrayList<>();

    @Override
    public Lookup<AnswerConcept> concept(final AnswerConceptType type, final String fqn) {
      final Map<String, AnswerConcept> known = type == AnswerConceptType.METRIC ? metrics : terms;
      return lookup(known.get(fqn), fqn);
    }

    @Override
    public Lookup<AnswerTable> table(final String fqn) {
      return lookup(tables.get(fqn), fqn);
    }

    private <T> Lookup<T> lookup(final T value, final String fqn) {
      return hidden.contains(fqn)
          ? Lookup.missing(AnswerUnresolved.NOT_VISIBLE)
          : value == null ? Lookup.missing(AnswerUnresolved.NOT_FOUND) : Lookup.found(value);
    }
  }

  private static AnswerCitation build(
      final FakeCatalog catalog, final AnswerSettings settings, final FinishAnswer request) {
    return new AnswerCitationBuilder(catalog, settings, NOW).build(request);
  }

  private static FinishAnswer request() {
    return new FinishAnswer().withQuestion("q").withAnswer("a");
  }

  @Test
  void aNameTheCatalogResolvesCarriesItsFactsAndTheAgentsPhrase() {
    final FakeCatalog catalog = new FakeCatalog();
    catalog.metrics.put(
        "finance.arr",
        new AnswerConcept()
            .withEntityType(AnswerConceptType.METRIC)
            .withFqn("finance.arr")
            .withCertified(true)
            .withVersion(3.0));

    final AnswerCitation citation =
        build(
            catalog,
            AnswerSettings.defaults(),
            request()
                .withConcepts(
                    List.of(new FinishAnswerConcept().withPhrase("ARR").withFqn("finance.arr"))));

    final AnswerConcept concept = citation.getConcepts().get(0);
    assertEquals("ARR", concept.getPhrase());
    assertEquals(AnswerConceptType.METRIC, concept.getEntityType());
    assertTrue(concept.getCertified());
    assertNull(concept.getUnresolved());
  }

  @Test
  void aConceptWithoutATypeIsLookedUpAsATermThenAMetric() {
    final FakeCatalog catalog = new FakeCatalog();
    catalog.terms.put(
        "g.churn", new AnswerConcept().withEntityType(AnswerConceptType.GLOSSARY_TERM));
    catalog.metrics.put("m.arr", new AnswerConcept().withEntityType(AnswerConceptType.METRIC));

    final AnswerCitation citation =
        build(
            catalog,
            AnswerSettings.defaults(),
            request()
                .withConcepts(
                    List.of(
                        new FinishAnswerConcept().withFqn("g.churn"),
                        new FinishAnswerConcept().withFqn("m.arr"))));

    assertEquals(AnswerConceptType.GLOSSARY_TERM, citation.getConcepts().get(0).getEntityType());
    assertEquals(AnswerConceptType.METRIC, citation.getConcepts().get(1).getEntityType());
    assertEquals("g.churn", citation.getConcepts().get(0).getPhrase());
  }

  @Test
  void aHiddenNameIsReportedWithoutItsNameAndAnUnknownNameWithIt() {
    final FakeCatalog catalog = new FakeCatalog();
    catalog.hidden.add("secret.salaries");
    catalog.hidden.add("secret.pay");

    final AnswerCitation citation =
        build(
            catalog,
            AnswerSettings.defaults(),
            request()
                .withConcepts(
                    List.of(
                        new FinishAnswerConcept()
                            .withPhrase("pay")
                            .withEntityType(AnswerConceptType.METRIC)
                            .withFqn("secret.pay"),
                        new FinishAnswerConcept().withPhrase("typo").withFqn("g.typo")))
                .withTables(
                    List.of(
                        new FinishAnswerTable().withFqn("secret.salaries"),
                        new FinishAnswerTable().withFqn("svc.db.typo"))));

    final AnswerConcept hiddenConcept = citation.getConcepts().get(0);
    assertEquals(AnswerUnresolved.NOT_VISIBLE, hiddenConcept.getUnresolved());
    assertNull(hiddenConcept.getFqn());
    assertEquals(AnswerUnresolved.NOT_FOUND, citation.getConcepts().get(1).getUnresolved());
    assertEquals("g.typo", citation.getConcepts().get(1).getFqn());
    assertEquals(AnswerUnresolved.NOT_VISIBLE, citation.getTables().get(0).getUnresolved());
    assertNull(citation.getTables().get(0).getFqn());
    assertEquals(AnswerUnresolved.NOT_FOUND, citation.getTables().get(1).getUnresolved());
    assertFalse(
        JsonUtils.pojoToJson(citation).contains("secret"),
        "nothing about a hidden name is in the citation");
  }

  private static AnswerTable tableWith(final Instant freshAt, final AnswerFreshnessSource source) {
    return new AnswerTable()
        .withFqn("svc.db.sch.arr_daily")
        .withFreshness(
            freshAt == null
                ? null
                : new AnswerFreshness().withSource(source).withAt(freshAt.toEpochMilli()))
        .withFailingTests(List.of(new AnswerFailingTest().withName("t")))
        .withOpenIncidents(List.of(new AnswerOpenIncident().withId("i")));
  }

  @Test
  void freshnessOlderThanTheHarnessLimitIsStaleAndMissingFreshnessIsUnknown() {
    final FakeCatalog catalog = new FakeCatalog();
    catalog.tables.put(
        "svc.db.sch.old",
        tableWith(NOW.minus(Duration.ofHours(25)), AnswerFreshnessSource.PROFILE));
    catalog.tables.put(
        "svc.db.sch.new", tableWith(NOW.minus(Duration.ofHours(23)), AnswerFreshnessSource.TEST));
    catalog.tables.put("svc.db.sch.none", tableWith(null, null));

    final AnswerCitation citation =
        build(
            catalog,
            AnswerSettings.defaults(),
            request()
                .withTables(
                    List.of(
                        new FinishAnswerTable().withFqn("svc.db.sch.old"),
                        new FinishAnswerTable().withFqn("svc.db.sch.new"),
                        new FinishAnswerTable().withFqn("svc.db.sch.none"))));

    assertTrue(citation.getTables().get(0).getFreshness().getStale());
    assertFalse(citation.getTables().get(1).getFreshness().getStale());
    assertEquals(
        AnswerFreshnessSource.UNKNOWN, citation.getTables().get(2).getFreshness().getSource());
  }

  @Test
  void trustSignalsTheHarnessIgnoresAreDroppedAndMarkedUnchecked() {
    final FakeCatalog catalog = new FakeCatalog();
    catalog.tables.put("svc.db.sch.arr_daily", tableWith(NOW, AnswerFreshnessSource.PROFILE));
    final AnswerSettings ignoreTests =
        new AnswerSettings(
            FiscalCalendar.Settings.defaults(), false, true, Duration.ofHours(24), true);

    final AnswerCitation citation =
        build(
            catalog,
            ignoreTests,
            request().withTables(List.of(new FinishAnswerTable().withFqn("svc.db.sch.arr_daily"))));

    assertTrue(citation.getTables().get(0).getFailingTests().isEmpty());
    assertEquals(1, citation.getTables().get(0).getOpenIncidents().size());
    assertFalse(citation.getTrustChecked().getFailingTests());
    assertTrue(citation.getTrustChecked().getOpenIncidents());
  }

  @Test
  void aKnownPhraseIsResolvedByTheServerAndDisagreeingDatesAreFlagged() {
    final AnswerCitation citation =
        build(
            new FakeCatalog(),
            AnswerSettings.defaults(),
            request()
                .withTimeWindow(
                    new FinishAnswerTimeWindow()
                        .withPhrase("last month")
                        .withStart("2026-10-02")
                        .withEnd("2026-10-31")));

    assertEquals(AnswerWindowResolver.SERVER, citation.getTimeWindow().getResolvedBy());
    assertEquals("2026-10-01", citation.getTimeWindow().getStart());
    assertEquals("October 2026", citation.getTimeWindow().getLabel());
    assertEquals("UTC", citation.getTimeWindow().getTimeZone());
    assertEquals("01-01", citation.getTimeWindow().getFiscalYearStart());
    assertEquals(
        List.of(
            "The agent's dates for \"last month\" differ from the organization's calendar. The"
                + " calendar's dates are shown."),
        citation.getWarnings());
  }

  @Test
  void anUnknownPhraseKeepsTheAgentsDatesWithAWarning() {
    final AnswerCitation citation =
        build(
            new FakeCatalog(),
            AnswerSettings.defaults(),
            request()
                .withTimeWindow(
                    new FinishAnswerTimeWindow()
                        .withPhrase("since the launch")
                        .withStart("2026-06-01")
                        .withEnd("2026-06-30")));

    assertEquals(AnswerWindowResolver.AGENT, citation.getTimeWindow().getResolvedBy());
    assertEquals("2026-06-01", citation.getTimeWindow().getStart());
    assertEquals(
        List.of(
            "\"since the launch\" is not a phrase the calendar knows. The dates are the agent's."),
        citation.getWarnings());
  }

  @Test
  void anUnknownPhraseWithoutUsableDatesYieldsAWindowWithNoDates() {
    final AnswerCitation citation =
        build(
            new FakeCatalog(),
            AnswerSettings.defaults(),
            request()
                .withTimeWindow(
                    new FinishAnswerTimeWindow().withPhrase("sometime").withStart("soon")));

    assertNull(citation.getTimeWindow().getStart());
    assertEquals(
        List.of(
            "\"sometime\" is not a phrase the calendar knows, and the agent gave no valid dates."),
        citation.getWarnings());
  }

  @Test
  void noWindowIsCitedWhenTheAgentGivesNone() {
    final AnswerCitation citation =
        build(
            new FakeCatalog(),
            AnswerSettings.defaults(),
            request().withTimeWindow(new FinishAnswerTimeWindow()));

    assertNull(citation.getTimeWindow());
  }

  @Test
  void aRunIsSelfReportedAndNoExecutionMeansNotRun() {
    final AnswerCitation ran =
        build(
            new FakeCatalog(),
            AnswerSettings.defaults(),
            request()
                .withExecution(
                    new FinishAnswerExecution()
                        .withExecutor(AnswerExecutor.HOST_CONNECTOR)
                        .withConnector("Snowflake")
                        .withRowCount(4)));
    final AnswerCitation notRun = build(new FakeCatalog(), AnswerSettings.defaults(), request());

    assertTrue(ran.getExecution().getSelfReported());
    assertEquals(4, ran.getExecution().getRowCount());
    assertEquals(AnswerExecutor.NONE, notRun.getExecution().getExecutor());
    assertFalse(notRun.getExecution().getSelfReported());
    assertFalse(notRun.getPlanChecked());
  }

  @Test
  void anAgentCannotClaimThatSqlStudioRanTheQuery() {
    final FinishAnswer claim =
        request()
            .withExecution(new FinishAnswerExecution().withExecutor(AnswerExecutor.SQL_STUDIO));

    assertThrows(
        IllegalArgumentException.class,
        () -> build(new FakeCatalog(), AnswerSettings.defaults(), claim));
  }

  @Test
  void citationsBeyondTheCapsAreRefused() {
    final FakeCatalog catalog = new FakeCatalog();
    final List<FinishAnswerConcept> concepts = new ArrayList<>();
    final List<FinishAnswerTable> tables = new ArrayList<>();
    for (int index = 0; index <= AnswerCitationBuilder.MAX_CONCEPTS; index++) {
      concepts.add(new FinishAnswerConcept().withFqn("c" + index));
    }
    for (int index = 0; index <= AnswerCitationBuilder.MAX_TABLES; index++) {
      tables.add(new FinishAnswerTable().withFqn("t" + index));
    }
    final AnswerSettings settings = AnswerSettings.defaults();

    assertThrows(
        IllegalArgumentException.class,
        () -> build(catalog, settings, request().withConcepts(concepts)));
    assertThrows(
        IllegalArgumentException.class,
        () -> build(catalog, settings, request().withTables(tables)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            build(
                catalog,
                settings,
                request().withSql("x".repeat(AnswerCitationBuilder.MAX_SQL_CHARS + 1))));
  }
}
