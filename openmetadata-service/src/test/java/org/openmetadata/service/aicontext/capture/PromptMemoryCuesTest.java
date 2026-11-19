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
package org.openmetadata.service.aicontext.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PromptMemoryCuesTest {
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Remember: monthly ARR is the arr_daily snapshot on the last day of the month|Monthly ARR is the arr_daily snapshot on the last day of the month",
        "remember that revenue is booked in cents.|Revenue is booked in cents.",
        "Please note that orders_v2 replaced orders last year|orders_v2 replaced orders last year",
        "Note - fiscal year starts in February|Fiscal year starts in February",
        "From now on, report revenue net of refunds|Report revenue net of refunds",
        "Going forward use the certified customers table|Use the certified customers table",
        "For future reference: churn excludes trials|Churn excludes trials",
        "Always use fct_orders for revenue questions|Always use fct_orders for revenue questions",
        "please never use raw_events for funnel counts|Never use raw_events for funnel counts",
        "Use dim_customer instead of customers_old|Use dim_customer instead of customers_old",
        "customers_old is deprecated, use dim_customer|customers_old is deprecated, use dim_customer",
        "Okay, remember that the EU region closes a day late|The EU region closes a day late",
        "Fix the join. Remember: ARR excludes one-off fees|ARR excludes one-off fees",
        "Remember: 月次ARRは arr_daily の月末スナップショット|月次ARRは arr_daily の月末スナップショット"
      })
  void anExplicitCueYieldsTheFactWithoutTheCue(final String prompt, final String fact) {
    assertThat(PromptMemoryCues.factIn(prompt)).contains(fact);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "What is monthly ARR?",
        "Remember that ARR is monthly, right?",
        "Can you remember that ARR is the arr_daily snapshot?",
        "Remember to run the tests before you push",
        "I always use fct_orders for revenue",
        "Remember: ok",
        "Remember that   short",
        "Show me revenue by month",
        "請記住月度收入以美分計算",
        "Recuerda que los ingresos se registran en centavos",
        ""
      })
  void anythingElseYieldsNothing(final String prompt) {
    assertThat(PromptMemoryCues.factIn(prompt)).isEmpty();
  }

  @Test
  void aNullPromptYieldsNothing() {
    assertThat(PromptMemoryCues.factIn(null)).isEmpty();
  }

  @Test
  void aCueInsideACodeBlockIsIgnoredButProseAroundItIsRead() {
    final String prompt =
        "Here is the script\n```sql\n-- Remember: this query is slow because of the join\n```\n"
            + "Remember that the script runs nightly at 02:00";

    assertThat(PromptMemoryCues.factIn(prompt)).contains("The script runs nightly at 02:00");
    assertThat(
            PromptMemoryCues.factIn(
                "```\nRemember that this block only holds a comment about joins\n```"))
        .isEmpty();
  }

  @Test
  void aPromptOverTheLimitYieldsNothing() {
    final String filler = "x ".repeat(PromptMemoryCues.MAX_PROMPT_CHARS / 2);

    assertThat(PromptMemoryCues.factIn("Remember that ARR is monthly. " + filler)).isEmpty();
    assertThat(PromptMemoryCues.factIn("Remember that ARR is monthly.")).isPresent();
  }

  @Test
  void aFactOverTheLimitYieldsNothing() {
    final String fact = "word ".repeat(PromptMemoryCues.MAX_FACT_CHARS / 5 + 1);

    assertThat(PromptMemoryCues.factIn("Remember that " + fact)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Remember that the password is hunter2 for the warehouse",
        "Remember that the password for arr_daily_8b038fcc_table is hunter2-hunter2",
        "Remember that the warehouse login token is abc123 and rotates",
        "Remember: api_key = abc123 for the billing system",
        "Remember that the token: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.sig is used by jobs",
        "Remember that the key sk-abcdefghijklmnopqrstuvwx belongs to billing",
        "Remember that ghp_abcdefghijklmnopqrstuvwxyz0123456789 is the ci login",
        "Remember that analyst jane.doe@example.com owns the revenue dashboard",
        "Remember that the dsn is postgres://admin:s3cret@db.internal/prod",
        "Remember that the card 4111 1111 1111 1111 is the test card",
        "Remember that the AWS key AKIAABCDEFGHIJKLMNOP is rotated monthly",
        "Remember that the value Zm9vYmFyMTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ubw is the salt"
      })
  void aFactThatLooksLikeASecretIsNeverCaptured(final String prompt) {
    assertThat(PromptMemoryCues.factIn(prompt)).isEmpty();
  }

  @Test
  void aLongSnakeCaseNameIsNotTakenForASecret() {
    final Optional<String> fact =
        PromptMemoryCues.factIn(
            "Remember that fct_monthly_recurring_revenue_by_customer_segment_daily is certified");

    assertThat(fact).isPresent();
  }

  @Test
  void aNameThatMerelyContainsAKeywordIsNotTakenForASecret() {
    assertThat(PromptMemoryCues.factIn("Remember that secret_santa is a certified table"))
        .isPresent();
  }

  @Test
  void onlyTheFirstFactOfAPromptIsKept() {
    assertThat(
            PromptMemoryCues.factIn(
                "Remember that ARR is monthly. Also remember that churn excludes trials."))
        .contains("ARR is monthly.");
  }

  @Test
  void assetNamesAreBackticksUnderscoresAndDotsOnly() {
    assertThat(
            PromptMemoryCues.assetNames(
                "Monthly ARR is the `arr_daily` snapshot, see sales.orders and revenue in orders"))
        .containsExactly("arr_daily", "sales.orders");
  }
}
