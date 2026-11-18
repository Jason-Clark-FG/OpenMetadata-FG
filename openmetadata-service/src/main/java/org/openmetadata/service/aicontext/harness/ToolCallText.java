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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.harness.ContextItem.AssetRef;

/** The text of a tool call's input or output, and the catalog assets it names. */
final class ToolCallText {
  static final int MAX_CHARS = 50_000;
  // service.database.schema.table: a table named in full, which needs no document to resolve.
  private static final Pattern TABLE_FQN =
      Pattern.compile("(?<![\\w.])[\\w$-]+(?:\\.[\\w$-]+){3}(?![\\w.])");
  private static final Pattern QUOTES = Pattern.compile("[`\"\\[\\]]");

  private ToolCallText() {}

  /** Every string value of the JSON, one per line, cut at {@link #MAX_CHARS}. */
  static String of(final JsonNode node) {
    final StringBuilder text = new StringBuilder();
    collect(node, text);
    return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text.toString();
  }

  /**
   * The assets the text names: those of the caller's document that it mentions by name, then the
   * tables it names by their fully qualified name.
   */
  static List<AssetRef> assets(final String text, final List<ContextItem> documentItems) {
    final Set<AssetRef> assets = new LinkedHashSet<>();
    CompileSelector.named(documentItems, text).stream()
        .map(ContextItem::asset)
        .filter(Objects::nonNull)
        .forEach(assets::add);
    tableNames(text).forEach(fqn -> assets.add(new AssetRef(Entity.TABLE, fqn)));
    return new ArrayList<>(assets);
  }

  private static Stream<String> tableNames(final String text) {
    final Matcher matcher = TABLE_FQN.matcher(QUOTES.matcher(text).replaceAll(""));
    final List<String> names = new ArrayList<>();
    while (matcher.find()) {
      names.add(matcher.group());
    }
    return names.stream();
  }

  private static void collect(final JsonNode node, final StringBuilder text) {
    if (node == null || text.length() > MAX_CHARS) {
      return;
    }
    if (node.isTextual()) {
      text.append(node.asText()).append('\n');
    } else if (node.isContainerNode()) {
      node.forEach(child -> collect(child, text));
    }
  }
}
