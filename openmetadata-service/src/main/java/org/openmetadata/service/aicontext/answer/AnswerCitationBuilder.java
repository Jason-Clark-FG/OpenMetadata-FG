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

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.openmetadata.schema.api.context.AnswerCitation;
import org.openmetadata.schema.api.context.AnswerConcept;
import org.openmetadata.schema.api.context.AnswerConceptType;
import org.openmetadata.schema.api.context.AnswerExecution;
import org.openmetadata.schema.api.context.AnswerExecutor;
import org.openmetadata.schema.api.context.AnswerFreshness;
import org.openmetadata.schema.api.context.AnswerFreshnessSource;
import org.openmetadata.schema.api.context.AnswerTable;
import org.openmetadata.schema.api.context.AnswerTimeWindow;
import org.openmetadata.schema.api.context.AnswerTrustChecked;
import org.openmetadata.schema.api.context.AnswerUnresolved;
import org.openmetadata.schema.api.context.AnswerWindowResolver;
import org.openmetadata.schema.api.context.FinishAnswer;
import org.openmetadata.schema.api.context.FinishAnswerConcept;
import org.openmetadata.schema.api.context.FinishAnswerExecution;
import org.openmetadata.schema.api.context.FinishAnswerTable;
import org.openmetadata.schema.api.context.FinishAnswerTimeWindow;
import org.openmetadata.service.aicontext.answer.AnswerCatalog.Lookup;

/**
 * Writes the citation for one answer. The agent supplies names; every fact comes from the catalog
 * as the caller may read it, and every name the catalog does not resolve is reported as such.
 */
public final class AnswerCitationBuilder {
  public static final int MAX_CONCEPTS = 10;
  public static final int MAX_TABLES = 5;
  public static final int MAX_SQL_CHARS = 20_000;

  private final AnswerCatalog catalog;
  private final AnswerSettings settings;
  private final Instant now;

  public AnswerCitationBuilder(
      final AnswerCatalog catalog, final AnswerSettings settings, final Instant now) {
    this.catalog = catalog;
    this.settings = settings;
    this.now = now;
  }

  public AnswerCitation build(final FinishAnswer request) {
    requireWithinCaps(request);
    final List<String> warnings = new ArrayList<>();
    final List<AnswerTable> tables =
        listOrEmpty(request.getTables()).stream().map(this::table).toList();
    final AnswerCitation citation =
        new AnswerCitation()
            .withQuestion(request.getQuestion())
            .withHeadline(request.getAnswer())
            .withConcepts(listOrEmpty(request.getConcepts()).stream().map(this::concept).toList())
            .withTables(tables)
            .withExecution(execution(request.getExecution()))
            .withSql(request.getSql())
            .withShowSql(settings.showSql())
            .withTrustChecked(
                new AnswerTrustChecked()
                    .withFailingTests(settings.reportFailingTests())
                    .withOpenIncidents(settings.reportOpenIncidents()))
            .withPlanChecked(false);
    timeWindow(request.getTimeWindow(), warnings).ifPresent(citation::setTimeWindow);
    return citation.withWarnings(warnings);
  }

  private static void requireWithinCaps(final FinishAnswer request) {
    if (listOrEmpty(request.getConcepts()).size() > MAX_CONCEPTS) {
      throw new IllegalArgumentException("Cite at most " + MAX_CONCEPTS + " concepts");
    }
    if (listOrEmpty(request.getTables()).size() > MAX_TABLES) {
      throw new IllegalArgumentException("Cite at most " + MAX_TABLES + " tables");
    }
    if (request.getSql() != null && request.getSql().length() > MAX_SQL_CHARS) {
      throw new IllegalArgumentException("sql is longer than " + MAX_SQL_CHARS + " characters");
    }
  }

  private AnswerConcept concept(final FinishAnswerConcept ref) {
    final List<AnswerConceptType> types =
        ref.getEntityType() == null
            ? List.of(AnswerConceptType.GLOSSARY_TERM, AnswerConceptType.METRIC)
            : List.of(ref.getEntityType());
    Lookup<AnswerConcept> lookup = Lookup.missing(AnswerUnresolved.NOT_FOUND);
    for (final AnswerConceptType type : types) {
      final Lookup<AnswerConcept> candidate = catalog.concept(type, ref.getFqn());
      if (candidate.value() != null) {
        lookup = candidate;
        break;
      }
      if (candidate.unresolved() == AnswerUnresolved.NOT_VISIBLE) {
        lookup = candidate;
      }
    }
    final String phrase = nullOrEmpty(ref.getPhrase()) ? ref.getFqn() : ref.getPhrase();
    return switch (lookup.unresolved()) {
      case null -> lookup.value().withPhrase(phrase);
      case NOT_VISIBLE -> new AnswerConcept()
          .withPhrase(phrase)
          .withEntityType(ref.getEntityType())
          .withUnresolved(AnswerUnresolved.NOT_VISIBLE);
      case NOT_FOUND -> new AnswerConcept()
          .withPhrase(phrase)
          .withEntityType(ref.getEntityType())
          .withFqn(ref.getFqn())
          .withUnresolved(AnswerUnresolved.NOT_FOUND);
    };
  }

