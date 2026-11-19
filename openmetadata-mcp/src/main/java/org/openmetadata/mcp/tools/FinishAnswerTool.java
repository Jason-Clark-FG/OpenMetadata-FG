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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.api.configuration.OpenMetadataBaseUrlConfiguration;
import org.openmetadata.schema.api.context.AnswerCitation;
import org.openmetadata.schema.api.context.AppliedHarness;
import org.openmetadata.schema.api.context.AppliedHarnesses;
import org.openmetadata.schema.api.context.FinishAnswer;
import org.openmetadata.schema.api.context.FinishAnswerTimeWindow;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.settings.SettingsType;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.answer.AnswerCatalog;
import org.openmetadata.service.aicontext.answer.AnswerCitationBuilder;
import org.openmetadata.service.aicontext.answer.AnswerMarkdown;
import org.openmetadata.service.aicontext.answer.AnswerSettings;
import org.openmetadata.service.aicontext.answer.LiveAnswerCatalog;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.AgentHarnessRepository;
import org.openmetadata.service.resources.settings.SettingsCache;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Ends a data answer. The agent names the terms, metrics and tables it used; the server looks each
 * one up as the caller and writes the "How I got this" block, so a citation cannot be invented.
 * The text content is the Markdown block and the structured content is the typed citation.
 */
@Slf4j
public class FinishAnswerTool {
  static final String SHOW_VERBATIM =
      "Show the answer and the \"How I got this\" list above to the user as written. Do not"
          + " reword it or add facts to it.";
  private static final Set<String> INPUT_KEYS =
      Set.of(
          "question", "answer", "result", "concepts", "timeWindow", "tables", "sql", "execution");
  private static final String DEFAULT_HARNESS_LABEL = "Company default";

  public McpSchema.CallToolResult call(
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext,
      final Map<String, Object> params) {
    final FinishAnswer request = parse(params);
    final AnswerCitation citation =
        new AnswerCitationBuilder(catalog(authorizer, securityContext), settings(), Instant.now())
            .build(request);
    labelWithHarness(citation, appliedHarnesses(authorizer, securityContext));
    final String block = AnswerMarkdown.render(citation, Instant.now());
    return McpSchema.CallToolResult.builder()
        .content(List.of(McpSchema.TextContent.builder(block + "\n" + SHOW_VERBATIM).build()))
        .structuredContent(JsonUtils.getMap(citation))
        .isError(false)
        .build();
  }

  /**
   * Names the harness the caller's agent runs under: their persona's when it has one, else the
   * company default. The card shows it in its header and footer.
   */
  static void labelWithHarness(final AnswerCitation citation, final AppliedHarnesses applied) {
    final AppliedHarness harness =
        applied == null
            ? null
            : applied.getPersona() != null ? applied.getPersona() : applied.getDefault();
    if (harness != null) {
      citation.setHarnessName(
          applied.getPersona() == null || nullOrEmpty(harness.getDisplayName())
              ? DEFAULT_HARNESS_LABEL
              : harness.getDisplayName());
      citation.setHarnessVersion(harness.getVersion());
    }
  }

  private static AppliedHarnesses appliedHarnesses(
      final Authorizer authorizer, final CatalogSecurityContext securityContext) {
    AppliedHarnesses applied = null;
    try {
      applied = GetContextTool.compiler(authorizer).appliedTo(securityContext);
    } catch (RuntimeException exception) {
      LOG.debug("No harness label for the citation: {}", exception.getMessage());
    }
    return applied;
  }

  /**
   * Models send a bare name where an object is expected and add keys the schema does not have, so
   * the arguments are reshaped before they are read. See
   * ADR:2026-11-06-mcp-tool-arguments-are-coerced-by-tools-not-validated-by-the-sdk.
   */
  static FinishAnswer parse(final Map<String, Object> params) {
    final Map<String, Object> input = new HashMap<>();
    params.forEach(
        (key, value) -> {
          if (INPUT_KEYS.contains(key) && value != null) {
            input.put(key, value);
          }
        });
    requireText(input, "question");
    requireText(input, "answer");
    wrapNames(input, "concepts", "fqn");
    wrapNames(input, "tables", "fqn");
    wrapName(input, "timeWindow", "phrase");
    wrapName(input, "result", "value");
    wrapName(input, "execution", "executor");
    final FinishAnswer request = JsonUtils.convertValueLenient(input, FinishAnswer.class);
    if (request.getTimeWindow() instanceof FinishAnswerTimeWindow window
        && nullOrEmpty(window.getPhrase())
        && nullOrEmpty(window.getStart())) {
      request.setTimeWindow(null);
    }
    return request;
  }

  private static void requireText(final Map<String, Object> input, final String key) {
    if (!(input.get(key) instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("'" + key + "' parameter is required");
    }
  }

  private static void wrapNames(
      final Map<String, Object> input, final String key, final String field) {
    if (input.get(key) instanceof List<?> items) {
      final List<Object> wrapped = new ArrayList<>();
      for (final Object item : items) {
        wrapped.add(item instanceof String name ? Map.of(field, name) : item);
      }
      input.put(key, wrapped);
    }
  }

  private static void wrapName(
      final Map<String, Object> input, final String key, final String field) {
    if (input.get(key) instanceof String name) {
      input.put(key, Map.of(field, name));
    }
  }

  private static AnswerCatalog catalog(
      final Authorizer authorizer, final CatalogSecurityContext securityContext) {
    final OpenMetadataBaseUrlConfiguration urls =
        SettingsCache.getSetting(
            SettingsType.OPEN_METADATA_BASE_URL_CONFIGURATION,
            OpenMetadataBaseUrlConfiguration.class);
    return new LiveAnswerCatalog(
        authorizer, securityContext, urls == null ? null : urls.getOpenMetadataUrl());
  }

  private static AnswerSettings settings() {
    AgentHarness defaultHarness = null;
    try {
      defaultHarness =
          ((AgentHarnessRepository) Entity.getEntityRepository(Entity.AGENT_HARNESS)).getDefault();
    } catch (EntityNotFoundException exception) {
      defaultHarness = null;
    }
    return AnswerSettings.of(defaultHarness);
  }
}
