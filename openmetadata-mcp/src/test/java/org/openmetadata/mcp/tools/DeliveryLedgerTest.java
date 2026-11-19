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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.openmetadata.mcp.tools.DeliveryLedger.Caller;

class DeliveryLedgerTest {
  private final AtomicLong nanos = new AtomicLong();
  private final DeliveryLedger ledger = new DeliveryLedger(Duration.ofMinutes(30), nanos::get);

  private void advanceMinutes(long minutes) {
    nanos.addAndGet(Duration.ofMinutes(minutes).toNanos());
  }

  @Test
  void aCallerIsDueUntilDeliveredThenNotWithinTheWindow() {
    Caller caller = new Caller("alice", "Claude Desktop", null, null);
    assertThat(ledger.isDue(caller)).isTrue();

    ledger.markDelivered(caller);
    advanceMinutes(20);

    assertThat(ledger.isDue(caller)).isFalse();
  }

  @Test
  void aCallerIsDueAgainAfterAnIdleGapAndActivityKeepsItOpen() {
    Caller caller = new Caller("alice", "Claude Desktop", null, null);
    ledger.markDelivered(caller);
    advanceMinutes(20);
    assertThat(ledger.isDue(caller)).isFalse();
    advanceMinutes(20);
    assertThat(ledger.isDue(caller)).isFalse();

    advanceMinutes(31);

    assertThat(ledger.isDue(caller)).isTrue();
  }

  @Test
  void deliveryIsNeverSharedAcrossUsersClientsPersonasOrSessions() {
    ledger.markDelivered(new Caller("alice", "Claude Desktop", "p1", "s1"));

    assertThat(ledger.isDue(new Caller("bob", "Claude Desktop", "p1", "s1"))).isTrue();
    assertThat(ledger.isDue(new Caller("alice", "Cursor", "p1", "s1"))).isTrue();
    assertThat(ledger.isDue(new Caller("alice", "Claude Desktop", "p2", "s1"))).isTrue();
    assertThat(ledger.isDue(new Caller("alice", "Claude Desktop", "p1", "s2"))).isTrue();
    assertThat(ledger.isDue(new Caller("alice", "Claude Desktop", "p1", "s1"))).isFalse();
  }

  @Test
  void aClientThatSentTheHarnessHookIsNeverDueWhileItKeepsSendingIt() {
    Caller viaHook = new Caller("alice", "Claude CLI", "p1", null);
    ledger.markHooked(viaHook);

    assertThat(ledger.isDue(new Caller("alice", "Claude CLI", "p2", "s9"))).isFalse();
    assertThat(ledger.isDue(new Caller("alice", "Claude Desktop", null, null))).isTrue();
    assertThat(ledger.isDue(new Caller("bob", "Claude CLI", null, null))).isTrue();

    advanceMinutes(31);

    assertThat(ledger.isDue(viaHook)).isTrue();
  }

  @Test
  void aNullClientIsOneCallerOfItsOwn() {
    ledger.markDelivered(new Caller("alice", null, null, null));

    assertThat(ledger.isDue(new Caller("alice", null, null, null))).isFalse();
    assertThat(ledger.isDue(new Caller("alice", "Cursor", null, null))).isTrue();
  }
}
