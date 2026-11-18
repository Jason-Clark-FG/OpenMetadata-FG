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

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.api.context.GuardrailAction;
import org.openmetadata.schema.api.context.GuardrailDecision;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.aicontext.harness.CompileSelector;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Answers an agent host's hook with the host's own hook output JSON. The plugin's hooks pass the
 * host's hook input through unchanged; the harness comes from the caller's sign-in, and any
 * persona or harness name in the input is ignored.
 *
 * <p>A failure surfaces as a tool error, which hosts treat as a non-blocking hook error, so the
 * agent keeps working when OpenMetadata cannot answer. The tool never answers {@code allow}: that
 * would skip the host's own permission prompt for the call.
 */
public class HarnessHookTool implements McpTool {
  /** Claude Code saves longer hook text to a file the model is not asked to read. */
  static final int MAX_HOOK_TEXT_CHARS = 10_000;

  /**
   * Codex shows the model about 2,500 tokens of each hook output. The cap sits a little under that
   * because the compiler estimates {@link CompileSelector#CHARS_PER_TOKEN} characters per token,
   * which undercounts real tokens for code and identifiers.
   */
  static final int CODEX_MAX_HOOK_TEXT_CHARS = 9_000;

  /**
   * Argument the server sets from the caller's connection before the tool runs. A value the caller
   * sends under this name is replaced, so a caller cannot choose its own cap beyond its client
   * label, which it already declares in its User-Agent.
   */
  public static final String CLIENT_NAME_PARAM = "mcp_client_name";

  private static final String CODEX_CLIENT_PREFIX = "codex";

  static final String HOOK_SPECIFIC_OUTPUT = "hookSpecificOutput";
  static final String HOOK_EVENT_NAME = "hookEventName";
  static final String ADDITIONAL_CONTEXT = "additionalContext";
  static final String PERMISSION_DECISION = "permissionDecision";
  static final String PERMISSION_DECISION_REASON = "permissionDecisionReason";
  static final String DENY = "deny";
  private static final String TRUNCATION_MARK = "…";
  private static final Set<String> SUPPORTED_EVENTS =
      Set.of(
          CompileEvent.SESSION_START.value(),
          CompileEvent.USER_PROMPT_SUBMIT.value(),
          CompileEvent.PRE_TOOL_USE.value(),
          CompileEvent.POST_TOOL_USE.value());

  /** The host's hook input, read once at the boundary. */
  record HookInput(
      String event,
      String sessionId,
      String prompt,
      String toolName,
      JsonNode toolInput,
      JsonNode toolResponse,
      String clientName) {

    static HookInput from(Map<String, Object> params) {
      return new HookInput(
          text(params.get("hook_event_name")),
          text(params.get("session_id")),
          text(params.get("prompt")),
          text(params.get("tool_name")),
          json(params.get("tool_input")),
          json(params.get("tool_response")),
          text(params.get(CLIENT_NAME_PARAM)));
    }

    int maxChars() {
      return clientName != null
              && clientName.toLowerCase(Locale.ROOT).startsWith(CODEX_CLIENT_PREFIX)
          ? CODEX_MAX_HOOK_TEXT_CHARS
          : MAX_HOOK_TEXT_CHARS;
    }

    Optional<CompileAgentContext> request() {
      return Optional.ofNullable(event)
          .filter(SUPPORTED_EVENTS::contains)
          .map(
              value ->
                  new CompileAgentContext()
                      .withEvent(CompileEvent.fromValue(value))
                      .withSessionId(sessionId)
                      .withPrompt(prompt)
                      .withToolName(toolName)
                      .withToolInput(toolInput)
                      .withToolOutput(toolResponse)
                      .withMaxTokens(maxChars() / CompileSelector.CHARS_PER_TOKEN));
    }
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    HookInput input = HookInput.from(params);
    return input
        .request()
        .map(
            request ->
                hookOutput(
                    input.event(),
                    GetContextTool.compiler(authorizer).compile(securityContext, request),
                    input.maxChars()))
        .orElse(Map.of());
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    throw new UnsupportedOperationException("HarnessHookTool does not require limit validation.");
  }

  /** Claude Code hook output: context to add and, at PreToolUse, a deny with its reasons. */
  static Map<String, Object> hookOutput(String event, CompiledAgentContext compiled, int maxChars) {
    Map<String, Object> specific = new LinkedHashMap<>();
    specific.put(HOOK_EVENT_NAME, event);
    if (!nullOrEmpty(compiled.getText()) && !compiled.getText().isBlank()) {
      specific.put(ADDITIONAL_CONTEXT, truncated(compiled.getText(), maxChars));
    }
    GuardrailDecision decision = compiled.getDecision();
    if (decision != null && decision.getAction() == GuardrailAction.DENY) {
      specific.put(PERMISSION_DECISION, DENY);
      specific.put(
          PERMISSION_DECISION_REASON, truncated(String.join(" ", decision.getReasons()), maxChars));
    }
    return specific.size() > 1 ? Map.of(HOOK_SPECIFIC_OUTPUT, specific) : Map.of();
  }

  /**
   * The compiler already packs whole items into the host's cap; this cuts what its separators or a
   * guardrail reason still push past it.
   */
  static String truncated(String text, int maxChars) {
    return text.length() <= maxChars
        ? text
        : text.substring(0, maxChars - TRUNCATION_MARK.length()) + TRUNCATION_MARK;
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /** Hosts substitute a JSON value into a string argument, so a string may hold JSON. */
  private static JsonNode json(Object value) {
    JsonNode node = null;
    if (value instanceof String text) {
      node = parsedOrText(text);
    } else if (value != null) {
      node = JsonUtils.getObjectMapper().valueToTree(value);
    }
    return node;
  }

  private static JsonNode parsedOrText(String text) {
    JsonNode node;
    try {
      node = JsonUtils.getObjectMapper().readTree(text);
    } catch (JsonProcessingException exception) {
      node = TextNode.valueOf(text);
    }
    return node == null ? TextNode.valueOf(text) : node;
  }
}
