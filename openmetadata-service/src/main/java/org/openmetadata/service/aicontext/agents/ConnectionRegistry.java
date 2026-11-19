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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;

/**
 * The outside MCP servers this installation can call, empty by default. A distribution registers
 * its providers at startup, as with {@link SubAgentRegistry}. Each provider is asked for its
 * connections every time, since they change while the server runs; a connection is offered to a
 * caller only when the caller's MCP Access rules leave it a tool.
 */
@Slf4j
public final class ConnectionRegistry {

  private static final ConnectionRegistry INSTANCE = new ConnectionRegistry();

  private final List<ConnectionProvider> providers = new CopyOnWriteArrayList<>();

  /** A connection and the provider that calls it. */
  public record Registered(ConnectionProvider provider, ConnectionDefinition definition) {}

  /** An empty registry; the server uses {@link #getInstance()}. */
  public ConnectionRegistry() {}

  public static ConnectionRegistry getInstance() {
    return INSTANCE;
  }

  public void register(final ConnectionProvider provider) {
    providers.add(provider);
    LOG.info("[Connections] Registered {}", provider.getClass().getSimpleName());
  }

  public void unregister(final ConnectionProvider provider) {
    providers.remove(provider);
  }

  /** Every connection usable now, in registration order; a name seen before is skipped. */
  public List<Registered> registered() {
    final List<Registered> registered = new ArrayList<>();
    final Set<String> names = new HashSet<>();
    for (final ConnectionProvider provider : providers) {
      for (final ConnectionDefinition connection : connectionsOf(provider)) {
        if (connection.name() != null && names.add(connection.name())) {
          registered.add(new Registered(provider, connection));
        }
      }
    }
    return registered;
  }

  public List<ConnectionDefinition> connections() {
    return registered().stream().map(Registered::definition).toList();
  }

  public Optional<Registered> find(final String name) {
    return registered().stream()
        .filter(entry -> entry.definition().name().equals(name))
        .findFirst();
  }

  /** A provider that cannot list its connections offers none, and the others still answer. */
  private static List<ConnectionDefinition> connectionsOf(final ConnectionProvider provider) {
    try {
      return Optional.ofNullable(provider.connections()).orElse(List.of());
    } catch (RuntimeException unreadable) {
      LOG.warn(
          "[Connections] {} could not list its connections: {}",
          provider.getClass().getSimpleName(),
          unreadable.getMessage());
      return List.of();
    }
  }
}