  private AnswerTable table(final FinishAnswerTable ref) {
    final Lookup<AnswerTable> lookup = catalog.table(ref.getFqn());
    return switch (lookup.unresolved()) {
      case null -> trustApplied(lookup.value());
      case NOT_VISIBLE -> new AnswerTable().withUnresolved(AnswerUnresolved.NOT_VISIBLE);
      case NOT_FOUND -> new AnswerTable()
          .withFqn(ref.getFqn())
          .withUnresolved(AnswerUnresolved.NOT_FOUND);
    };
  }

  private AnswerTable trustApplied(final AnswerTable table) {
    if (!settings.reportFailingTests()) {
      table.setFailingTests(List.of());
    }
    if (!settings.reportOpenIncidents()) {
      table.setOpenIncidents(List.of());
    }
    final AnswerFreshness freshness = table.getFreshness();
    if (freshness != null && freshness.getAt() != null) {
      freshness.setStale(
          Instant.ofEpochMilli(freshness.getAt()).plus(settings.staleAfter()).isBefore(now));
    } else {
      table.setFreshness(new AnswerFreshness().withSource(AnswerFreshnessSource.UNKNOWN));
    }
    return table;
  }

  private AnswerExecution execution(final FinishAnswerExecution reported) {
    final AnswerExecutor executor =
        reported == null || reported.getExecutor() == null
            ? AnswerExecutor.NONE
            : reported.getExecutor();
    if (executor == AnswerExecutor.SQL_STUDIO) {
      throw new IllegalArgumentException(
          "execution.executor must be none or hostConnector. OpenMetadata does not run SQL");
    }
    final boolean ran = executor != AnswerExecutor.NONE;
    final AnswerExecution execution =
        new AnswerExecution().withExecutor(executor).withSelfReported(ran);
    if (reported != null) {
      execution
          .withConnector(reported.getConnector())
          .withReason(reported.getReason())
          .withRowCount(reported.getRowCount());
    }
    return execution;
  }

  private Optional<AnswerTimeWindow> timeWindow(
      final FinishAnswerTimeWindow reported, final List<String> warnings) {
    Optional<AnswerTimeWindow> window = Optional.empty();
    if (reported != null
        && !(nullOrEmpty(reported.getPhrase()) && nullOrEmpty(reported.getStart()))) {
      final FiscalCalendar.Settings calendar = settings.calendar();
      final Optional<FiscalCalendar.Window> resolved =
          FiscalCalendar.resolve(reported.getPhrase(), calendar, now);
      final AnswerTimeWindow base =
          new AnswerTimeWindow()
              .withPhrase(reported.getPhrase())
              .withTimeZone(calendar.timeZone().getId())
              .withFiscalYearStart(calendar.fiscalYearStart().toString().substring(2));
      window =
          Optional.of(
              resolved
                  .map(found -> serverWindow(base, found, reported, warnings))
                  .orElseGet(() -> agentWindow(base, reported, warnings)));
    }
    return window;
  }

  private static AnswerTimeWindow serverWindow(
      final AnswerTimeWindow base,
      final FiscalCalendar.Window found,
      final FinishAnswerTimeWindow reported,
      final List<String> warnings) {
    if (datesDiffer(found, reported)) {
      warnings.add(
          "The agent's dates for \""
              + reported.getPhrase()
              + "\" differ from the organization's calendar. The calendar's dates are shown.");
    }
    return base.withLabel(found.label())
        .withStart(found.start().toString())
        .withEnd(found.end().toString())
        .withResolvedBy(AnswerWindowResolver.SERVER);
  }

  private static boolean datesDiffer(
      final FiscalCalendar.Window found, final FinishAnswerTimeWindow reported) {
    return (!nullOrEmpty(reported.getStart())
            && !found.start().toString().equals(reported.getStart()))
        || (!nullOrEmpty(reported.getEnd()) && !found.end().toString().equals(reported.getEnd()));
  }

  private static AnswerTimeWindow agentWindow(
      final AnswerTimeWindow base,
      final FinishAnswerTimeWindow reported,
      final List<String> warnings) {
    final boolean usable = validDate(reported.getStart()) && validDate(reported.getEnd());
    if (!usable || !nullOrEmpty(reported.getPhrase())) {
      final String subject =
          nullOrEmpty(reported.getPhrase())
              ? "The time window"
              : "\"" + reported.getPhrase() + "\"";
      warnings.add(
          usable
              ? subject + " is not a phrase the calendar knows. The dates are the agent's."
              : subject
                  + " is not a phrase the calendar knows, and the agent gave no valid dates.");
    }
    return base.withStart(usable ? reported.getStart() : null)
        .withEnd(usable ? reported.getEnd() : null)
        .withResolvedBy(AnswerWindowResolver.AGENT);
  }

  private static boolean validDate(final String text) {
    boolean valid = false;
    try {
      valid = text != null && LocalDate.parse(text) != null;
    } catch (DateTimeParseException exception) {
      valid = false;
    }
    return valid;
  }
}
