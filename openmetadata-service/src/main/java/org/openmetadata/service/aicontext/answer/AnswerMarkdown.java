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

import java.time.Duration;
import java.time.Instant;
import java.time.MonthDay;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openmetadata.schema.api.context.AnswerCitation;
import org.openmetadata.schema.api.context.AnswerConcept;
import org.openmetadata.schema.api.context.AnswerConceptType;
import org.openmetadata.schema.api.context.AnswerExecution;
import org.openmetadata.schema.api.context.AnswerExecutor;
import org.openmetadata.schema.api.context.AnswerFreshness;
import org.openmetadata.schema.api.context.AnswerFreshnessSource;
import org.openmetadata.schema.api.context.AnswerResult;
import org.openmetadata.schema.api.context.AnswerResultBreakdown;
import org.openmetadata.schema.api.context.AnswerTable;
import org.openmetadata.schema.api.context.AnswerTimeWindow;
import org.openmetadata.schema.api.context.AnswerUnresolved;
import org.openmetadata.schema.api.context.AnswerWindowResolver;

/**
 * Renders a citation as the "How I got this" block. Names that come from the catalog are escaped,
 * because a description or display name is text anyone with edit rights wrote.
 */
public final class AnswerMarkdown {
  private static final String WARN = "⚠ ";
  private static final String ARROW = " → ";
  private static final String DEFAULT_NOT_RUN = "the query was not run in this chat";
  private static final Pattern TIER = Pattern.compile("^Tier(\\d+)$");
  private static final Pattern BACKTICKS = Pattern.compile("`+");
  private static final String SPECIAL = "\\`*_[]<>|#";
  private static final long MINUTES_PER_HOUR = 60;
  private static final long HOURS_PER_DAY = 24;
  private static final long HOURS_SHOWN_UP_TO = 48;

  private AnswerMarkdown() {}

  public static String render(final AnswerCitation citation, final Instant now) {
    final List<String> steps = new ArrayList<>();
    listOrEmpty(citation.getConcepts()).forEach(concept -> steps.add(conceptLine(concept)));
    if (citation.getTimeWindow() != null) {
      steps.add(windowLine(citation.getTimeWindow()));
    }
    steps.addAll(tableLines(citation, now));
    steps.add(executionLine(citation));
    listOrEmpty(citation.getWarnings()).forEach(warning -> steps.add(WARN + warning));
    final StringBuilder block = new StringBuilder(citation.getHeadline()).append("\n\n");
    if (citation.getResult() != null) {
      block.append(resultBlock(citation.getResult())).append('\n');
    }
    block.append("**How I got this**\n\n");
    for (int index = 0; index < steps.size(); index++) {
      block.append(index + 1).append(". ").append(steps.get(index)).append('\n');
    }
    if (showsSql(citation)) {
      block.append('\n').append(sqlBlock(citation.getSql()));
    }
    return block.toString();
  }

  private static String resultBlock(final AnswerResult result) {
    final StringBuilder lines = new StringBuilder();
    lines.append("**").append(escape(result.getValue())).append("**");
    if (!nullOrEmpty(result.getChange())) {
      lines.append(' ').append(escape(result.getChange()));
    }
    if (!nullOrEmpty(result.getLabel())) {
      lines.append(" · ").append(escape(result.getLabel()));
    }
    lines.append(" _(reported by the agent, not computed by OpenMetadata)_\n");
    if (!nullOrEmpty(result.getNote())) {
      lines.append('\n').append(escape(result.getNote())).append('\n');
    }
    final List<AnswerResultBreakdown> rows = listOrEmpty(result.getBreakdown());
    if (!rows.isEmpty()) {
      lines.append('\n');
    }
    for (final AnswerResultBreakdown row : rows) {
      lines.append("- ").append(escape(row.getLabel())).append(": ").append(escape(row.getValue()));
      if (!nullOrEmpty(row.getChange())) {
        lines.append(' ').append(escape(row.getChange()));
      }
      lines.append('\n');
    }
    return lines.toString();
  }

  private static boolean showsSql(final AnswerCitation citation) {
    final boolean notRun = citation.getExecution().getExecutor() == AnswerExecutor.NONE;
    return !nullOrEmpty(citation.getSql())
        && (notRun || !Boolean.FALSE.equals(citation.getShowSql()));
  }

  private static String conceptLine(final AnswerConcept concept) {
    final String quoted = "\"" + escape(concept.getPhrase()) + "\"" + ARROW;
    final String kind = kindOf(concept.getEntityType());
    return switch (concept.getUnresolved()) {
      case null -> quoted
          + kind
          + " "
          + link(escape(nameOf(concept)), concept.getUrl())
          + " ("
          + conceptFacts(concept)
          + ")";
      case NOT_VISIBLE -> quoted + "a " + kind + " that is not visible to you";
      case NOT_FOUND -> quoted + code(concept.getFqn()) + " not found in OpenMetadata";
    };
  }

