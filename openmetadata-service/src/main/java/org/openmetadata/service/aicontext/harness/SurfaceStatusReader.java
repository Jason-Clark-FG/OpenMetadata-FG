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

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.ToLongBiFunction;
import org.openmetadata.schema.api.context.AgentHarnessSurface;
import org.openmetadata.schema.api.context.AgentHarnessSurfaceId;
import org.openmetadata.schema.api.context.AgentHarnessSurfaceStatus;
import org.openmetadata.schema.api.context.AgentHarnessSurfaces;

/**
 * Which AI surfaces have called OpenMetadata lately. A surface is matched by the client label
 * {@code AuthEnrichedMcpContextExtractor} derives from the User-Agent of each MCP call. The label
 * is self-declared, so the status is a usage signal and not attestation.
 */
public final class SurfaceStatusReader {
  static final Duration WINDOW = Duration.ofDays(7);

  /** A surface, the channels it supports and the client-label patterns (SQL LIKE) that name it. */
  record Surface(
      AgentHarnessSurfaceId id,
      String name,
      boolean skills,
      boolean connector,
      boolean hooks,
      List<String> labelPatterns) {}

  static final List<Surface> SURFACES =
      List.of(
          new Surface(
              AgentHarnessSurfaceId.CLAUDE_CHAT,
              "Claude chat",
              true,
              true,
              false,
              List.of("Claude-User%", "Claude.ai%")),
          new Surface(
              AgentHarnessSurfaceId.CLAUDE_DESKTOP,
              "Claude Desktop",
              true,
              true,
              false,
              List.of("Claude Desktop")),
          new Surface(
              AgentHarnessSurfaceId.COWORK,
              "Cowork",
              true,
              true,
              true,
              List.of("Cowork%", "Claude Cowork%")),
          new Surface(
              AgentHarnessSurfaceId.CLAUDE_CODE,
              "Claude Code",
              true,
              true,
              true,
              List.of("Claude CLI")),
          new Surface(AgentHarnessSurfaceId.CODEX, "Codex", true, true, false, List.of("Codex%")),
          new Surface(
              AgentHarnessSurfaceId.CHAT_GPT,
              "ChatGPT",
              false,
              true,
              false,
              List.of("Openai-mcp%", "ChatGPT%")));

  private SurfaceStatusReader() {}

  /**
   * @param latestCall the epoch millis of the latest call after the given time whose label matches
   *     the given pattern, or a negative value when there was none
   */
  public static AgentHarnessSurfaces read(
      final long now, final ToLongBiFunction<String, Long> latestCall) {
    final long since = now - WINDOW.toMillis();
    return new AgentHarnessSurfaces()
        .withSurfaces(
            SURFACES.stream().map(surface -> status(surface, since, latestCall)).toList());
  }

  private static AgentHarnessSurface status(
      final Surface surface, final long since, final ToLongBiFunction<String, Long> latestCall) {
    final OptionalLong lastSeen =
        surface.labelPatterns().stream()
            .mapToLong(pattern -> latestCall.applyAsLong(pattern, since))
            .filter(timestamp -> timestamp > 0)
            .max();
    return new AgentHarnessSurface()
        .withId(surface.id())
        .withName(surface.name())
        .withSkills(surface.skills())
        .withConnector(surface.connector())
        .withHooks(surface.hooks())
        .withStatus(
            lastSeen.isPresent()
                ? AgentHarnessSurfaceStatus.CONNECTED
                : AgentHarnessSurfaceStatus.NOT_CONNECTED)
        .withLastSeenAt(lastSeen.isPresent() ? lastSeen.getAsLong() : null);
  }
}
