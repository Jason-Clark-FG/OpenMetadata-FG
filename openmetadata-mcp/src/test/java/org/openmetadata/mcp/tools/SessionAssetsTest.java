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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.mcp.tools.SessionAssets.Asset;

class SessionAssetsTest {
  private static final DeliveryLedger.Caller ALICE =
      new DeliveryLedger.Caller("alice", "Claude Desktop", "analyst", "s1");
  private static final DeliveryLedger.Caller BOB =
      new DeliveryLedger.Caller("bob", "Claude Desktop", "analyst", "s1");

  @Test
  void aConversationKeepsOnlyItsFiftyMostRecentlyUsedAssets() {
    final SessionAssets assets = new SessionAssets();
    for (int index = 0; index < 60; index++) {
      assets.observe(
          ALICE, "get_entity_details", Map.of("entityType", "table", "fqn", "db.t" + index));
    }
    assets.observe(ALICE, "get_entity_details", Map.of("entityType", "table", "fqn", "db.t10"));

    final List<Asset> seen = assets.seen(ALICE.conversationKey());

    assertEquals(50, seen.size());
    assertEquals(new Asset("table", "db.t11"), seen.getFirst());
    assertEquals(new Asset("table", "db.t10"), seen.getLast());
  }

  @Test
  void conversationsDoNotShareAssets() {
    final SessionAssets assets = new SessionAssets();
    assets.observe(ALICE, "get_entity_details", Map.of("entityType", "table", "fqn", "db.orders"));

    assertEquals(List.of(), assets.seen(BOB.conversationKey()));
    assertEquals(List.of(new Asset("table", "db.orders")), assets.seen(ALICE.conversationKey()));
  }

  @Test
  void aFinishedAnswerCountsItsConceptsAndTablesButASearchCountsNothing() {
    final SessionAssets assets = new SessionAssets();
    assets.observe(ALICE, "search_metadata", Map.of("query", "orders"));
    assets.observe(
        ALICE,
        "finish_answer",
        Map.of(
            "concepts",
            List.of(Map.of("fqn", "Finance.ARR", "entityType", "glossaryTerm"), "Finance.Margin"),
            "tables",
            List.of("db.orders", Map.of("fqn", "db.refunds"))));

    assertEquals(
        List.of(
            new Asset("glossaryTerm", "Finance.ARR"),
            new Asset("glossaryTerm", "Finance.Margin"),
            new Asset("table", "db.orders"),
            new Asset("table", "db.refunds")),
        assets.seen(ALICE.conversationKey()));
  }
}
