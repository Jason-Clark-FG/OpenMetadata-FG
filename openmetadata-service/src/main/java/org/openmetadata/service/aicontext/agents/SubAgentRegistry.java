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
package org.openmetadata.service.aicontext.agents;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;

/**
 * The sub-agents this installation offers, empty by default. A distribution registers its
 * providers at startup, as with {@code SinkProviderRegistry}; an agent is offered to a caller only
 * when it is also enabled in the caller's harness.
 */
@Slf4j
public final class SubAgentRegistry {

  private static final SubAgentRegistry INSTANCE = new SubAgentRegistry();

  private final List<SubAgentProvider> providers = new CopyOnWriteArrayList<>();
  private final Map<String, SubAgentProvider> byAgent = new ConcurrentHashMap<>();

  private SubAgentRegistry() {}

  public static SubAgentRegistry getInstance() {
    return INSTANCE;
  }

  /** Adds a provider and its agents; a name another provider already has is refused. */
  public void register(final SubAgentProvider provider) {
    for (final SubAgentDefinition agent : provider.agents()) {
      final SubAgentProvider existing = byAgent.putIfAbsent(agent.name(), provider);
      if (existing != null && existing != provider) {
        throw new IllegalArgumentException(
            "Sub-agent '%s' is already registered by another provider".formatted(agent.name()));
      }
    }
    providers.add(provider);
    LOG.info(
        "[SubAgents] Registered {}",
        provider.agents().stream().map(SubAgentDefinition::name).toList());
  }

  /** Removes a provider and its agents. */
  public void unregister(final SubAgentProvider provider) {
    providers.remove(provider);
    byAgent.values().removeIf(registered -> registered == provider);
  }

  /** Every registered agent, in registration order. */
  public List<SubAgentDefinition> agents() {
    return providers.stream().flatMap(provider -> provider.agents().stream()).toList();
  }

  public Optional<SubAgentDefinition> agent(final String name) {
    return agents().stream().filter(agent -> agent.name().equals(name)).findFirst();
  }

  /** The provider that runs {@code agent}, if one is registered. */
  public Optional<SubAgentProvider> providerOf(final String agent) {
    return Optional.ofNullable(agent).map(byAgent::get);
  }
}
