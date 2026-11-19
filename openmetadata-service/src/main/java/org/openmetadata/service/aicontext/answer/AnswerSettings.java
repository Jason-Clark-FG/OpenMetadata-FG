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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessAnswer;
import org.openmetadata.schema.entity.context.AgentHarnessAnswerTrust;
import org.openmetadata.schema.entity.context.AgentHarnessCalendar;

/** What the default harness says about how an answer is cited, with built-in values for the rest. */
public record AnswerSettings(
    FiscalCalendar.Settings calendar,
    boolean reportFailingTests,
    boolean reportOpenIncidents,
    Duration staleAfter,
    boolean showSql) {
  static final Duration DEFAULT_STALE_AFTER = Duration.ofHours(24);
  private static final DateTimeFormatter MONTH_DAY =
      DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT);

  public static AnswerSettings defaults() {
    return of(null);
  }

  /** The settings of the company default harness; a missing harness or group takes the defaults. */
  public static AnswerSettings of(final AgentHarness defaultHarness) {
    final AgentHarnessAnswer answer =
        defaultHarness == null ? null : defaultHarness.getAnswerHarness();
    final AgentHarnessCalendar calendar = answer == null ? null : answer.getCalendar();
    final AgentHarnessAnswerTrust trust = answer == null ? null : answer.getTrust();
    return new AnswerSettings(
        calendar == null ? FiscalCalendar.Settings.defaults() : calendarOf(calendar),
        trust == null || trust.getFailingTests() != AgentHarnessAnswerTrust.FailingTests.IGNORE,
        trust == null || trust.getOpenIncidents() != AgentHarnessAnswerTrust.OpenIncidents.IGNORE,
        trust == null ? DEFAULT_STALE_AFTER : staleAfter(trust.getStaleAfter()),
        answer == null
            || answer.getAnswer() == null
            || !Boolean.FALSE.equals(answer.getAnswer().getShowSql()));
  }

  private static FiscalCalendar.Settings calendarOf(final AgentHarnessCalendar calendar) {
    return new FiscalCalendar.Settings(
        MonthDay.parse(calendar.getFiscalYearStart(), MONTH_DAY),
        ZoneId.of(calendar.getTimeZone()),
        DayOfWeek.valueOf(calendar.getWeekStart().value().toUpperCase(Locale.ROOT)));
  }

  /** Parses {@code 24h} or {@code 3d}; the schema's pattern guarantees the shape. */
  public static Duration staleAfter(final String text) {
    final long amount = Long.parseLong(text.substring(0, text.length() - 1));
    return text.endsWith("d") ? Duration.ofDays(amount) : Duration.ofHours(amount);
  }
}
