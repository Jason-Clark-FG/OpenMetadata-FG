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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.aicontext.answer.FiscalCalendar.Settings;
import org.openmetadata.service.aicontext.answer.FiscalCalendar.Window;

class FiscalCalendarTest {
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  private static final Settings CALENDAR_YEAR = Settings.defaults();
  private static final Settings FEB_FISCAL =
      new Settings(MonthDay.of(2, 1), ZoneId.of("America/Chicago"), DayOfWeek.MONDAY);

  private static Window resolve(final String phrase, final Settings settings, final String nowIso) {
    return FiscalCalendar.resolve(phrase, settings, Instant.parse(nowIso))
        .orElseThrow(() -> new AssertionError("Not resolved: " + phrase));
  }

  private static Window resolve(final String phrase, final Settings settings) {
    return resolve(phrase, settings, NOW.toString());
  }

  private static void assertWindow(
      final String start, final String end, final String label, final Window window) {
    assertEquals(LocalDate.parse(start), window.start());
    assertEquals(LocalDate.parse(end), window.end());
    assertEquals(label, window.label());
  }

  @Test
  void dayPhrasesFollowTheCalendarsTimeZone() {
    assertWindow("2026-11-18", "2026-11-18", "2026-11-18", resolve("today", CALENDAR_YEAR));
    assertWindow("2026-11-17", "2026-11-17", "2026-11-17", resolve("Yesterday ", CALENDAR_YEAR));
    // 03:00Z on Feb 1 is still the evening of Jan 31 in Chicago.
    assertWindow(
        "2027-01-31",
        "2027-01-31",
        "2027-01-31",
        resolve("today", FEB_FISCAL, "2027-02-01T03:00:00Z"));
  }

  @Test
  void rollingPhrasesEndTodayAndIncludeIt() {
    assertWindow("2026-11-12", "2026-11-18", "last 7 days", resolve("last 7 days", CALENDAR_YEAR));
    assertWindow(
        "2026-11-17", "2026-11-18", "last 24 hours", resolve("last 24 hours", CALENDAR_YEAR));
    assertWindow(
        "2026-11-05", "2026-11-18", "last 2 weeks", resolve("past 2 weeks", CALENDAR_YEAR));
  }

  @Test
  void rollingHoursAcrossASpringForwardUseTheLocalDateOfThatInstant() {
    final String afterTransition = "2026-03-09T05:30:00Z";
    assertWindow(
        "2026-03-07",
        "2026-03-09",
        "last 24 hours",
        resolve("last 24 hours", FEB_FISCAL, afterTransition));
    assertWindow(
        "2026-03-08",
        "2026-03-08",
        "2026-03-08",
        resolve("yesterday", FEB_FISCAL, afterTransition));
  }

  @Test
  void weeksStartOnTheConfiguredDay() {
    assertWindow(
        "2026-11-16", "2026-11-22", "week of 2026-11-16", resolve("this week", CALENDAR_YEAR));
    assertWindow(
        "2026-11-09", "2026-11-15", "week of 2026-11-09", resolve("last week", CALENDAR_YEAR));
    final Settings sunday = new Settings(MonthDay.of(1, 1), ZoneId.of("UTC"), DayOfWeek.SUNDAY);
    assertWindow("2026-11-15", "2026-11-21", "week of 2026-11-15", resolve("this week", sunday));
  }

  @Test
  void monthsQuartersAndYearsOfACalendarYear() {
    assertWindow("2026-10-01", "2026-10-31", "October 2026", resolve("last month", CALENDAR_YEAR));
    assertWindow("2026-11-01", "2026-11-30", "November 2026", resolve("this month", CALENDAR_YEAR));
    assertWindow("2026-07-01", "2026-09-30", "Q3 2026", resolve("last quarter", CALENDAR_YEAR));
    assertWindow("2026-10-01", "2026-12-31", "Q4 2026", resolve("this quarter", CALENDAR_YEAR));
    assertWindow("2026-01-01", "2026-12-31", "2026", resolve("this year", CALENDAR_YEAR));
    assertWindow("2025-01-01", "2025-12-31", "2025", resolve("last year", CALENDAR_YEAR));
  }

