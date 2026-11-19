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

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.api.configuration.OpenMetadataBaseUrlConfiguration;
import org.openmetadata.schema.entity.context.ContextMemory;
import org.openmetadata.schema.entity.context.ContextMemoryScope;
import org.openmetadata.schema.entity.context.ContextMemorySourceType;
import org.openmetadata.schema.entity.context.ContextMemoryType;
import org.openmetadata.schema.entity.context.MemoryShareConfig;
import org.openmetadata.schema.entity.context.MemorySharedPrincipal;
import org.openmetadata.schema.settings.SettingsType;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.capture.AgentMemoryDuplicates;
import org.openmetadata.service.aicontext.capture.AgentMemoryPlan;
import org.openmetadata.service.aicontext.harness.AgentHarnessCompiler.AppliedMemory;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.ContextMemoryRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.resources.context.ContextMemoryWriteAccess;
import org.openmetadata.service.resources.settings.SettingsCache;
import org.openmetadata.service.rules.RuleEngine;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.EntityUrls;
import org.openmetadata.service.util.EntityUtil;

/**
 * Records a fact the user stated about their data as a context memory linked to the assets it is
 * about, as the caller. The caller's agent harness decides whether capture is on, who may read the
 * memory, whether the session's assets are linked, and whether a restatement is merged
 * (docs/plans/2026-11-18-agent-harness-implementation-plan.md section 2.4). The memory lifecycle
 * decides its approval, as for any memory the caller creates.
 */
public class RememberTool {
  /** Set by the server from the transport, replacing anything the caller sent. */
  public static final String CONVERSATION_PARAM = "_conversation";

  public static final String CLIENT_PARAM = "_client";

  static final int MAX_FACT_CHARS = 2000;
  static final int MAX_TITLE_CHARS = 200;
  static final int MAX_ABOUT = 10;
  static final int MAX_LINKED = 20;
  private static final int DERIVED_TITLE_CHARS = 80;
  private static final Pattern RECORDED_BY =
      Pattern.compile("Recorded by (.+) through the remember tool\\.");
  private static final String CAPTURE_OFF =
      "Memory capture is turned off for your agent harness, so nothing was saved. Tell the user"
          + " if they asked you to remember something.";

  public McpSchema.CallToolResult call(
      final Authorizer authorizer,
      final Limits limits,
      final CatalogSecurityContext securityContext,
      final Map<String, Object> params) {
    return run(authorizer, limits, securityContext, Request.parse(params));
  }

  /**
   * Saves a fact a hook read from the user's prompt, under the same settings, dedup and linking as
   * a fact the model records. The memory says it was recorded automatically.
   */
  public McpSchema.CallToolResult captureFromPrompt(
      final Authorizer authorizer,
      final Limits limits,
      final CatalogSecurityContext securityContext,
      final String fact,
      final List<SessionAssets.Asset> about,
      final String conversation,
      final String client) {
    return run(
        authorizer,
        limits,
        securityContext,
        new Request(
            fact, null, null, ContextMemoryType.LEARNING, about, conversation, client, true));
  }

  private McpSchema.CallToolResult run(
      final Authorizer authorizer,
      final Limits limits,
      final CatalogSecurityContext securityContext,
      final Request request) {
    final String user = CommonUtils.principal(securityContext);
    final Optional<AppliedMemory> applied =
        GetContextTool.compiler(authorizer).memory(securityContext);
    final Optional<AgentMemoryPlan> plan =
        applied.flatMap(
            memory ->
                AgentMemoryPlan.of(
                    memory.settings(),
                    memory.persona() == null ? null : memory.persona().getEntityReference()));
    return plan.isEmpty()
        ? refused()
        : capture(authorizer, limits, securityContext, user, request, plan.get());
  }

  private static McpSchema.CallToolResult refused() {
    final Map<String, Object> structured = new LinkedHashMap<>();
    structured.put("captured", false);
    structured.put("reason", CAPTURE_OFF);
    return result(CAPTURE_OFF, structured);
  }