  private static String kindOf(final AnswerConceptType type) {
    return type == AnswerConceptType.METRIC ? "metric" : type == null ? "concept" : "glossary term";
  }

  private static String nameOf(final AnswerConcept concept) {
    return nullOrEmpty(concept.getDisplayName()) ? concept.getFqn() : concept.getDisplayName();
  }

  private static String conceptFacts(final AnswerConcept concept) {
    final List<String> facts = new ArrayList<>();
    if (!nullOrEmpty(concept.getStatus())) {
      facts.add(concept.getStatus().toLowerCase(Locale.ROOT));
    }
    if (Boolean.TRUE.equals(concept.getCertified())) {
      facts.add("certified");
    }
    facts.add(owners(concept.getOwners()));
    if (concept.getVersion() != null) {
      facts.add("v" + version(concept.getVersion()));
    }
    return String.join(", ", facts);
  }

  private static String version(final double version) {
    return version == Math.rint(version) ? String.valueOf((long) version) : String.valueOf(version);
  }

  private static String owners(final List<String> owners) {
    final List<String> names = listOrEmpty(owners);
    final String label = names.size() == 1 ? "owner " : "owners ";
    return names.isEmpty()
        ? "no owner"
        : label + names.stream().map(AnswerMarkdown::escape).collect(Collectors.joining(", "));
  }

  private static String windowLine(final AnswerTimeWindow window) {
    final String subject =
        nullOrEmpty(window.getPhrase()) ? "" : "\"" + escape(window.getPhrase()) + "\"" + ARROW;
    final String dates =
        nullOrEmpty(window.getStart()) || nullOrEmpty(window.getEnd())
            ? "no usable dates"
            : window.getStart().equals(window.getEnd())
                ? window.getStart()
                : window.getStart() + " to " + window.getEnd();
    final String line;
    if (window.getResolvedBy() == AnswerWindowResolver.SERVER) {
      line =
          subject
              + (nullOrEmpty(window.getLabel()) ? "" : escape(window.getLabel()) + ", ")
              + dates
              + " ("
              + calendarNote(window)
              + ")";
    } else {
      line = subject + dates + " (dates from the agent, not checked against the calendar)";
    }
    return line;
  }

  private static String calendarNote(final AnswerTimeWindow window) {
    final MonthDay start = MonthDay.parse("--" + window.getFiscalYearStart());
    final String fiscal =
        start.equals(MonthDay.of(1, 1))
            ? ""
            : ", fiscal year starts "
                + start.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                + " "
                + start.getDayOfMonth();
    return window.getTimeZone() + fiscal;
  }

  private static List<String> tableLines(final AnswerCitation citation, final Instant now) {
    final List<String> lines = new ArrayList<>();
    final List<String> concerns = new ArrayList<>();
    int resolved = 0;
    for (final AnswerTable table : listOrEmpty(citation.getTables())) {
      lines.add("Data" + ARROW + tableLine(table, now));
      if (table.getUnresolved() == null) {
        resolved++;
        concerns.addAll(concerns(table));
      }
    }
    lines.addAll(concerns);
    if (resolved > 0 && concerns.isEmpty()) {
      allClear(citation).ifPresent(text -> lines.add("Trust" + ARROW + text));
    }
    return lines;
  }

  private static Optional<String> allClear(final AnswerCitation citation) {
    final boolean tests =
        citation.getTrustChecked() == null
            || Boolean.TRUE.equals(citation.getTrustChecked().getFailingTests());
    final boolean incidents =
        citation.getTrustChecked() == null
            || Boolean.TRUE.equals(citation.getTrustChecked().getOpenIncidents());
    final String text =
        tests && incidents
            ? "no failing tests, no open incidents"
            : tests ? "no failing tests" : incidents ? "no open incidents" : null;
    return Optional.ofNullable(text);
  }

  private static String tableLine(final AnswerTable table, final Instant now) {
    final String line;
    if (table.getUnresolved() == AnswerUnresolved.NOT_VISIBLE) {
      line = "a table that is not visible to you";
    } else if (table.getUnresolved() == AnswerUnresolved.NOT_FOUND) {
      line = code(table.getFqn()) + " not found in OpenMetadata";
    } else {
      line =
          link(code(table.getFqn()), table.getUrl())
              + " ("
              + tableFacts(table)
              + ")"
              + freshness(table.getFreshness(), now);
    }
    return line;
  }

