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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;

/**
 * Remembers which callers already hold their SessionStart context, so a hookless MCP client gets it
 * once per conversation. The stateless transport issues no session id, so a caller is the user,
 * the client label and the active persona, and a conversation is a run of calls with no gap longer
 * than the idle window. A caller that sends a session id is keyed by it too.
 */
public final class DeliveryLedger {
  public static final Duration IDLE_WINDOW = Duration.ofMinutes(30);
  static final int MAX_ENTRIES = 10_000;

  /** Who is calling. {@code sessionId} is null when the transport carries none. */
  public record Caller(String user, String client, String persona, String sessionId) {
    private String conversationKey() {
      return String.join("\n", user, nonNull(client), nonNull(persona), nonNull(sessionId));
    }

    // A hook-driven client sends harness_hook with the same user and client label on every tool
    // use, whatever persona or session it names.
    private String hostKey() {
      return user + "\n" + nonNull(client);
    }

    private static String nonNull(String value) {
      return value == null ? "" : value;
    }
  }

  private final Cache<String, Boolean> delivered;
  private final Cache<String, Boolean> hooked;

  public DeliveryLedger() {
    this(IDLE_WINDOW, Ticker.systemTicker());
  }

  DeliveryLedger(Duration idleWindow, Ticker ticker) {
    this.delivered = newCache(idleWindow, ticker);
    this.hooked = newCache(idleWindow, ticker);
  }

  private static Cache<String, Boolean> newCache(Duration idleWindow, Ticker ticker) {
    return Caffeine.newBuilder()
        .maximumSize(MAX_ENTRIES)
        .expireAfterAccess(idleWindow)
        .ticker(ticker)
        .build();
  }

  /** True when this call opens a conversation for a caller whose host sends no hooks. Touches the entry. */
  public boolean isDue(Caller caller) {
    return hooked.getIfPresent(caller.hostKey()) == null
        && delivered.getIfPresent(caller.conversationKey()) == null;
  }

  public void markDelivered(Caller caller) {
    delivered.put(caller.conversationKey(), Boolean.TRUE);
  }

  public void markHooked(Caller caller) {
    hooked.put(caller.hostKey(), Boolean.TRUE);
  }
}