  private static McpSchema.CallToolResult capture(
      final Authorizer authorizer,
      final Limits limits,
      final CatalogSecurityContext securityContext,
      final String user,
      final Request request,
      final AgentMemoryPlan plan) {
    final Links links = plan.linkAssets() ? links(user, request) : Links.NONE;
    final ContextMemoryRepository repository =
        (ContextMemoryRepository) Entity.getEntityRepository(Entity.CONTEXT_MEMORY);
    final Optional<ContextMemory> existing =
        plan.dedup()
            ? AgentMemoryDuplicates.find(repository, links.primary(), request.fact(), user)
            : Optional.empty();
    final McpSchema.CallToolResult outcome;
    if (existing.isPresent()) {
      outcome = deduplicated(existing.get(), links, plan);
    } else {
      final ContextMemory memory = build(request, links, plan, user);
      CreateEntityTool.authorizeCreate(
          authorizer, limits, securityContext, Entity.CONTEXT_MEMORY, memory);
      RuleEngine.getInstance().evaluate(memory);
      final EntityInterface<?> saved =
          CreateEntityTool.persist(EntityCreationSpec.resolve(Entity.CONTEXT_MEMORY), memory, user)
              .getEntity();
      outcome = created((ContextMemory) saved, links, plan);
    }
    return outcome;
  }

  private static ContextMemory build(
      final Request request, final Links links, final AgentMemoryPlan plan, final String user) {
    final UUID id = UUID.randomUUID();
    final String title = request.title() != null ? request.title() : derivedTitle(request.fact());
    final ContextMemory memory =
        new ContextMemory()
            .withId(id)
            .withName("agent-memory-" + id)
            .withTitle(title)
            .withQuestion(request.question() != null ? request.question() : title)
            .withAnswer(request.fact())
            .withMemoryType(request.memoryType())
            .withMemoryScope(
                links.primary() == null
                    ? ContextMemoryScope.USER_GLOBAL
                    : ContextMemoryScope.ENTITY_SCOPED)
            .withShareConfig(plan.shareConfig())
            .withPrimaryEntity(links.primary())
            .withRelatedEntities(links.related())
            .withSourceType(ContextMemorySourceType.AGENT_CAPTURE);
    memory.setDescription(describe(request));
    memory.setUpdatedBy(user);
    memory.setUpdatedAt(System.currentTimeMillis());
    return memory;
  }

  private static String describe(final Request request) {
    final String by = request.client() == null ? "an agent" : request.client();
    String description = null;
    if (request.automatic()) {
      description = "Recorded automatically from the user's prompt by " + by + ".";
    } else if (request.client() != null) {
      description = "Recorded by " + by + " through the remember tool.";
    }
    return description;
  }

  private static McpSchema.CallToolResult created(
      final ContextMemory memory, final Links links, final AgentMemoryPlan plan) {
    final Map<String, Object> structured = base(memory, links, plan);
    structured.put("captured", true);
    return result(summary("Saved", memory, links, plan), structured);
  }

  private static McpSchema.CallToolResult deduplicated(
      final ContextMemory existing, final Links links, final AgentMemoryPlan plan) {
    final Map<String, Object> structured = base(existing, links, plan);
    structured.put("captured", false);
    structured.put("deduplicatedInto", existing.getId().toString());
    return result(
        summary("Already remembered, so nothing new was saved", existing, links, plan), structured);
  }

  private static Map<String, Object> base(
      final ContextMemory memory, final Links links, final AgentMemoryPlan plan) {
    final Map<String, Object> structured = new LinkedHashMap<>();
    structured.put("id", memory.getId().toString());
    structured.put("title", memory.getTitle());
    structured.put(
        "status", memory.getEntityStatus() == null ? null : memory.getEntityStatus().value());
    structured.put(
        "visibility",
        memory.getShareConfig() == null
            ? plan.shareConfig().getVisibility().value()
            : memory.getShareConfig().getVisibility().value());
    structured.put("fact", memory.getAnswer());
    structured.put("savedAt", memory.getUpdatedAt());
    structured.put("sharedWith", sharedWith(memory, plan));
    final Matcher recorder =
        memory.getDescription() == null ? null : RECORDED_BY.matcher(memory.getDescription());
    if (recorder != null && recorder.matches()) {
      structured.put("client", recorder.group(1));
    }
    structured.put("linkedAssets", links.linked());
    if (!links.skipped().isEmpty()) {
      structured.put("skipped", links.skipped());
    }
    if (plan.note() != null) {
      structured.put("note", plan.note());
    }
    structured.put("url", memoryUrl(memory));
    return structured;
  }

  private static List<String> sharedWith(final ContextMemory memory, final AgentMemoryPlan plan) {
    final MemoryShareConfig config =
        memory.getShareConfig() == null ? plan.shareConfig() : memory.getShareConfig();
    return listOrEmpty(config.getSharedWith()).stream()
        .map(MemorySharedPrincipal::getPrincipal)
        .filter(Objects::nonNull)
        .map(
            principal ->
                nullOrEmpty(principal.getDisplayName())
                    ? principal.getName()
                    : principal.getDisplayName())
        .toList();
  }

