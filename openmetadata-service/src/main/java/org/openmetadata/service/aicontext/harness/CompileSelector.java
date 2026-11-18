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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.openmetadata.schema.api.context.CompiledSegment;

/**
 * Chooses what each hook event delivers and packs it into the event's token budget. Pure: the
 * compiler hands it the caller's document and the event's input.
 */
public final class CompileSelector {
  private static final int CHARS_PER_TOKEN = 4;
  private static final int MIN_NAME_LENGTH = 3;
  private static final String ITEM_SEPARATOR = "\n\n";

  /** The packed text, its size, the size of each kind of item, and the items it holds. */
  public record Packed(
      String text, int tokens, List<CompiledSegment> segments, List<ContextItem> items) {}

  /**
   * What a caller's agent can receive. The fixed items are delivered at SessionStart; the
   * knowledge items are delivered when they are always in context or when an event names them.
   */
  public record Document(List<ContextItem> fixed, List<ContextItem> knowledge) {
    public Document {
      fixed = List.copyOf(fixed);
      knowledge = List.copyOf(knowledge);
    }
  }

  private CompileSelector() {}

  public static List<ContextItem> sessionStart(final Document document) {
    return Stream.concat(
            document.fixed().stream(),
            document.knowledge().stream().filter(ContextItem::alwaysInContext))
        .toList();
  }

  /** Items the prompt names, then the always-in-context items this session has not received. */
  public static List<ContextItem> userPromptSubmit(
      final Document document, final String prompt, final Set<String> sentKeys) {
    return Stream.concat(
            named(document.knowledge(), prompt).stream(),
            document.knowledge().stream()
                .filter(ContextItem::alwaysInContext)
                .filter(item -> !sentKeys.contains(item.key())))
        .toList();
  }

  /** Guardrail notes first, then the cards of the assets the tool input names. */
  public static List<ContextItem> preToolUse(
      final Document document, final String toolInput, final List<ContextItem> guardrailNotes) {
    return Stream.concat(
            guardrailNotes.stream(),
            named(document.knowledge(), toolInput).stream()
                .filter(item -> ContextItem.ASSETS.equals(item.kind())))
        .toList();
  }

  /** The items whose names appear in {@code text} as whole words, ignoring case. */
  public static List<ContextItem> named(final List<ContextItem> items, final String text) {
    final String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);
    return haystack.isBlank()
        ? List.of()
        : items.stream().filter(item -> isNamedIn(item, haystack)).toList();
  }

  /**
   * Packs the default's items first and the persona's after them, each in its given order. An item
   * that does not fit the remaining budget is left out, and a later, smaller one may still fit, so
   * the persona's items are the first to go when the budget runs out.
   */
  public static Packed pack(final List<ContextItem> candidates, final int budgetTokens) {
    final List<ContextItem> included = new ArrayList<>();
    final Set<String> seen = new HashSet<>();
    int used = 0;
    for (final ContextItem item : byOrigin(candidates)) {
      final int cost = tokens(item.text());
      if (seen.add(item.key()) && used + cost <= budgetTokens) {
        included.add(item);
        used += cost;
      }
    }
    return new Packed(text(included), used, segments(included), List.copyOf(included));
  }

  public static int tokens(final String text) {
    return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
  }

  private static List<ContextItem> byOrigin(final List<ContextItem> candidates) {
    return candidates.stream().sorted(Comparator.comparing(ContextItem::origin)).toList();
  }

  private static String text(final List<ContextItem> items) {
    return String.join(ITEM_SEPARATOR, items.stream().map(ContextItem::text).toList());
  }

  private static List<CompiledSegment> segments(final List<ContextItem> items) {
    final Map<String, Integer> byKind = new LinkedHashMap<>();
    for (final ContextItem item : items) {
      byKind.merge(item.kind(), tokens(item.text()), Integer::sum);
    }
    return byKind.entrySet().stream()
        .map(entry -> new CompiledSegment().withKind(entry.getKey()).withTokens(entry.getValue()))
        .toList();
  }

  private static boolean isNamedIn(final ContextItem item, final String haystack) {
    return item.names().stream()
        .filter(name -> name != null && name.length() >= MIN_NAME_LENGTH)
        .map(name -> name.toLowerCase(Locale.ROOT))
        .anyMatch(name -> haystack.contains(name) && isWholeWord(haystack, name));
  }

  private static boolean isWholeWord(final String haystack, final String name) {
    return Pattern.compile("(?<![\\w])" + Pattern.quote(name) + "(?![\\w])")
        .matcher(haystack)
        .find();
  }
}
