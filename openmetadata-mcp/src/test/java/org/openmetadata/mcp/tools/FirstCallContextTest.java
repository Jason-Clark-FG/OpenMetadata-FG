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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.spec.McpSchema;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openmetadata.mcp.tools.DeliveryLedger.Caller;
import org.openmetadata.schema.api.context.AppliedHarness;
import org.openmetadata.schema.api.context.AppliedHarnesses;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

class FirstCallContextTest {
  private static final Caller ALICE = new Caller("alice", "Claude Desktop", null, null);

  private final AtomicInteger compiles = new AtomicInteger();
  private final CatalogSecurityContext security = security("alice");
  private final DeliveryLedger ledger = new DeliveryLedger(Duration.ofMinutes(30), () -> 0L);

  private static CatalogSecurityContext security(String name) {
    CatalogSecurityContext context = mock(CatalogSecurityContext.class);
    Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn(name);
    when(context.getUserPrincipal()).thenReturn(principal);
    return context;
  }

  private static CompiledAgentContext compiled(String text) {
    AppliedHarness persona =
        new AppliedHarness()
            .withId(UUID.randomUUID())
            .withName("p")
            .withDisplayName("Data Steward")
            .withVersion(0.3);
    return new CompiledAgentContext()
        .withHarness(new AppliedHarnesses().withDefault(persona).withPersona(persona))
        .withText(text);
  }

  private FirstCallContext contextCompiling(String text) {
    return new FirstCallContext(
        ledger,
        (authorizer, context, client) -> {
          compiles.incrementAndGet();
          return compiled(text);
        },
        name -> "Alice A.");
  }

  private static McpSchema.CallToolResult answer() {
    return McpSchema.CallToolResult.builder()
        .content(List.of(McpSchema.TextContent.builder("{\"hits\":1}").build()))
        .isError(false)
        .build();
  }

  private static String text(McpSchema.CallToolResult result, int index) {
    return ((McpSchema.TextContent) result.content().get(index)).text();
  }

  @Test
  void theFirstResultCarriesTheContextBlockAheadOfTheAnswerAndTheSecondDoesNot() {
    FirstCallContext context = contextCompiling("Be precise.");

    McpSchema.CallToolResult first =
        context.apply(null, security, ALICE, "search_metadata", answer());
    McpSchema.CallToolResult second =
        context.apply(null, security, ALICE, "search_metadata", answer());

    assertThat(first.content()).hasSize(2);
    assertThat(text(first, 0))
        .startsWith(
            "## OpenMetadata context for Alice A. (Data Steward harness v0.3)\n\nBe precise.")
        .endsWith("use the tool result below.");
    assertThat(text(first, 1)).isEqualTo("{\"hits\":1}");
    assertThat(second.content()).hasSize(1);
    assertThat(compiles.get()).isEqualTo(1);
  }

  @Test
  void aDisabledHarnessOrEmptyCompileLeavesTheAnswerAlone() {
    FirstCallContext context = contextCompiling("");

    McpSchema.CallToolResult result =
        context.apply(null, security, ALICE, "search_metadata", answer());

    assertThat(result.content()).hasSize(1);
  }

  @Test
  void aFailedCompileReturnsTheAnswerUnchangedAndTriesAgainNextCall() {
    AtomicInteger attempts = new AtomicInteger();
    FirstCallContext context =
        new FirstCallContext(
            ledger,
            (authorizer, ctx, client) -> {
              if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("harness unavailable");
              }
              return compiled("Be precise.");
            },
            name -> name);

    McpSchema.CallToolResult failed =
        context.apply(null, security, ALICE, "search_metadata", answer());
    McpSchema.CallToolResult retried =
        context.apply(null, security, ALICE, "search_metadata", answer());

    assertThat(failed.content()).hasSize(1);
    assertThat(retried.content()).hasSize(2);
  }

  @Test
  void theHookAndGetContextAreNeverPrefixedAndMarkTheCallerServed() {
    FirstCallContext context = contextCompiling("Be precise.");
    Caller hooked = new Caller("alice", "Claude CLI", null, null);
    Caller explicit = new Caller("alice", "Cursor", null, null);

    McpSchema.CallToolResult hook = context.apply(null, security, hooked, "harness_hook", answer());
    McpSchema.CallToolResult get = context.apply(null, security, explicit, "get_context", answer());

    assertThat(hook.content()).hasSize(1);
    assertThat(get.content()).hasSize(1);
    assertThat(context.apply(null, security, hooked, "search_metadata", answer()).content())
        .hasSize(1);
    assertThat(context.apply(null, security, explicit, "search_metadata", answer()).content())
        .hasSize(1);
    assertThat(compiles.get()).isZero();
  }

  @Test
  void anErrorResultIsNotPrefixedAndDoesNotUseTheFirstCall() {
    FirstCallContext context = contextCompiling("Be precise.");
    McpSchema.CallToolResult error =
        McpSchema.CallToolResult.builder()
            .content(List.of(McpSchema.TextContent.builder("denied").build()))
            .isError(true)
            .build();

    McpSchema.CallToolResult denied =
        context.apply(null, security, ALICE, "search_metadata", error);
    McpSchema.CallToolResult next =
        context.apply(null, security, ALICE, "search_metadata", answer());

    assertThat(denied.content()).hasSize(1);
    assertThat(next.content()).hasSize(2);
  }
}
