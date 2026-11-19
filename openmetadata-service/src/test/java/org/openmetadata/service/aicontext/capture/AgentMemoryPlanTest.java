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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.entity.context.AgentHarnessMemory;
import org.openmetadata.schema.entity.context.AgentMemoryReview;
import org.openmetadata.schema.entity.context.AgentMemoryVisibility;
import org.openmetadata.schema.entity.context.MemoryShareRole;
import org.openmetadata.schema.type.EntityReference;

class AgentMemoryPlanTest {
  private static final EntityReference PERSONA =
      new EntityReference().withId(UUID.randomUUID()).withType("persona").withName("analyst");

  @Test
  void stewardReviewKeepsTheVisibilityTheSettingsName() {
    assertEquals(
        "Private",
        plan(AgentMemoryReview.STEWARD_REVIEW, AgentMemoryVisibility.PRIVATE, PERSONA)
            .shareConfig()
            .getVisibility()
            .value());
    assertEquals(
        "Public",
        plan(AgentMemoryReview.STEWARD_REVIEW, AgentMemoryVisibility.ORGANIZATION, PERSONA)
            .shareConfig()
            .getVisibility()
            .value());
    final AgentMemoryPlan shared =
        plan(AgentMemoryReview.STEWARD_REVIEW, AgentMemoryVisibility.PERSONA, PERSONA);
    assertEquals("Shared", shared.shareConfig().getVisibility().value());
    assertEquals(1, shared.shareConfig().getSharedWith().size());
    assertEquals(PERSONA, shared.shareConfig().getSharedWith().getFirst().getPrincipal());
    assertEquals(MemoryShareRole.VIEWER, shared.shareConfig().getSharedWith().getFirst().getRole());
    assertNull(shared.note());
  }

  @Test
  void authorOnlyForcesPrivateWhateverTheVisibility() {
    for (final AgentMemoryVisibility visibility : AgentMemoryVisibility.values()) {
      final AgentMemoryPlan plan = plan(AgentMemoryReview.AUTHOR_ONLY, visibility, PERSONA);
      assertEquals("Private", plan.shareConfig().getVisibility().value());
      assertEquals(List.of(), plan.shareConfig().getSharedWith());
      assertNull(plan.note());
    }
  }

  @Test
  void personaVisibilityWithoutAPersonaFallsBackToPrivateAndSaysSo() {
    final AgentMemoryPlan plan =
        plan(AgentMemoryReview.STEWARD_REVIEW, AgentMemoryVisibility.PERSONA, null);

    assertEquals("Private", plan.shareConfig().getVisibility().value());
    assertEquals(List.of(), plan.shareConfig().getSharedWith());
    assertEquals(AgentMemoryPlan.NO_PERSONA_NOTE, plan.note());
  }

  @Test
  void captureOffYieldsNoPlan() {
    final AgentHarnessMemory settings =
        settings(AgentMemoryReview.STEWARD_REVIEW, AgentMemoryVisibility.PRIVATE)
            .withCapture(false);

    assertEquals(Optional.empty(), AgentMemoryPlan.of(settings, PERSONA));
    assertEquals(Optional.empty(), AgentMemoryPlan.of(null, PERSONA));
  }

  @Test
  void linkAssetsAndDedupFollowTheirSettings() {
    final AgentHarnessMemory off =
        settings(AgentMemoryReview.STEWARD_REVIEW, AgentMemoryVisibility.PRIVATE)
            .withLinkAssets(false)
            .withDedup(false);

    final AgentMemoryPlan plan = AgentMemoryPlan.of(off, PERSONA).orElseThrow();

    assertFalse(plan.linkAssets());
    assertFalse(plan.dedup());
    assertTrue(
        plan(AgentMemoryReview.STEWARD_REVIEW, AgentMemoryVisibility.PRIVATE, PERSONA)
            .linkAssets());
  }

  private static AgentMemoryPlan plan(
      final AgentMemoryReview review,
      final AgentMemoryVisibility visibility,
      final EntityReference persona) {
    return AgentMemoryPlan.of(settings(review, visibility), persona).orElseThrow();
  }

  private static AgentHarnessMemory settings(
      final AgentMemoryReview review, final AgentMemoryVisibility visibility) {
    return new AgentHarnessMemory()
        .withCapture(true)
        .withLinkAssets(true)
        .withDedup(true)
        .withReview(review)
        .withVisibility(visibility);
  }
}
