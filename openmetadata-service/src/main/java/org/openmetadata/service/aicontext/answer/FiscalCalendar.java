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
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a time phrase such as "last quarter" into the dates it covers under the organization's
 * calendar. A fiscal year is named by the calendar year it starts in, so with a February 1 start,
 * Q3 FY2026 is 2026-08-01 to 2026-10-31. Rolling phrases ("last 7 days") end today and include it.
 */
public final class FiscalCalendar {
  /** Where days start and which month the fiscal year opens in. */
  public record Settings(MonthDay fiscalYearStart, ZoneId timeZone, DayOfWeek weekStart) {
    public static Settings defaults() {
      return new Settings(MonthDay.of(Month.JANUARY, 1), ZoneId.of("UTC"), DayOfWeek.MONDAY);
    }

    boolean calendarYear() {
      return fiscalYearStart.equals(MonthDay.of(Month.JANUARY, 1));
    }
  }

  /** The days a phrase covers, both ends included, and the name of the period. */
  public record Window(LocalDate start, LocalDate end, String label) {}

  private static final Pattern ROLLING =
      Pattern.compile("^(?:last|past) (\\d{1,4}) (hour|day|week)s?$");
  private static final Pattern FISCAL_QUARTER =
      Pattern.compile("^(?:fiscal )?q([1-4]) (?:fy ?)?(\\d{4})$");
  private static final Pattern MONTH_YEAR = Pattern.compile("^([a-z]{3,9}) (\\d{4})$");
  private static final Pattern DATE_RANGE =
      Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (?:to|through|\\.\\.|-) ?(\\d{4}-\\d{2}-\\d{2})$");
  private static final Pattern PERIOD =
      Pattern.compile("^(this|last|current|previous) (week|month|quarter|year)$");
  private static final int MONTHS_PER_QUARTER = 3;

  private FiscalCalendar() {}

  /** The dates the phrase covers, or empty when it is outside the grammar. */
  public static Optional<Window> resolve(
      final String phrase, final Settings settings, final Instant now) {
    final String text = phrase == null ? "" : phrase.trim().toLowerCase(Locale.ROOT);
    final LocalDate today = now.atZone(settings.timeZone()).toLocalDate();
    Optional<Window> window = relativeDay(text, today);
    window = window.or(() -> rolling(text, settings, now, today));
    window = window.or(() -> period(text, settings, today));
    window = window.or(() -> toDate(text, settings, today));
    window = window.or(() -> fiscalQuarter(text, settings));
    window = window.or(() -> monthYear(text));
    return window.or(() -> isoDates(text));
  }

  private static Optional<Window> relativeDay(final String text, final LocalDate today) {
    return switch (text) {
      case "today" -> Optional.of(new Window(today, today, today.toString()));
      case "yesterday" -> Optional.of(
          new Window(today.minusDays(1), today.minusDays(1), today.minusDays(1).toString()));
      default -> Optional.empty();
    };
  }

  private static Optional<Window> rolling(
      final String text, final Settings settings, final Instant now, final LocalDate today) {
    final Matcher matcher = ROLLING.matcher(text);
    Optional<Window> window = Optional.empty();
    if (matcher.matches()) {
      final int count = Integer.parseInt(matcher.group(1));
      final String unit = matcher.group(2);
      final LocalDate start =
          switch (unit) {
            case "hour" -> now.minusSeconds(count * 3600L)
                .atZone(settings.timeZone())
                .toLocalDate();
            case "day" -> today.minusDays(count - 1L);
            default -> today.minusWeeks(count).plusDays(1);
          };
      window = Optional.of(new Window(start, today, "last " + count + " " + unit + "s"));
    }
    return window;
  }

  private static Optional<Window> period(
      final String text, final Settings settings, final LocalDate today) {
    final Matcher matcher = PERIOD.matcher(text);
    Optional<Window> window = Optional.empty();
    if (matcher.matches()) {
      final boolean previous =
          matcher.group(1).equals("last") || matcher.group(1).equals("previous");
      window =
          Optional.of(
              switch (matcher.group(2)) {
                case "week" -> week(settings, previous ? today.minusWeeks(1) : today);
                case "month" -> month(previous ? today.minusMonths(1) : today);
                case "quarter" -> currentQuarter(
                    settings,
                    previous ? currentQuarter(settings, today).start().minusDays(1) : today);
                default -> fiscalYear(
                    settings,
                    previous ? fiscalYearOf(settings, today) - 1 : fiscalYearOf(settings, today));
              });
    }
    return window;
  }