  @Test
  void quartersAndYearsFollowAFebruaryFiscalYear() {
    assertWindow(
        "2026-08-01", "2026-10-31", "fiscal Q3 FY2026", resolve("last quarter", FEB_FISCAL));
    assertWindow(
        "2026-11-01", "2027-01-31", "fiscal Q4 FY2026", resolve("this quarter", FEB_FISCAL));
    assertWindow("2026-02-01", "2027-01-31", "fiscal FY2026", resolve("this year", FEB_FISCAL));
    assertWindow("2025-02-01", "2026-01-31", "fiscal FY2025", resolve("last year", FEB_FISCAL));
  }

  @Test
  void aFiscalYearThatStartedLastCalendarYearStillCoversJanuary() {
    final String january = "2027-01-15T12:00:00Z";
    assertWindow(
        "2026-11-01",
        "2027-01-31",
        "fiscal Q4 FY2026",
        resolve("this quarter", FEB_FISCAL, january));
    assertWindow(
        "2026-08-01",
        "2026-10-31",
        "fiscal Q3 FY2026",
        resolve("last quarter", FEB_FISCAL, january));
    assertWindow(
        "2026-02-01", "2027-01-31", "fiscal FY2026", resolve("this year", FEB_FISCAL, january));
  }

  @Test
  void aQuarterThatEndsAtTheStartOfAFiscalYearRollsIntoTheNext() {
    final String firstDay = "2027-02-01T12:00:00Z";
    assertWindow(
        "2027-02-01",
        "2027-04-30",
        "fiscal Q1 FY2027",
        resolve("this quarter", FEB_FISCAL, firstDay));
    assertWindow(
        "2026-11-01",
        "2027-01-31",
        "fiscal Q4 FY2026",
        resolve("last quarter", FEB_FISCAL, firstDay));
  }

  @Test
  void toDatePhrasesRunFromTheStartOfThePeriodToToday() {
    assertWindow("2026-11-01", "2026-11-18", "month to date", resolve("MTD", FEB_FISCAL));
    assertWindow("2026-11-01", "2026-11-18", "quarter to date", resolve("QTD", FEB_FISCAL));
    assertWindow("2026-02-01", "2026-11-18", "year to date", resolve("YTD", FEB_FISCAL));
    assertWindow(
        "2026-10-01", "2026-11-18", "quarter to date", resolve("quarter to date", CALENDAR_YEAR));
  }

  @Test
  void namedFiscalQuartersUseTheYearTheFiscalYearStartsIn() {
    assertWindow("2026-08-01", "2026-10-31", "fiscal Q3 FY2026", resolve("Q3 FY2026", FEB_FISCAL));
    assertWindow(
        "2026-02-01", "2026-04-30", "fiscal Q1 FY2026", resolve("fiscal q1 2026", FEB_FISCAL));
    assertWindow("2026-04-01", "2026-06-30", "Q2 2026", resolve("Q2 FY2026", CALENDAR_YEAR));
  }

  @Test
  void namedMonthsIncludeTheLastDayOfALeapFebruary() {
    assertWindow(
        "2028-02-01", "2028-02-29", "February 2028", resolve("February 2028", CALENDAR_YEAR));
    assertWindow("2027-02-01", "2027-02-28", "February 2027", resolve("feb 2027", CALENDAR_YEAR));
    assertWindow(
        "2028-02-01",
        "2028-02-29",
        "February 2028",
        resolve("last month", CALENDAR_YEAR, "2028-03-10T00:00:00Z"));
    assertWindow("2026-10-01", "2026-10-31", "October 2026", resolve("Oct 2026", CALENDAR_YEAR));
  }

  @Test
  void literalDatesAreTakenAsGiven() {
    assertWindow("2026-10-15", "2026-10-15", "2026-10-15", resolve("2026-10-15", CALENDAR_YEAR));
    assertWindow(
        "2026-10-01",
        "2026-10-31",
        "2026-10-01 to 2026-10-31",
        resolve("2026-10-01 to 2026-10-31", CALENDAR_YEAR));
  }

  @Test
  void aPhraseOutsideTheGrammarOrAnImpossibleDateResolvesToNothing() {
    for (final String phrase :
        new String[] {"around then", "", "2026-13-01", "2026-10-31 to 2026-10-01", "Smarch 2026"}) {
      final Optional<Window> window = FiscalCalendar.resolve(phrase, CALENDAR_YEAR, NOW);
      assertTrue(window.isEmpty(), phrase);
    }
    assertTrue(FiscalCalendar.resolve(null, CALENDAR_YEAR, NOW).isEmpty());
  }
}
