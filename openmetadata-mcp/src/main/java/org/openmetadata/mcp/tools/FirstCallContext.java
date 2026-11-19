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

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.openmetadata.schema.api.context.AppliedHarness;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.Include;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.harness.CompileSelector;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hosts without hooks (Claude Desktop, claude.ai, ChatGPT) rarely call get_context on their own, so
 * the first result a caller gets in a conversation carries the SessionStart context in front of it.
 *
 * <p>A caller whose client has sent harness_hook inside the idle window already gets the context
 * from its hooks and is skipped. A failed compile returns the tool result unchanged.
 */
public final class FirstCallContext {
  private static final Logger LOG = LoggerFactory.getLogger(FirstCallContext.class);
  static final String HARNESS_HOOK = "harness_hook";
  static final String GET_CONTEXT = "get_context";

  /** Carries what the caller's harness says inside its own first answer of a run. */
  static final String KNOWLEDGE_GRAPH = "knowledge_graph";

  private static final String CLOSING =
      "These are standing instructions from OpenMetadata for this task. Follow them when you use"
          + " the tool result above and in the rest of the conversation.";

  /** Compiles the SessionStart context for the caller, sized to the caller's client. */
  @FunctionalInterface
  interface Compiler {
    CompiledAgentContext compile(
        Authorizer authorizer, CatalogSecurityContext securityContext, String client);
  }

  private final DeliveryLedger ledger;
  private final Compiler compiler;
  private final UnaryOperator<String> userLabels;

  FirstCallContext(DeliveryLedger ledger, Compiler compiler, UnaryOperator<String> userLabels) {
    this.ledger = ledger;
    this.compiler = compiler;
    this.userLabels = userLabels;
  }

  public static FirstCallContext create(DeliveryLedger ledger) {
    return new FirstCallContext(
        ledger, FirstCallContext::compileSessionStart, FirstCallContext::userLabel);
  }

  /** Returns {@code result}, preceded by the caller's context when this call opens a conversation. */
  public McpSchema.CallToolResult apply(
      Authorizer authorizer,
      CatalogSecurityContext securityContext,
      DeliveryLedger.Caller caller,
      String toolName,
      McpSchema.CallToolResult result) {
    McpSchema.CallToolResult answer = result;
    if (HARNESS_HOOK.equals(toolName)) {
      ledger.markHooked(caller);
    } else if (GET_CONTEXT.equals(toolName) || KNOWLEDGE_GRAPH.equals(toolName)) {
      ledger.markDelivered(caller);
    } else if (!Boolean.TRUE.equals(result.isError()) && ledger.isDue(caller)) {
      answer = withContext(authorizer, securityContext, caller, result);
    }
    return answer;
  }

  private McpSchema.CallToolResult withContext(
      Authorizer authorizer,
      CatalogSecurityContext securityContext,
      DeliveryLedger.Caller caller,
      McpSchema.CallToolResult result) {
    McpSchema.CallToolResult answer = result;
    try {
      CompiledAgentContext compiled =
          compiler.compile(authorizer, securityContext, caller.client());
      ledger.markDelivered(caller);
      if (!nullOrEmpty(compiled.getText()) && !compiled.getText().isBlank()) {
        answer = append(block(securityContext, compiled), result);
      }
    } catch (RuntimeException e) {
      LOG.warn("Could not compile the first-call context; returning the tool result alone", e);
    }
    return answer;
  }

  private static CompiledAgentContext compileSessionStart(
      Authorizer authorizer, CatalogSecurityContext securityContext, String client) {
    int maxChars = HarnessHookTool.maxCharsFor(client);
    return GetContextTool.compiler(authorizer)
        .compile(
            securityContext,
            new CompileAgentContext()
                .withEvent(CompileEvent.SESSION_START)
                .withMaxTokens(maxChars / CompileSelector.CHARS_PER_TOKEN));
  }

  private String block(CatalogSecurityContext securityContext, CompiledAgentContext compiled) {
    String user = userLabels.apply(securityContext.getUserPrincipal().getName());
    return "## OpenMetadata context for "
        + user
        + " ("
        + harnessLabel(compiled)
        + ")\n\n"
        + compiled.getText().strip()
        + "\n\n"
        + CLOSING;
  }

  private static String harnessLabel(CompiledAgentContext compiled) {
    AppliedHarness harness =
        compiled.getHarness().getPersona() != null
            ? compiled.getHarness().getPersona()
            : compiled.getHarness().getDefault();
    return harness.getDisplayName() + " harness v" + harness.getVersion();
  }

  private static String userLabel(String userName) {
    String label = userName;
    try {
      User user = Entity.getEntityByName(Entity.USER, userName, "", Include.NON_DELETED);
      if (!nullOrEmpty(user.getDisplayName())) {
        label = user.getDisplayName();
      }
    } catch (RuntimeException e) {
      LOG.debug("No display name for {}; using the user name", userName);
    }
    return label;
  }

  /** The tool's own answer stays the first block, so clients that read content[0] are unaffected. */
  static McpSchema.CallToolResult append(String text, McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = new ArrayList<>(result.content());
    content.add(McpSchema.TextContent.builder(text).build());
    McpSchema.CallToolResult.Builder withContext =
        McpSchema.CallToolResult.builder().content(content).isError(result.isError());
    if (result.structuredContent() != null) {
      withContext.structuredContent(result.structuredContent());
    }
    return withContext.build();
  }
}
