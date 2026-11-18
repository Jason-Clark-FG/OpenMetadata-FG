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

package org.openmetadata.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.openmetadata.service.apps.InvalidMcpConfigurationException;

/**
 * The MCP tools a deployment offers, from {@code mcpOfferedTools} ({@code MCP_OFFERED_TOOLS}): a
 * comma-separated list of tool names, read once at start. Blank offers every tool. A name this
 * server does not register stops the start, so a typo never leaves a server offering nothing.
 */
final class OfferedTools {

  static final OfferedTools ALL = new OfferedTools(Set.of());
  static final String KNOWLEDGE_GRAPH = "knowledge_graph";
  private static final String SETTING = "mcpOfferedTools (MCP_OFFERED_TOOLS)";
  private static final String INSTRUCTIONS = "json/data/mcp/knowledge-graph-instructions.txt";

  private final Set<String> names;

  private OfferedTools(final Set<String> names) {
    this.names = Set.copyOf(names);
  }

  static OfferedTools parse(final String setting, final Collection<String> registered) {
    final Set<String> names =
        Arrays.stream(setting == null ? new String[0] : setting.split(","))
            .map(String::strip)
            .filter(name -> !name.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    final List<String> unknown = names.stream().filter(name -> !registered.contains(name)).toList();
    if (!unknown.isEmpty()) {
      throw new InvalidMcpConfigurationException(
          "%s names tool(s) this server does not register: %s. It registers: %s. The knowledge-graph tools are registered only when rdf.enabled is true."
              .formatted(SETTING, unknown, new TreeSet<>(registered)));
    }
    return new OfferedTools(names);
  }

  boolean offers(final String tool) {
    return names.isEmpty() || names.contains(tool);
  }

  Optional<String> refusal(final String tool) {
    return offers(tool)
        ? Optional.empty()
        : Optional.of(
            "%s is not offered by this MCP server; it offers %s."
                .formatted(tool, new TreeSet<>(names)));
  }

  /** What initialize tells a client when knowledge_graph is the only tool on offer. */
  Optional<String> instructions() {
    return names.equals(Set.of(KNOWLEDGE_GRAPH))
        ? Optional.of(Instructions.TEXT)
        : Optional.empty();
  }

  boolean isRestricted() {
    return !names.isEmpty();
  }

  Set<String> names() {
    return names;
  }

  private static final class Instructions {
    private static final String TEXT = load();

    private static String load() {
      try (InputStream input =
          OfferedTools.class.getClassLoader().getResourceAsStream(INSTRUCTIONS)) {
        if (input == null) {
          throw new IllegalStateException("Missing classpath resource " + INSTRUCTIONS);
        }
        return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
      } catch (IOException failure) {
        throw new UncheckedIOException("Cannot read " + INSTRUCTIONS, failure);
      }
    }
  }
}
