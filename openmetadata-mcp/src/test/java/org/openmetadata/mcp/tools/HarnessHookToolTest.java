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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.api.context.GuardrailAction;
import org.openmetadata.schema.api.context.GuardrailDecision;
import org.openmetadata.schema.utils.JsonUtils;

class HarnessHookToolTest {

  @Test
  void contextBecomesAdditionalContext() {
    assertEquals(
        Map.of(
            "hookSpecificOutput",
            Map.of("hookEventName", "SessionStart", "additionalContext", "Cite FQNs.")),
        HarnessHookTool.hookOutput("SessionStart", compiled("Cite FQNs.", null)));
  }

  @Test
  void aDenyBecomesAPermissionDecisionWithItsReasons() {
    assertEquals(
        Map.of(
            "hookSpecificOutput",
            Map.of(
                "hookEventName",
                "PreToolUse",
                "permissionDecision",
                "deny",
                "permissionDecisionReason",
                "orders is deprecated. slack is blocked.")),
        HarnessHookTool.hookOutput(
            "PreToolUse",
            compiled(
                "",
                new GuardrailDecision()
                    .withAction(GuardrailAction.DENY)
                    .withReasons(List.of("orders is deprecated.", "slack is blocked.")))));
  }

  @Test
  void aWarnAddsContextAndNeverAnswersAllow() {
    assertEquals(
        Map.of(
            "hookSpecificOutput",
            Map.of("hookEventName", "PreToolUse", "additionalContext", "orders has incidents.")),
        HarnessHookTool.hookOutput(
            "PreToolUse",
            compiled(
                "orders has incidents.",
                new GuardrailDecision()
                    .withAction(GuardrailAction.WARN)
                    .withReasons(List.of("orders has incidents.")))));
    assertEquals(
        Map.of(),
        HarnessHookTool.hookOutput(
            "PreToolUse",
            compiled(
                "",
                new GuardrailDecision().withAction(GuardrailAction.ALLOW).withReasons(List.of()))));
  }

  @Test
  void injectedTextIsCutAtTenThousandCharacters() {
    final String cut = HarnessHookTool.truncated("x".repeat(12_000));

    assertEquals(10_000, cut.length());
    assertEquals("x".repeat(9_999) + "…", cut);
    assertEquals("short", HarnessHookTool.truncated("short"));
  }

  @Test
  void hookInputAcceptsToolInputAsAnObjectOrAsAJsonString() {
    final CompileAgentContext fromObject =
        HarnessHookTool.HookInput.from(
                Map.of(
                    "hook_event_name", "PreToolUse",
                    "session_id", "s1",
                    "tool_name", "mcp__db__query",
                    "tool_input", Map.of("sql", "select 1")))
            .request()
            .orElseThrow();
    final CompileAgentContext fromString =
        HarnessHookTool.HookInput.from(
                Map.of(
                    "hook_event_name", "PreToolUse",
                    "session_id", "s1",
                    "tool_name", "mcp__db__query",
                    "tool_input", "{\"sql\":\"select 1\"}"))
            .request()
            .orElseThrow();

    assertEquals(CompileEvent.PRE_TOOL_USE, fromObject.getEvent());
    assertEquals(JsonUtils.readTree("{\"sql\":\"select 1\"}"), fromObject.getToolInput());
    assertEquals(fromObject, fromString);
  }

  @Test
  void unsupportedEventsAndClientPersonaFieldsAreIgnored() {
    assertEquals(
        Optional.empty(),
        HarnessHookTool.HookInput.from(Map.of("hook_event_name", "Stop")).request());
    assertEquals(
        new CompileAgentContext().withEvent(CompileEvent.SESSION_START).withSessionId("s1"),
        HarnessHookTool.HookInput.from(
                Map.of(
                    "hook_event_name", "SessionStart",
                    "session_id", "s1",
                    "persona", "Admin",
                    "harness", "default"))
            .request()
            .orElseThrow());
  }

  private static CompiledAgentContext compiled(
      final String text, final GuardrailDecision decision) {
    return new CompiledAgentContext().withText(text).withDecision(decision);
  }
}
