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
package org.openmetadata.service.aicontext.capture;

import java.util.List;
import java.util.Optional;
import org.openmetadata.schema.entity.context.AgentHarnessMemory;
import org.openmetadata.schema.entity.context.AgentMemoryReview;
import org.openmetadata.schema.entity.context.AgentMemoryVisibility;
import org.openmetadata.schema.entity.context.MemoryShareConfig;
import org.openmetadata.schema.entity.context.MemoryShareRole;
import org.openmetadata.schema.entity.context.MemorySharedPrincipal;
import org.openmetadata.schema.entity.context.MemoryVisibility;
import org.openmetadata.schema.type.EntityReference;

/**
 * What the agent harness's memory settings make of one captured memory: whether it is taken at
 * all, which assets are linked, whether a restatement is merged, and who may read it
 * (docs/plans/2026-11-18-agent-harness-implementation-plan.md section 2.4). Approval is not
 * decided here: the deployment's memory lifecycle does that
 * (ADR:2026-11-05-oss-memories-are-approved-without-reconciliation).
 *
 * @param shareConfig who may read the memory
 * @param note why the visibility is not the one the settings name, or null
 */
public record AgentMemoryPlan(
    boolean linkAssets, boolean dedup, MemoryShareConfig shareConfig, String note) {

  static final String NO_PERSONA_NOTE =
      "No persona applies to you, so the memory is private to you instead of shared with a"
          + " persona.";

  /** Empty when the settings do not allow capture. {@code persona} is the caller's applied one. */
  public static Optional<AgentMemoryPlan> of(
      final AgentHarnessMemory settings, final EntityReference persona) {
    if (settings == null || !Boolean.TRUE.equals(settings.getCapture())) {
      return Optional.empty();
    }
    final Share share = share(settings, persona);
    return Optional.of(
        new AgentMemoryPlan(
            Boolean.TRUE.equals(settings.getLinkAssets()),
            Boolean.TRUE.equals(settings.getDedup()),
            share.config(),
            share.note()));
  }

  private record Share(MemoryShareConfig config, String note) {}

  private static Share share(final AgentHarnessMemory settings, final EntityReference persona) {
    final AgentMemoryVisibility requested =
        settings.getReview() == AgentMemoryReview.AUTHOR_ONLY
            ? AgentMemoryVisibility.PRIVATE
            : settings.getVisibility();
    return switch (requested) {
      case PRIVATE -> new Share(privateConfig(), null);
      case ORGANIZATION -> new Share(
          new MemoryShareConfig().withVisibility(MemoryVisibility.PUBLIC).withSharedWith(List.of()),
          null);
      case PERSONA -> persona == null
          ? new Share(privateConfig(), NO_PERSONA_NOTE)
          : new Share(
              new MemoryShareConfig()
                  .withVisibility(MemoryVisibility.SHARED)
                  .withSharedWith(
                      List.of(
                          new MemorySharedPrincipal()
                              .withPrincipal(persona)
                              .withRole(MemoryShareRole.VIEWER))),
              null);
    };
  }

  private static MemoryShareConfig privateConfig() {
    return new MemoryShareConfig()
        .withVisibility(MemoryVisibility.PRIVATE)
        .withSharedWith(List.of());
  }
}