  private static String summary(
      final String verb,
      final ContextMemory memory,
      final Links links,
      final AgentMemoryPlan plan) {
    final StringBuilder text =
        new StringBuilder(verb + ": " + memory.getTitle() + "\n" + memoryUrl(memory));
    if (!links.linked().isEmpty()) {
      text.append("\nLinked to: ")
          .append(
              String.join(
                  ", ", links.linked().stream().map(link -> (String) link.get("fqn")).toList()));
    }
    if (!links.skipped().isEmpty()) {
      text.append("\nNot linked, because they were not found or you cannot view them: ")
          .append(String.join(", ", links.skipped()));
    }
    if (plan.note() != null) {
      text.append("\n").append(plan.note());
    }
    return text.toString();
  }

  private static McpSchema.CallToolResult result(
      final String text, final Map<String, Object> structured) {
    return McpSchema.CallToolResult.builder()
        .content(List.of(McpSchema.TextContent.builder(text).build()))
        .structuredContent(structured)
        .isError(false)
        .build();
  }

  /** The assets the memory links to: the ones named, then the ones the conversation read. */
  private record Links(
      EntityReference primary,
      List<EntityReference> related,
      List<Map<String, Object>> linked,
      List<String> skipped) {
    static final Links NONE = new Links(null, List.of(), List.of(), List.of());
  }

  private static Links links(final String user, final Request request) {
    final List<EntityReference> resolved = new ArrayList<>();
    final List<Map<String, Object>> linked = new ArrayList<>();
    final List<String> skipped = new ArrayList<>();
    final Set<UUID> taken = new HashSet<>();
    final List<SessionAssets.Asset> seen =
        request.conversation() == null
            ? List.of()
            : SessionAssets.shared().seen(request.conversation());
    final List<SessionAssets.Asset> candidates = new ArrayList<>(request.about());
    for (int index = seen.size() - 1; index >= 0; index--) {
      candidates.add(seen.get(index));
    }
    for (final SessionAssets.Asset candidate : candidates) {
      if (resolved.size() < MAX_LINKED) {
        final Optional<EntityInterface<?>> entity = viewable(user, candidate);
        if (entity.isEmpty() && request.about().contains(candidate)) {
          skipped.add(candidate.fullyQualifiedName());
        } else if (entity.isPresent() && taken.add(entity.get().getId())) {
          resolved.add(entity.get().getEntityReference());
          linked.add(linkOf(candidate.entityType(), entity.get()));
        }
      }
    }
    final boolean namedAnchor =
        !resolved.isEmpty()
            && !request.about().isEmpty()
            && linked.getFirst().get("fqn").equals(request.about().getFirst().fullyQualifiedName());
    final EntityReference primary = namedAnchor ? resolved.getFirst() : null;
    final List<EntityReference> related =
        namedAnchor ? resolved.subList(1, resolved.size()) : resolved;
    return new Links(primary, related, linked, skipped);
  }

  private static Optional<EntityInterface<?>> viewable(
      final String user, final SessionAssets.Asset asset) {
    Optional<EntityInterface<?>> found = lookup(asset.entityType(), asset.fullyQualifiedName());
    if (found.isEmpty() && Entity.GLOSSARY_TERM.equals(asset.entityType())) {
      found = lookup(Entity.METRIC, asset.fullyQualifiedName());
    }
    return found.filter(
        entity -> ContextMemoryWriteAccess.canViewReference(user, entity.getEntityReference()));
  }

  private static Optional<EntityInterface<?>> lookup(final String entityType, final String fqn) {
    Optional<EntityInterface<?>> found = Optional.empty();
    if (Entity.hasEntityRepository(entityType)) {
      try {
        found = Optional.of(Entity.getEntityByName(entityType, fqn, "", Include.NON_DELETED));
      } catch (EntityNotFoundException missing) {
        found = Optional.empty();
      }
    }
    return found;
  }

  private static Map<String, Object> linkOf(
      final String requestedType, final EntityInterface<?> entity) {
    final String type = entity.getEntityReference().getType();
    final Map<String, Object> link = new LinkedHashMap<>();
    link.put("fqn", entity.getFullyQualifiedName());
    link.put("type", type == null ? requestedType : type);
    link.put("url", assetUrl(type == null ? requestedType : type, entity));
    return link;
  }