  private static String tableFacts(final AnswerTable table) {
    final List<String> facts = new ArrayList<>();
    if (!nullOrEmpty(table.getTier())) {
      facts.add(TIER.matcher(table.getTier()).replaceFirst("Tier $1"));
    }
    facts.add(Boolean.TRUE.equals(table.getCertified()) ? "certified" : "not certified");
    if (Boolean.TRUE.equals(table.getDeprecated())) {
      facts.add("deprecated");
    }
    if (!listOrEmpty(table.getOwners()).isEmpty()) {
      facts.add(owners(table.getOwners()));
    }
    return String.join(", ", facts);
  }

  private static String freshness(final AnswerFreshness freshness, final Instant now) {
    final String text =
        freshness == null || freshness.getAt() == null
            ? "freshness unknown"
            : (freshness.getSource() == AnswerFreshnessSource.TEST
                    ? "freshness tested "
                    : "profiled ")
                + age(Instant.ofEpochMilli(freshness.getAt()), now);
    final boolean stale = freshness != null && Boolean.TRUE.equals(freshness.getStale());
    return ", " + (stale ? WARN + text + ", stale" : text);
  }

  private static String age(final Instant at, final Instant now) {
    final Duration elapsed = Duration.between(at, now);
    final long hours = elapsed.toHours();
    final String text;
    if (elapsed.toMinutes() < MINUTES_PER_HOUR) {
      text = Math.max(elapsed.toMinutes(), 0) + " min ago";
    } else if (hours < HOURS_SHOWN_UP_TO) {
      text = hours + " h ago";
    } else {
      text = hours / HOURS_PER_DAY + " d ago";
    }
    return text;
  }

  private static List<String> concerns(final AnswerTable table) {
    final List<String> lines = new ArrayList<>();
    final String name = code(table.getFqn());
    if (Boolean.TRUE.equals(table.getDeprecated())) {
      lines.add(
          WARN
              + name
              + " is deprecated"
              + (nullOrEmpty(table.getReplacedBy())
                  ? "."
                  : ", replaced by " + code(table.getReplacedBy()) + "."));
    }
    listOrEmpty(table.getFailingTests())
        .forEach(
            test ->
                lines.add(
                    WARN
                        + "Failing test on "
                        + name
                        + ": "
                        + link(escape(test.getName()), test.getUrl())));
    listOrEmpty(table.getOpenIncidents())
        .forEach(
            incident ->
                lines.add(
                    WARN
                        + "Open incident "
                        + code(incident.getId())
                        + " on "
                        + name
                        + incidentDetail(incident.getStatus(), incident.getSeverity())
                        + (nullOrEmpty(incident.getTestCase())
                            ? ""
                            : ": " + link(escape(incident.getTestCase()), incident.getUrl()))));
    return lines;
  }

  private static String incidentDetail(final String status, final String severity) {
    final List<String> parts = new ArrayList<>();
    if (!nullOrEmpty(status)) {
      parts.add(status);
    }
    if (!nullOrEmpty(severity)) {
      parts.add(severity);
    }
    return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
  }

  private static String executionLine(final AnswerCitation citation) {
    final AnswerExecution execution = citation.getExecution();
    final String line;
    if (execution.getExecutor() == AnswerExecutor.NONE) {
      final String reason =
          nullOrEmpty(execution.getReason()) ? DEFAULT_NOT_RUN : execution.getReason();
      line =
          "Not run: "
              + escape(reason.replaceAll("\\.+$", ""))
              + "."
              + (showsSql(citation) ? " SQL below." : "");
    } else {
      line =
          "Ran through your "
              + escape(
                  nullOrEmpty(execution.getConnector()) ? "warehouse" : execution.getConnector())
              + " connector"
              + rows(execution.getRowCount())
              + ". Self-reported: OpenMetadata did not run it.";
    }
    return line;
  }

  private static String rows(final Integer count) {
    return count == null ? "" : ", " + count + (count == 1 ? " row" : " rows");
  }

  private static String sqlBlock(final String sql) {
    final Matcher runs = BACKTICKS.matcher(sql);
    int longest = 2;
    while (runs.find()) {
      longest = Math.max(longest, runs.group().length());
    }
    final String fence = "`".repeat(longest + 1);
    return fence + "sql\n" + sql.strip() + "\n" + fence + "\n";
  }

  private static String link(final String text, final String url) {
    return nullOrEmpty(url) ? text : "[" + text + "](" + url + ")";
  }

  private static String code(final String text) {
    final String safe = nullOrEmpty(text) ? "" : text.replace("`", "'");
    return "`" + safe + "`";
  }

  private static String escape(final String text) {
    final StringBuilder escaped = new StringBuilder();
    for (final char character :
        (text == null ? "" : text.replaceAll("[\\r\\n]+", " ")).toCharArray()) {
      if (SPECIAL.indexOf(character) >= 0) {
        escaped.append('\\');
      }
      escaped.append(character);
    }
    return escaped.toString();
  }
}
