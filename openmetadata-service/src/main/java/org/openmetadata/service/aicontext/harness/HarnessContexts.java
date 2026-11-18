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
package org.openmetadata.service.aicontext.harness;

import jakarta.ws.rs.core.SecurityContext;
import java.util.ArrayList;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.service.aicontext.PersonaContextAccess;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.AgentHarnessRepository;

/**
 * The context definition a persona's members receive: the published default's rules followed by
 * the persona's. The persona context cache and the MCP search scope read it, so publishing the
 * default changes the definition, and with it the cache key, of every persona.
 */
@Slf4j
public final class HarnessContexts {
  private final AgentHarnessRepository harnesses;

  public HarnessContexts(final AgentHarnessRepository harnesses) {
    this.harnesses = harnesses;
  }

  public PersonaContextDefinition definitionFor(final Persona persona) {
    final AgentHarness defaultHarness = harnesses.getDefault();
    final AgentHarness personaHarness =
        isDefaultStandIn(persona, defaultHarness)
            ? null
            : harnesses.findByPersona(persona.getId()).orElse(null);
    return merged(defaultHarness, personaHarness);
  }

  /** The caller's active persona: the one they asked for, else their default persona. */
  public static Optional<Persona> activePersona(final SecurityContext securityContext) {
    Optional<Persona> persona = Optional.empty();
    try {
      persona = Optional.of(PersonaContextAccess.activePersona(securityContext));
    } catch (EntityNotFoundException exception) {
      LOG.debug("No active persona; the caller receives the default harness alone");
    }
    return persona;
  }

  /** The persona whose context document the caller reads: their own, else the default's. */
  public Persona documentPersona(final SecurityContext securityContext) {
    return activePersona(securityContext).orElseGet(this::defaultStandIn);
  }

  /**
   * The persona that stands for the company default when a caller has no persona, so the default
   * harness's rules are built and cached like a persona's. It carries the default harness's id.
   */
  public Persona defaultStandIn() {
    final AgentHarness defaultHarness = harnesses.getDefault();
    return new Persona()
        .withId(defaultHarness.getId())
        .withName(defaultHarness.getName())
        .withFullyQualifiedName(defaultHarness.getFullyQualifiedName())
        .withDisplayName(defaultHarness.getDisplayName());
  }

  /** The merged definition. Disabled when the default is disabled, because then nothing is delivered. */
  public static PersonaContextDefinition merged(
      final AgentHarness defaultHarness, final AgentHarness personaHarness) {
    return HarnessResolver.effective(defaultHarness, personaHarness)
        .map(spec -> spec.getContext().withEnabled(true))
        .orElseGet(
            () -> new PersonaContextDefinition().withEnabled(false).withRules(new ArrayList<>()));
  }

  private static boolean isDefaultStandIn(
      final Persona persona, final AgentHarness defaultHarness) {
    return defaultHarness.getId().equals(persona.getId());
  }
}