  private static String assetUrl(final String type, final EntityInterface<?> entity) {
    final String base = baseUrl();
    return EntityUrls.buildEntityUrl(
        type,
        entity,
        (prefix, fqn, extra) ->
            base
                + "/"
                + prefix
                + "/"
                + EntityUtil.encodeEntityFqnSafe(fqn)
                + (nullOrEmpty(extra) ? "" : "/" + extra));
  }

  private static String memoryUrl(final ContextMemory memory) {
    return baseUrl()
        + "/context-center/memories?memory="
        + EntityUtil.encodeEntityFqnSafe(memory.getName());
  }

  private static String baseUrl() {
    final OpenMetadataBaseUrlConfiguration urls =
        SettingsCache.getSetting(
            SettingsType.OPEN_METADATA_BASE_URL_CONFIGURATION,
            OpenMetadataBaseUrlConfiguration.class);
    return urls == null || urls.getOpenMetadataUrl() == null
        ? ""
        : urls.getOpenMetadataUrl().replaceAll("/+$", "");
  }

  static String derivedTitle(final String fact) {
    final String line = fact.strip().replaceAll("\\s+", " ");
    return line.length() <= DERIVED_TITLE_CHARS
        ? line
        : line.substring(0, DERIVED_TITLE_CHARS).replaceAll("\\s+\\S*$", "") + "...";
  }

  /** The arguments, checked. */
  record Request(
      String fact,
      String title,
      String question,
      ContextMemoryType memoryType,
      List<SessionAssets.Asset> about,
      String conversation,
      String client,
      boolean automatic) {

    static Request parse(final Map<String, Object> params) {
      final Object fact = params.get("fact");
      if (!(fact instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("'fact' parameter is required");
      }
      if (text.length() > MAX_FACT_CHARS) {
        throw new IllegalArgumentException(
            "'fact' is " + text.length() + " characters; the limit is " + MAX_FACT_CHARS);
      }
      return new Request(
          text.strip(),
          optionalText(params, "title", MAX_TITLE_CHARS),
          optionalText(params, "question", MAX_TITLE_CHARS * 2),
          memoryType(params.get("memoryType")),
          about(params.get("about")),
          params.get(CONVERSATION_PARAM) instanceof String key ? key : null,
          optionalText(params, CLIENT_PARAM, 100),
          false);
    }

    private static String optionalText(
        final Map<String, Object> params, final String key, final int max) {
      final Object value = params.get(key);
      String text = null;
      if (value instanceof String raw && !raw.isBlank()) {
        text = raw.strip();
        if (text.length() > max) {
          throw new IllegalArgumentException("'" + key + "' is longer than " + max + " characters");
        }
      }
      return text;
    }

    private static ContextMemoryType memoryType(final Object raw) {
      if (!(raw instanceof String value) || value.isBlank()) {
        return ContextMemoryType.LEARNING;
      }
      try {
        return ContextMemoryType.fromValue(value.strip());
      } catch (IllegalArgumentException unknown) {
        throw new IllegalArgumentException(
            "'memoryType' must be one of "
                + Arrays.stream(ContextMemoryType.values()).map(ContextMemoryType::value).toList());
      }
    }

    /** Models send a bare name where an object is expected, so a name alone is read as a table. */
    private static List<SessionAssets.Asset> about(final Object raw) {
      final List<SessionAssets.Asset> about = new ArrayList<>();
      if (raw instanceof List<?> items) {
        if (items.size() > MAX_ABOUT) {
          throw new IllegalArgumentException("'about' takes at most " + MAX_ABOUT + " assets");
        }
        for (final Object item : items) {
          final SessionAssets.Asset asset = asset(item);
          if (asset != null && !about.contains(asset)) {
            about.add(asset);
          }
        }
      }
      return about;
    }

    private static SessionAssets.Asset asset(final Object item) {
      SessionAssets.Asset asset = null;
      if (item instanceof String name && !name.isBlank()) {
        asset = new SessionAssets.Asset(Entity.TABLE, name.strip());
      } else if (item instanceof Map<?, ?> map && map.get("fqn") instanceof String fqn) {
        final Object type = map.get("entityType");
        asset =
            new SessionAssets.Asset(
                type instanceof String entityType && !entityType.isBlank()
                    ? entityType.strip()
                    : Entity.TABLE,
                fqn.strip());
      }
      return asset;
    }
  }
}
