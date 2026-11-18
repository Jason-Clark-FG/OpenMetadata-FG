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

package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.rdf.explore.SearchHit;
import org.openmetadata.service.search.vector.HybridSearchException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

class CatalogStartingPointsTest {

  private static final Authorizer AUTHORIZER = mock(Authorizer.class);
  private static final CatalogSecurityContext CALLER = RdfToolAuthorization.caller("analyst");
  private static final UUID ORDERS = UUID.randomUUID();

  private final RecordingSearch semantic =
      new RecordingSearch(
          Map.of(
              "results",
              List.of(
                  Map.of(
                      "parentId", ORDERS.toString(),
                      "entityType", "table",
                      "fullyQualifiedName", "warehouse.shop.public.orders"))));
  private final RecordingSearch keyword =
      new RecordingSearch(
          Map.of(
              "results",
              List.of(
                  Map.of(
                      "id", ORDERS.toString(),
                      "entityType", "table",
                      "fullyQualifiedName", "warehouse.shop.public.orders"),
                  Map.of(
                      "entityType", "tableColumn",
                      "fullyQualifiedName", "warehouse.shop.public.orders.id"),
                  Map.of("entityType", "dashboard", "fullyQualifiedName", "bi.revenue"))));

  private CatalogStartingPoints startingPoints(final boolean vectorSearchEnabled) {
    return new CatalogStartingPoints(
        () -> vectorSearchEnabled, () -> semantic, () -> keyword, NO_HYBRID, EVERY_HIT_VISIBLE);
  }

  @Test
  void withoutHybridSearchMeaningAndWordHitsAreMergedMeaningFirstWithoutDuplicates()
      throws Exception {
    final UUID lifetimeSpend = UUID.randomUUID();
    final RecordingSearch meaning =
        new RecordingSearch(
            Map.of(
                "results",
                List.of(
                    semanticHit(ORDERS, "table", "warehouse.shop.public.orders"),
                    semanticHit(lifetimeSpend, "glossaryTerm", "g.Lifetime Spend"))));

    final CatalogHits found =
        new CatalogStartingPoints(
                () -> true, () -> meaning, () -> keyword, NO_HYBRID, EVERY_HIT_VISIBLE)
            .search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("orders", List.of("table"), 3));

