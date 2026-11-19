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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.api.context.GuardrailAction;
import org.openmetadata.schema.api.context.GuardrailDecision;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.aicontext.capture.AgentMemoryDuplicates;
import org.openmetadata.service.aicontext.capture.PromptAssetLookup;
import org.openmetadata.service.aicontext.capture.PromptMemoryCues;
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
@Slf4j
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
  private static final String NOTE_SEPARATOR = "\n\n";
  private static final int NOTE_FACT_CHARS = 300;

  /**
   * The note for each fact a user just had saved, so a hook the host retries gets the same note
   * and no second memory, whether or not the fact has an anchor the memory dedup could match.
   */
  private static final Cache<String, String> RECENT_CAPTURES =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(10)).build();

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

  /** The most hook text the client's host shows its model, by the label its User-Agent resolved to. */
  static int maxCharsFor(String clientName) {
    return clientName != null && clientName.toLowerCase(Locale.ROOT).startsWith(CODEX_CLIENT_PREFIX)
        ? CODEX_MAX_HOOK_TEXT_CHARS
        : MAX_HOOK_TEXT_CHARS;
  }

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
      return maxCharsFor(clientName);
    }

    Optional<CompileAgentContext> request() {
      return request(0);
    }

    /** {@code reservedChars} of the host's cap are kept for text added after the compile. */
    Optional<CompileAgentContext> request(int reservedChars) {
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
                      .withMaxTokens(
                          Math.max(0, maxChars() - reservedChars)
                              / CompileSelector.CHARS_PER_TOKEN));
    }
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    HookInput input = HookInput.from(params);
    Optional<String> note =
        CompileEvent.USER_PROMPT_SUBMIT.value().equals(input.event())
            ? captured(authorizer, limits, securityContext, input, params)
            : Optional.empty();
    int reserved = note.map(text -> text.length() + NOTE_SEPARATOR.length()).orElse(0);
    return input
        .request(reserved)
        .map(
            request ->
                hookOutput(
                    input.event(),
                    GetContextTool.compiler(authorizer).compile(securityContext, request),
                    input.maxChars(),
                    note))
        .orElse(Map.of());
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    throw new UnsupportedOperationException("HarnessHookTool saves memories and needs limits.");
  }

  /**
   * Saves the fact a prompt states under an explicit cue as the caller's memory and returns the
   * note that tells the agent to say so. A failure to save never blocks the prompt: the agent
   * simply receives no note and the model's own {@code remember} tool remains.
   */
  private static Optional<String> captured(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      HookInput input,
      Map<String, Object> params) {
    Optional<String> note = Optional.empty();
    Optional<String> fact = PromptMemoryCues.factIn(input.prompt());
    if (fact.isPresent()) {
      String key =
          CommonUtils.principal(securityContext)
              + "\n"
              + AgentMemoryDuplicates.normalize(fact.get());
      note = Optional.ofNullable(RECENT_CAPTURES.getIfPresent(key));
      if (note.isEmpty()) {
        try {
          note = save(authorizer, limits, securityContext, input, params, fact.get());
        } catch (RuntimeException failure) {
          LOG.warn("Could not save a memory from the user's prompt: {}", failure.getMessage());
        }
        note.ifPresent(text -> RECENT_CAPTURES.put(key, text));
      }
    }
    return note;
  }

  private static Optional<String> save(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      HookInput input,
      Map<String, Object> params,
      String fact) {
    List<SessionAssets.Asset> about =
        PromptAssetLookup.find(PromptMemoryCues.assetNames(fact), securityContext).stream()
            .map(found -> new SessionAssets.Asset(found.entityType(), found.fullyQualifiedName()))
            .toList();
    String conversation =
        params.get(RememberTool.CONVERSATION_PARAM) instanceof String key ? key : null;
    McpSchema.CallToolResult saved =
        new RememberTool()
            .captureFromPrompt(
                authorizer, limits, securityContext, fact, about, conversation, input.clientName());
    Optional<String> note = Optional.empty();
    if (saved.structuredContent() instanceof Map<?, ?> outcome && outcome.get("url") != null) {
      boolean created = Boolean.TRUE.equals(outcome.get("captured"));
      note =
          Optional.of(
              (created
                      ? "Saved to OpenMetadata as a memory ("
                      : "Already in OpenMetadata as a memory (")
                  + outcome.get("visibility")
                  + "): "
                  + truncated(fact, NOTE_FACT_CHARS)
                  + " - "
                  + outcome.get("url")
                  + ". Tell the user it was saved.");
    }
    return note;
  }

  static Map<String, Object> hookOutput(String event, CompiledAgentContext compiled, int maxChars) {
    return hookOutput(event, compiled, maxChars, Optional.empty());
  }

  /**
   * Claude Code hook output: context to add, led by the note that a memory was saved, and, at
   * PreToolUse, a deny with its reasons.
   */
  static Map<String, Object> hookOutput(
      String event, CompiledAgentContext compiled, int maxChars, Optional<String> note) {
    Map<String, Object> specific = new LinkedHashMap<>();
    specific.put(HOOK_EVENT_NAME, event);
    String compiledText = nullOrEmpty(compiled.getText()) ? "" : compiled.getText();
    String text =
        note.isPresent() && !compiledText.isBlank()
            ? note.get() + NOTE_SEPARATOR + compiledText
            : note.orElse(compiledText);
    if (!text.isBlank()) {
      specific.put(ADDITIONAL_CONTEXT, truncated(text, maxChars));
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