  private static Optional<Window> toDate(
      final String text, final Settings settings, final LocalDate today) {
    final Optional<Window> window =
        switch (text) {
          case "mtd", "month to date", "month-to-date" -> Optional.of(
              new Window(today.withDayOfMonth(1), today, "month to date"));
          case "qtd", "quarter to date", "quarter-to-date" -> Optional.of(
              new Window(currentQuarter(settings, today).start(), today, "quarter to date"));
          case "ytd", "year to date", "year-to-date" -> Optional.of(
              new Window(
                  fiscalYearStartDate(settings, fiscalYearOf(settings, today)),
                  today,
                  "year to date"));
          default -> Optional.empty();
        };
    return window;
  }

  private static Optional<Window> fiscalQuarter(final String text, final Settings settings) {
    final Matcher matcher = FISCAL_QUARTER.matcher(text);
    Optional<Window> window = Optional.empty();
    if (matcher.matches()) {
      window =
          Optional.of(
              quarterWindow(
                  settings,
                  Integer.parseInt(matcher.group(2)),
                  Integer.parseInt(matcher.group(1))));
    }
    return window;
  }

  private static Optional<Window> monthYear(final String text) {
    final Matcher matcher = MONTH_YEAR.matcher(text);
    Optional<Window> window = Optional.empty();
    if (matcher.matches()) {
      final Optional<Month> month = monthNamed(matcher.group(1));
      if (month.isPresent()) {
        final LocalDate first = LocalDate.of(Integer.parseInt(matcher.group(2)), month.get(), 1);
        window = Optional.of(month(first));
      }
    }
    return window;
  }

  private static Optional<Window> isoDates(final String text) {
    final Matcher range = DATE_RANGE.matcher(text);
    Optional<Window> window = Optional.empty();
    try {
      if (range.matches()) {
        final LocalDate start = LocalDate.parse(range.group(1));
        final LocalDate end = LocalDate.parse(range.group(2));
        window = end.isBefore(start) ? Optional.empty() : Optional.of(new Window(start, end, text));
      } else if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
        final LocalDate day = LocalDate.parse(text);
        window = Optional.of(new Window(day, day, text));
      }
    } catch (DateTimeParseException exception) {
      window = Optional.empty();
    }
    return window;
  }

  private static Optional<Month> monthNamed(final String name) {
    Optional<Month> match = Optional.empty();
    for (final Month month : Month.values()) {
      final String full =
          month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
      if (full.equals(name) || (name.length() == 3 && full.startsWith(name))) {
        match = Optional.of(month);
      }
    }
    return match;
  }

  private static Window week(final Settings settings, final LocalDate day) {
    final LocalDate start = day.with(TemporalAdjusters.previousOrSame(settings.weekStart()));
    return new Window(start, start.plusDays(6), "week of " + start);
  }

  private static Window month(final LocalDate day) {
    final LocalDate start = day.withDayOfMonth(1);
    final String label =
        start.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + start.getYear();
    return new Window(start, start.plusMonths(1).minusDays(1), label);
  }

  private static Window currentQuarter(final Settings settings, final LocalDate day) {
    final int year = fiscalYearOf(settings, day);
    final LocalDate yearStart = fiscalYearStartDate(settings, year);
    int number = 1;
    while (number < 4 && !day.isBefore(yearStart.plusMonths((long) number * MONTHS_PER_QUARTER))) {
      number++;
    }
    return quarterWindow(settings, year, number);
  }

  private static Window quarterWindow(final Settings settings, final int year, final int number) {
    final LocalDate start =
        fiscalYearStartDate(settings, year).plusMonths((long) (number - 1) * MONTHS_PER_QUARTER);
    final String label =
        settings.calendarYear() ? "Q" + number + " " + year : "fiscal Q" + number + " FY" + year;
    return new Window(start, start.plusMonths(MONTHS_PER_QUARTER).minusDays(1), label);
  }

  private static Window fiscalYear(final Settings settings, final int year) {
    final LocalDate start = fiscalYearStartDate(settings, year);
    return new Window(
        start,
        start.plusYears(1).minusDays(1),
        settings.calendarYear() ? String.valueOf(year) : "fiscal FY" + year);
  }

  private static int fiscalYearOf(final Settings settings, final LocalDate day) {
    return day.isBefore(fiscalYearStartDate(settings, day.getYear()))
        ? day.getYear() - 1
        : day.getYear();
  }

  private static LocalDate fiscalYearStartDate(final Settings settings, final int year) {
    return settings.fiscalYearStart().atYear(year);
  }
}
