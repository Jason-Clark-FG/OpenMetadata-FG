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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.openmetadata.service.Entity;

/**
 * The assets a conversation has read, so a memory the agent records can be linked to them. Only
 * tools that name the assets they used count: reading an entity, a concept's context, and the
 * tables and concepts a finished answer cites. A search result lists candidates the agent may
 * never have used, so it is not counted.
 *
 * <p>A conversation is keyed as {@link DeliveryLedger.Caller#conversationKey()}. Names only are
 * kept, and the caller's access is checked again when a memory is written.
 */
public final class SessionAssets {
  static final int MAX_PER_CONVERSATION = 50;
  static final int MAX_CONVERSATIONS = 10_000;
  static final Duration EXPIRY = Duration.ofHours(2);

  /** An asset by entity type and fully qualified name. */
  public record Asset(String entityType, String fullyQualifiedName) {}

  private static final SessionAssets SHARED = new SessionAssets();

  private final Cache<String, LinkedHashSet<Asset>> conversations =
      Caffeine.newBuilder().maximumSize(MAX_CONVERSATIONS).expireAfterAccess(EXPIRY).build();

  public static SessionAssets shared() {
    return SHARED;
  }

  /** Notes the assets a successful call to {@code tool} with {@code params} used. */
  public void observe(
      final DeliveryLedger.Caller caller, final String tool, final Map<String, Object> params) {
    if (params == null) {
      return;
    }
    switch (tool) {
      case "get_entity_details", "get_concept_context" -> add(
          caller, params.get("entityType"), params.get("fqn"));
      case "finish_answer" -> {
        namesIn(params.get("concepts"))
            .forEach(item -> add(caller, item.get("entityType"), item.get("fqn")));
        namesIn(params.get("tables")).forEach(item -> add(caller, "table", item.get("fqn")));
      }
      default -> {
        // The other tools do not name the assets they used.
      }
    }
  }

  /** The conversation's assets, most recently used last. */
  public List<Asset> seen(final String conversationKey) {
    final LinkedHashSet<Asset> assets = conversations.getIfPresent(conversationKey);
    if (assets == null) {
      return List.of();
    }
    synchronized (assets) {
      return new ArrayList<>(assets);
    }
  }

  private void add(final DeliveryLedger.Caller caller, final Object type, final Object fqn) {
    if (type instanceof String entityType
        && fqn instanceof String name
        && !entityType.isBlank()
        && !name.isBlank()) {
      final LinkedHashSet<Asset> assets =
          conversations.get(caller.conversationKey(), key -> new LinkedHashSet<>());
      final Asset asset = new Asset(entityType, name);
      synchronized (assets) {
        assets.remove(asset);
        assets.add(asset);
        while (assets.size() > MAX_PER_CONVERSATION) {
          assets.remove(assets.iterator().next());
        }
      }
    }
  }

  /** Models send a bare name where an object is expected, so both shapes are read. */
  private static List<Map<String, Object>> namesIn(final Object items) {
    final List<Map<String, Object>> named = new ArrayList<>();
    if (items instanceof List<?> list) {
      for (final Object item : list) {
        if (item instanceof String name) {
          named.add(Map.of("fqn", name, "entityType", Entity.GLOSSARY_TERM));
        } else if (item instanceof Map<?, ?> map && map.get("fqn") instanceof String) {
          named.add(Map.of("fqn", map.get("fqn"), "entityType", typeOf(map)));
        }
      }
    }
    return named;
  }

  private static Object typeOf(final Map<?, ?> map) {
    return map.get("entityType") == null ? Entity.GLOSSARY_TERM : map.get("entityType");
  }
}
