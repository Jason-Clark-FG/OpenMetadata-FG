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

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;

import java.util.List;
import java.util.Optional;
import org.openmetadata.schema.entity.context.AgentHarnessEvent;
import org.openmetadata.schema.entity.context.HookEvent;

/**
 * The hook events a harness delivers at, in the order every listing shows them, each with the
 * setting the default harness falls back to when it does not list the event.
 */
public final class HarnessEvents {
  private record Fallback(HookEvent id, boolean enabled, int tokenBudget) {
    AgentHarnessEvent setting() {
      return new AgentHarnessEvent().withId(id).withEnabled(enabled).withTokenBudget(tokenBudget);
    }
  }

  private static final List<Fallback> FALLBACKS =
      List.of(
          new Fallback(HookEvent.SESSION_START, true, 6000),
          new Fallback(HookEvent.USER_PROMPT_SUBMIT, true, 4000),
          new Fallback(HookEvent.PRE_TOOL_USE, true, 2000),
          new Fallback(HookEvent.POST_TOOL_USE, true, 1000),
          new Fallback(HookEvent.STOP, true, 1000));

  private HarnessEvents() {}

  public static List<HookEvent> ids() {
    return FALLBACKS.stream().map(Fallback::id).toList();
  }

  public static List<AgentHarnessEvent> fallbacks() {
    return FALLBACKS.stream().map(Fallback::setting).toList();
  }

  public static Optional<AgentHarnessEvent> find(
      final List<AgentHarnessEvent> events, final HookEvent id) {
    return listOrEmpty(events).stream().filter(event -> event.getId() == id).findFirst();
  }

  /** The default harness's setting of {@code id}, which is the fallback when it lists none. */
  public static AgentHarnessEvent defaultSetting(
      final List<AgentHarnessEvent> defaultEvents, final HookEvent id) {
    return find(defaultEvents, id).orElseGet(() -> fallback(id));
  }

  private static AgentHarnessEvent fallback(final HookEvent id) {
    return FALLBACKS.stream()
        .filter(fallback -> fallback.id() == id)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No fallback setting for hook event " + id))
        .setting();
  }
}