    assertThat(found.hits())
        .containsExactly(
            new SearchHit("table", "warehouse.shop.public.orders", ORDERS),
            new SearchHit("glossaryTerm", "g.Lifetime Spend", lifetimeSpend),
            new SearchHit("tableColumn", "warehouse.shop.public.orders.id", null));
    assertThat(found.semanticSearchSkipped()).isNull();
    assertThat(meaning.calls.getFirst())
        .containsEntry("query", "orders")
        .containsEntry("size", 3)
        .containsEntry("filters", Map.of("entityType", List.of("table")));
    assertThat(keyword.calls).isNotEmpty();
  }

  @Test
  void withHybridSearchOneQueryAnswersWithTablesColumnsAndTermsAndNothingElseRuns()
      throws Exception {
    final UUID customers = UUID.randomUUID();
    final UUID spend = UUID.randomUUID();
    final UUID term = UUID.randomUUID();
    final RecordingHybrid hybrid =
        new RecordingHybrid(
            List.of(
                hybridHit(customers, "table", "shop.customers"),
                columnHit(spend, "shop.orders.lifetime_spend", customers, "shop.orders"),
                hybridHit(term, "glossaryTerm", "g.Lifetime Spend")));

    final CatalogHits found =
        new CatalogStartingPoints(
                () -> true, () -> semantic, () -> keyword, hybrid, EVERY_HIT_VISIBLE)
            .search(
                AUTHORIZER,
                CALLER,
                new KnowledgeGraphCall.Find(
                    "How much do our clients spend with us over time?", List.of(), 5));

    assertThat(found.hits())
        .containsExactly(
            new SearchHit("table", "shop.customers", customers),
            new SearchHit("tableColumn", "shop.orders.lifetime_spend", spend),
            new SearchHit("glossaryTerm", "g.Lifetime Spend", term));
    assertThat(found.semanticSearchSkipped()).isNull();
    assertThat(hybrid.questions)
        .containsExactly("How much do our clients spend with us over time?");
    assertThat(hybrid.words).containsExactly(List.of("client", "spend", "over", "time"));
    assertThat(hybrid.entityTypes).containsExactly(List.of());
    assertThat(hybrid.sizes).containsExactly(15);
    assertThat(hybrid.callers).containsExactly(CALLER);
    assertThat(semantic.calls).isEmpty();
    assertThat(keyword.calls).isEmpty();
  }

  /**
   * A find answer is where to start, and each kind of node is a different way in: a term says what
   * a word means, a table holds the data, a column answers "which column". Each kind's best hit
   * comes before any kind's second, in rank order.
   */
  @Test
  void hybridHitsComeBackEachKindsBestFirstThenEachKindsSecond() throws Exception {
    final UUID orders = UUID.randomUUID();
    final RecordingHybrid hybrid =
        new RecordingHybrid(
            List.of(
                columnHit(UUID.randomUUID(), "shop.orders.order_total", orders, "shop.orders"),
                columnHit(UUID.randomUUID(), "shop.orders.subtotal", orders, "shop.orders"),
                columnHit(UUID.randomUUID(), "shop.orders.tax_paid", orders, "shop.orders"),
                hybridHit(orders, "table", "shop.orders"),
                hybridHit(UUID.randomUUID(), "glossaryTerm", "g.Revenue"),
                hybridHit(UUID.randomUUID(), "table", "shop.stg_orders")));

    final CatalogHits found =
        new CatalogStartingPoints(
                () -> true, () -> semantic, () -> keyword, hybrid, EVERY_HIT_VISIBLE)
            .search(
                AUTHORIZER,
                CALLER,
                new KnowledgeGraphCall.Find("total revenue per month", List.of(), 5));

    assertThat(found.hits())
        .extracting(SearchHit::fullyQualifiedName)
        .containsExactly(
            "shop.orders.order_total",
            "shop.orders",
            "g.Revenue",
            "shop.orders.subtotal",
            "shop.stg_orders",
            "shop.orders.tax_paid");
  }

  private static Map<String, Object> deprecated(final Map<String, Object> hit) {
    final Map<String, Object> marked = new HashMap<>(hit);
    marked.put("entityStatus", "Deprecated");
    return marked;
  }

  @Test
  void aHybridHitTheCallerCannotSeeIsLeftOutAndAColumnIsJudgedByItsTable() throws Exception {
    final UUID customers = UUID.randomUUID();
    final UUID hidden = UUID.randomUUID();
    final List<Map<String, Object>> judged = new ArrayList<>();
    final RecordingHybrid hybrid =
        new RecordingHybrid(
            List.of(
                columnHit(UUID.randomUUID(), "shop.customers.email", customers, "shop.customers"),
                hybridHit(hidden, "table", "shop.secret")));

    final CatalogHits found =
        new CatalogStartingPoints(
                () -> true,
                () -> semantic,
                () -> keyword,
                hybrid,
                (authorizer, caller, hit) -> {
                  judged.add(hit);
                  return !hidden.toString().equals(hit.get("parentId"));
                })
            .search(
                AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("customer email", List.of(), 5));

    assertThat(found.hits())
        .extracting(SearchHit::fullyQualifiedName)
        .containsExactly("shop.customers.email");
    assertThat(CatalogStartingPoints.visibleAs(judged.getFirst()))
        .containsEntry("entityType", "table")
        .containsEntry("parentId", customers.toString())
        .containsEntry("fullyQualifiedName", "shop.customers");
  }

  @Test
  void withSemanticSearchOffHybridSearchIsNeverAsked() throws Exception {
    final RecordingHybrid hybrid = new RecordingHybrid(List.of());

    new CatalogStartingPoints(() -> false, () -> semantic, () -> keyword, hybrid, EVERY_HIT_VISIBLE)
        .search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("orders", List.of(), 5));

    assertThat(hybrid.questions).isEmpty();
    assertThat(keyword.calls).isNotEmpty();
  }

  @Test
  void hybridSearchIsAskedForTheTypesTheCallerNamedAndAOneWordQuestionHasNoSeparateWords()
      throws Exception {
    final RecordingHybrid hybrid = new RecordingHybrid(List.of());

    new CatalogStartingPoints(() -> true, () -> semantic, () -> keyword, hybrid, EVERY_HIT_VISIBLE)
        .search(
            AUTHORIZER,
            CALLER,
            new KnowledgeGraphCall.Find("orders", List.of("table", "aiGovernancePolicy"), 5));

    assertThat(hybrid.entityTypes).containsExactly(List.of("table", "aiGovernancePolicy"));
    assertThat(hybrid.words).containsExactly(List.of());
  }

  @Test
  void aHybridSearchThatFailsLeavesTheWordSearchToAnswerAndSaysSemanticSearchWasSkipped()
      throws Exception {
    final CatalogStartingPoints points =
        new CatalogStartingPoints(
            () -> true,
            () -> semantic,
            () -> keyword,
            (question, words, entityTypes, size, caller) -> {
              throw new HybridSearchException("cluster rejected the hybrid query", null);
            },
            EVERY_HIT_VISIBLE);

    final CatalogHits found =
        points.search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("orders", List.of(), 5));

    assertThat(found.hits())
        .contains(new SearchHit("tableColumn", "warehouse.shop.public.orders.id", null));
    assertThat(found.semanticSearchSkipped()).contains("cluster rejected the hybrid query");
  }

  private static Map<String, Object> semanticHit(
      final UUID id, final String entityType, final String fqn) {
    return Map.of("parentId", id.toString(), "entityType", entityType, "fullyQualifiedName", fqn);
  }

  private static Map<String, Object> hybridHit(
      final UUID id, final String entityType, final String fqn) {
    return semanticHit(id, entityType, fqn);
  }

  private static Map<String, Object> columnHit(
      final UUID id, final String fqn, final UUID table, final String tableFqn) {
    return Map.of(
        "parentId",
        id.toString(),
        "entityType",
        "tableColumn",
        "fullyQualifiedName",
        fqn,
        "table",
        Map.of("id", table.toString(), "type", "table", "fullyQualifiedName", tableFqn));
  }

  private static final CatalogStartingPoints.HybridCatalogSearch NO_HYBRID =
      (question, words, entityTypes, size, caller) -> Optional.empty();
  private static final SemanticSearchTool.HitVisibility EVERY_HIT_VISIBLE =
      (authorizer, caller, hit) -> true;

  /** Answers every hybrid search with the same ranked rows, and remembers what it was asked. */
  private static final class RecordingHybrid implements CatalogStartingPoints.HybridCatalogSearch {
    private final List<Map<String, Object>> rows;
    private final List<String> questions = new ArrayList<>();
    private final List<List<String>> words = new ArrayList<>();
    private final List<List<String>> entityTypes = new ArrayList<>();
    private final List<Integer> sizes = new ArrayList<>();
    private final List<CatalogSecurityContext> callers = new ArrayList<>();

    private RecordingHybrid(final List<Map<String, Object>> rows) {
      this.rows = rows;
    }

    @Override
    public Optional<List<Map<String, Object>>> search(
        final String question,
        final List<String> questionWords,
        final List<String> types,
        final int size,
        final CatalogSecurityContext caller) {
      questions.add(question);
      words.add(questionWords);
      entityTypes.add(types);
      sizes.add(size);
      callers.add(caller);
      return Optional.of(rows);
    }
  }

  private static Map<String, Object> keywordRow(
      final UUID id, final String entityType, final String fqn, final String status) {
    final Map<String, Object> row = new HashMap<>();
    row.put("id", id.toString());
    row.put("entityType", entityType);
    row.put("fullyQualifiedName", fqn);
    if (status != null) {
      row.put("entityStatus", status);
    }
    return row;
  }

  /**
   * Trust is stated on the card, not in the rank: a Deprecated table the question matches best is
   * returned first of its kind, on the hybrid path and with vector search off alike.
   */
  @Test
  void aDeprecatedTableThatMatchesBestIsReturnedFirstOfItsKind() throws Exception {
    final UUID clean = UUID.randomUUID();
    final UUID customers = UUID.randomUUID();
    final RecordingHybrid hybrid =
        new RecordingHybrid(
            List.of(
                deprecated(hybridHit(clean, "table", "shop.customers_clean")),
                hybridHit(customers, "table", "shop.customers")));
    final RecordingSearch words =
        new RecordingSearch(
            Map.of(
                "results",
                List.of(
                    keywordRow(clean, "table", "shop.customers_clean", "Deprecated"),
                    keywordRow(customers, "table", "shop.customers", "Unprocessed"))));
    final KnowledgeGraphCall.Find find =
        new KnowledgeGraphCall.Find("customers_clean", List.of("table"), 5);

    final List<SearchHit> byHybrid =
        new CatalogStartingPoints(
                () -> true, () -> semantic, () -> keyword, hybrid, EVERY_HIT_VISIBLE)
            .search(AUTHORIZER, CALLER, find)
            .hits();
    final List<SearchHit> byWords =
        new CatalogStartingPoints(
                () -> false, () -> semantic, () -> words, NO_HYBRID, EVERY_HIT_VISIBLE)
            .search(AUTHORIZER, CALLER, find)
            .hits();

    assertThat(byHybrid)
        .extracting(SearchHit::fullyQualifiedName)
        .containsExactly("shop.customers_clean", "shop.customers");
    assertThat(byWords)
        .extracting(SearchHit::fullyQualifiedName)
        .containsExactly("shop.customers_clean", "shop.customers");
  }

  @Test
  void keywordSearchFindsTheStartingPointsOtherwiseAndAsksForIds() throws Exception {
    final List<SearchHit> hits =
        startingPoints(false)
            .search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("orders", List.of(), 5))
            .hits();

    assertThat(hits)
        .containsExactly(
            new SearchHit("table", "warehouse.shop.public.orders", ORDERS),
            new SearchHit("tableColumn", "warehouse.shop.public.orders.id", null));
    assertThat(semantic.calls).isEmpty();
    assertThat(keyword.calls.getFirst())
        .containsEntry("query", "orders")
        .containsEntry("size", 5)
        .containsEntry("fields", "id")
        .doesNotContainKey("entityType");
  }

  @Test
  void aHitWithoutTheIdentityItsNodeIsMintedFromIsLeftOut() throws Exception {
    final List<SearchHit> hits =
        startingPoints(false)
            .search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("orders", List.of(), 5))
            .hits();

    assertThat(hits).extracting(SearchHit::entityType).doesNotContain("dashboard");
  }

  @Test
  void keywordSearchNarrowsToSeveralEntityTypesWithAFilter() throws Exception {
    startingPoints(false)
        .search(
            AUTHORIZER,
            CALLER,
            new KnowledgeGraphCall.Find("orders", List.of("table", "dashboard"), 5))
        .hits();

    assertThat(keyword.calls.getFirst().get("queryFilter").toString())
        .contains("\"terms\"", "\"entityType\"", "\"table\"", "\"dashboard\"");
  }

  @Test
  void ontologyTermTypesAreLeftOutOfTheCatalogSearch() throws Exception {
    startingPoints(false)
        .search(
            AUTHORIZER,
            CALLER,
            new KnowledgeGraphCall.Find("orders", List.of("table", "ontologyConcept"), 5));

    assertThat(keyword.calls.getFirst())
        .containsEntry("entityType", "table")
        .doesNotContainKey("queryFilter");
  }

  @Test
  void aSentenceIsSearchedWholeAndWordByWordAndHitsOfMoreWordsComeFirst() throws Exception {
    final UUID customers = UUID.randomUUID();
    final UUID orders = UUID.randomUUID();
    final UUID revenue = UUID.randomUUID();
    final String sentence = "which tables hold customer data, and can I trust the orders table?";
    final QuerySearch search =
        new QuerySearch(
            Map.of(
                "customer",
                List.of(table(customers, "shop.customers"), column("shop.orders.customer_id")),
                "order",
                List.of(table(orders, "shop.orders"), column("shop.orders.customer_id")),
                "data",
                List.of(dashboard(revenue))));

    final List<SearchHit> hits =
        new CatalogStartingPoints(
                () -> false, () -> semantic, () -> search, NO_HYBRID, EVERY_HIT_VISIBLE)
            .search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find(sentence, List.of("table"), 5))
            .hits();

    assertThat(search.queries())
        .containsExactly(sentence, "table", "hold", "customer", "data", "trust", "order");
    assertThat(search.calls)
        .allSatisfy(
            call -> assertThat(call).containsEntry("size", 5).containsEntry("entityType", "table"));
    assertThat(hits)
        .containsExactly(
            new SearchHit("tableColumn", "shop.orders.customer_id", null),
            new SearchHit("table", "shop.customers", customers),
            new SearchHit("dashboard", "bi.revenue", revenue),
            new SearchHit("table", "shop.orders", orders));
  }

  @Test
  void aQuestionOfOnlyShortOrCommonWordsSearchesNothing() throws Exception {
    final QuerySearch search = new QuerySearch(Map.of());

    final List<SearchHit> hits =
        new CatalogStartingPoints(
                () -> false, () -> semantic, () -> search, NO_HYBRID, EVERY_HIT_VISIBLE)
            .search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("what is it", List.of(), 5))
            .hits();

    assertThat(hits).isEmpty();
    assertThat(search.calls).isEmpty();
  }

  private static Map<String, Object> table(final UUID id, final String fqn) {
    return Map.of("id", id.toString(), "entityType", "table", "fullyQualifiedName", fqn);
  }

  private static Map<String, Object> column(final String fqn) {
    return Map.of("entityType", "tableColumn", "fullyQualifiedName", fqn);
  }

  private static Map<String, Object> dashboard(final UUID id) {
    return Map.of(
        "id", id.toString(), "entityType", "dashboard", "fullyQualifiedName", "bi.revenue");
  }

  @Test
  void aFailedWordSearchIsAFailureNotAnEmptyResult() {
    final CatalogStartingPoints failing =
        new CatalogStartingPoints(
            () -> false,
            () -> semantic,
            () -> new RecordingSearch(Map.of("error", "search cluster down")),
            NO_HYBRID,
            EVERY_HIT_VISIBLE);

    assertThatThrownBy(
            () ->
                failing.search(
                    AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("orders", List.of(), 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("search cluster down");
  }

  @Test
  void aFailedSemanticSearchLeavesTheWordSearchToAnswerAndSaysItWasSkipped() throws Exception {
    final CatalogHits found =
        new CatalogStartingPoints(
                () -> true,
                () -> new RecordingSearch(Map.of("error", "embedding backend down")),
                () -> keyword,
                NO_HYBRID,
                EVERY_HIT_VISIBLE)
            .search(AUTHORIZER, CALLER, new KnowledgeGraphCall.Find("orders", List.of(), 1));

    assertThat(found.hits()).isNotEmpty();
    assertThat(found.semanticSearchSkipped()).contains("embedding backend down");
  }

  /** Answers each query with its own results, as a search engine would. */
  private static final class QuerySearch implements McpTool {
    private final Map<String, List<Map<String, Object>>> results;
    private final List<Map<String, Object>> calls = new ArrayList<>();

    private QuerySearch(final Map<String, List<Map<String, Object>>> results) {
      this.results = results;
    }

    List<Object> queries() {
      return calls.stream().map(call -> call.get("query")).toList();
    }

    @Override
    public Map<String, Object> execute(
        final Authorizer authorizer,
        final CatalogSecurityContext securityContext,
        final Map<String, Object> params) {
      calls.add(params);
      return Map.of("results", results.getOrDefault((String) params.get("query"), List.of()));
    }

    @Override
    public Map<String, Object> execute(
        final Authorizer authorizer,
        final Limits limits,
        final CatalogSecurityContext securityContext,
        final Map<String, Object> params) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class RecordingSearch implements McpTool {
    private final Map<String, Object> response;
    private final List<Map<String, Object>> calls = new ArrayList<>();

    private RecordingSearch(final Map<String, Object> response) {
      this.response = response;
    }

    @Override
    public Map<String, Object> execute(
        final Authorizer authorizer,
        final CatalogSecurityContext securityContext,
        final Map<String, Object> params) {
      calls.add(params);
      return response;
    }

    @Override
    public Map<String, Object> execute(
        final Authorizer authorizer,
        final Limits limits,
        final CatalogSecurityContext securityContext,
        final Map<String, Object> params) {
      throw new UnsupportedOperationException();
    }
  }
}
