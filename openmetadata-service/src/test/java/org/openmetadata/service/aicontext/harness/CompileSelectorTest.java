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
package org.openmetadata.service.aicontext.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.CompiledSegment;
import org.openmetadata.service.aicontext.harness.CompileSelector.Document;
import org.openmetadata.service.aicontext.harness.CompileSelector.Packed;
import org.openmetadata.service.aicontext.harness.ContextItem.AssetRef;
import org.openmetadata.service.aicontext.harness.ContextItem.Origin;

class CompileSelectorTest {
  private static final ContextItem INSTRUCTIONS =
      ContextItem.fixed(Origin.DEFAULT, ContextItem.INSTRUCTIONS, "instructions", "Cite FQNs.");
  private static final ContextItem PROMPT =
      ContextItem.fixed(Origin.PERSONA, ContextItem.PROMPT, "prompt", "You serve analysts.");
  private static final ContextItem REVENUE =
      knowledge(Origin.DEFAULT, ContextItem.GLOSSARY, "Revenue", true, null);
  private static final ContextItem CHURN =
      knowledge(Origin.PERSONA, ContextItem.METRICS, "Churn", false, null);
  private static final ContextItem ORDERS =
      knowledge(
          Origin.PERSONA,
          ContextItem.ASSETS,
          "orders",
          false,
          new AssetRef("table", "svc.db.sales.orders"));
  private static final Document DOCUMENT =
      new Document(List.of(INSTRUCTIONS, PROMPT), List.of(REVENUE, CHURN, ORDERS));

  @Test
  void sessionStartDeliversFixedItemsAndAlwaysInContextKnowledge() {
    assertEquals(
        List.of("instructions", "prompt", "glossary:Revenue"),
        keys(CompileSelector.sessionStart(DOCUMENT)));
  }

  @Test
  void userPromptSubmitDeliversNamedKnowledgeAndUnsentAlwaysInContextItems() {
    assertEquals(
        List.of("metrics:Churn", "glossary:Revenue"),
        keys(CompileSelector.userPromptSubmit(DOCUMENT, "Why did churn rise?", Set.of())));
    assertEquals(
        List.of("metrics:Churn"),
        keys(
            CompileSelector.userPromptSubmit(
                DOCUMENT, "Why did churn rise?", Set.of("glossary:Revenue"))));
  }

  @Test
  void namesMatchWholeWordsOnly() {
    assertEquals(
        List.of(),
        keys(CompileSelector.userPromptSubmit(DOCUMENT, "churned users", Set.of(REVENUE.key()))));
    assertEquals(
        List.of("assets:orders"),
        keys(CompileSelector.named(DOCUMENT.knowledge(), "SELECT * FROM ORDERS")));
  }

  @Test
  void preToolUseDeliversGuardrailNotesThenNamedAssetCardsOnly() {
    final ContextItem note =
        ContextItem.fixed(Origin.DEFAULT, ContextItem.GUARDRAILS, "guardrails", "Flagged.");
    assertEquals(
        List.of("guardrails", "assets:orders"),
        keys(
            CompileSelector.preToolUse(
                DOCUMENT, "select churn, revenue from orders", List.of(note))));
  }

  @Test
  void packPutsDefaultItemsFirstAndCutsPersonaItemsWhenTheBudgetRunsOut() {
    final ContextItem defaultItem = sized(Origin.DEFAULT, "default", 40);
    final ContextItem personaItem = sized(Origin.PERSONA, "persona", 40);

    final Packed packed = CompileSelector.pack(List.of(personaItem, defaultItem), 15);

    assertEquals(List.of("default"), keys(packed.items()));
    assertEquals("x".repeat(40), packed.text());
    assertEquals(10, packed.tokens());
  }

  @Test
  void packSkipsAnItemThatDoesNotFitAndKeepsALaterSmallerOne() {
    final Packed packed =
        CompileSelector.pack(
            List.of(
                sized(Origin.DEFAULT, "large", 80),
                sized(Origin.PERSONA, "small", 8),
                sized(Origin.PERSONA, "small", 8)),
            10);

    assertEquals(List.of("small"), keys(packed.items()));
    assertEquals(2, packed.tokens());
  }

  @Test
  void packJoinsTextAndCountsTokensPerKind() {
    final Packed packed = CompileSelector.pack(List.of(PROMPT, INSTRUCTIONS, REVENUE), 1_000);

    assertEquals("Cite FQNs.\n\nRevenue card\n\nYou serve analysts.", packed.text());
    assertEquals(
        List.of(
            new CompiledSegment().withKind(ContextItem.INSTRUCTIONS).withTokens(3),
            new CompiledSegment().withKind(ContextItem.GLOSSARY).withTokens(3),
            new CompiledSegment().withKind(ContextItem.PROMPT).withTokens(5)),
        packed.segments());
    assertEquals(11, packed.tokens());
  }

  @Test
  void aZeroBudgetDeliversNothing() {
    final Packed packed = CompileSelector.pack(List.of(INSTRUCTIONS), 0);

    assertEquals("", packed.text());
    assertEquals(0, packed.tokens());
  }

  private static ContextItem knowledge(
      final Origin origin,
      final String kind,
      final String name,
      final boolean alwaysInContext,
      final AssetRef asset) {
    return new ContextItem(
        origin,
        kind,
        kind.toLowerCase(Locale.ROOT) + ":" + name,
        name + " card",
        List.of(name),
        alwaysInContext,
        asset);
  }

  private static ContextItem sized(final Origin origin, final String key, final int chars) {
    return ContextItem.fixed(origin, ContextItem.ASSETS, key, "x".repeat(chars));
  }

  private static List<String> keys(final List<ContextItem> items) {
    return items.stream().map(ContextItem::key).toList();
  }
}
