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
package org.openmetadata.mcp.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.rdf.explore.SearchHit;

/**
 * The dotted names a question spells, such as {@code raw.raw_orders.ordered_at}, read as the end of
 * a fully qualified name. Each name and, for one of three segments or more, the name without its
 * last segment (the table of a named column) is a candidate, shorter first; an asset whose
 * qualified name is a candidate or ends with "." and one matches it.
 */
record QualifiedNames(List<String> candidates) {

  private static final Pattern DOTTED =
      Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_\\-]*(?:\\.[A-Za-z0-9_][A-Za-z0-9_\\-]*)+");
  private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
  private static final String FQN = "fullyQualifiedName";
  private static final String ENTITY_TYPE = "entityType";
  private static final String SEPARATOR = ".";

  static QualifiedNames in(final String question) {
    final Set<String> candidates = new LinkedHashSet<>();
    DOTTED
        .matcher(question)
        .results()
        .map(match -> match.group().toLowerCase(Locale.ROOT))
        .filter(name -> LETTER.matcher(name).find())
        .filter(name -> Arrays.stream(name.split("\\.")).allMatch(segment -> segment.length() > 1))
        .forEach(name -> candidates.addAll(prefixes(name)));
    return new QualifiedNames(List.copyOf(candidates));
  }

  private static List<String> prefixes(final String name) {
    final String[] segments = name.split("\\.");
    final List<String> prefixes = new ArrayList<>();
    if (segments.length > 2) {
      prefixes.add(String.join(SEPARATOR, Arrays.copyOf(segments, segments.length - 1)));
    }
    prefixes.add(name);
    return prefixes;
  }

  boolean isEmpty() {
    return candidates.isEmpty();
  }

  /** The search filter for assets of {@code entityTypes} (any when empty) matching a candidate. */
  String queryFilter(final List<String> entityTypes) {
    final List<Object> should = new ArrayList<>();
    candidates.forEach(
        candidate -> {
          should.add(Map.of("term", Map.of(FQN, candidate)));
          should.add(Map.of("wildcard", Map.of(FQN, Map.of("value", "*." + candidate))));
        });
    final List<Object> filter =
        entityTypes.isEmpty()
            ? List.of()
            : List.of(Map.of("terms", Map.of(ENTITY_TYPE, entityTypes)));
    return JsonUtils.pojoToJson(
        Map.of(
            "query",
            Map.of("bool", Map.of("should", should, "minimum_should_match", 1, "filter", filter))));
  }

  /**
   * The exact hits first, by the candidate they match (an asset before its named column), in the
   * order {@code ranked} gives them, then the ranked hits that are not exact.
   */
  List<SearchHit> first(final List<SearchHit> exact, final List<SearchHit> ranked) {
    final List<SearchHit> matching = exact.stream().filter(hit -> candidateOf(hit) >= 0).toList();
    final List<SearchHit> ordered =
        matching.stream()
            .distinct()
            .sorted(
                Comparator.comparingInt(this::candidateOf)
                    .thenComparingInt(hit -> positionIn(ranked, hit)))
            .toList();
    final List<SearchHit> hits = new ArrayList<>(ordered);
    ranked.stream().filter(hit -> !isSameAsset(ordered, hit)).forEach(hits::add);
    return hits;
  }

  private int candidateOf(final SearchHit hit) {
    final String name =
        hit.fullyQualifiedName() == null ? "" : hit.fullyQualifiedName().toLowerCase(Locale.ROOT);
    return IntStream.range(0, candidates.size())
        .filter(
            index ->
                name.equals(candidates.get(index))
                    || name.endsWith(SEPARATOR + candidates.get(index)))
        .findFirst()
        .orElse(-1);
  }

  private static int positionIn(final List<SearchHit> ranked, final SearchHit hit) {
    final int position =
        IntStream.range(0, ranked.size())
            .filter(index -> isSame(ranked.get(index), hit))
            .findFirst()
            .orElse(-1);
    return position < 0 ? Integer.MAX_VALUE : position;
  }

  private static boolean isSameAsset(final List<SearchHit> exact, final SearchHit hit) {
    return exact.stream().anyMatch(candidate -> isSame(candidate, hit));
  }

  private static boolean isSame(final SearchHit first, final SearchHit second) {
    return first.entityType().equals(second.entityType())
        && first.fullyQualifiedName() != null
        && first.fullyQualifiedName().equalsIgnoreCase(second.fullyQualifiedName());
  }
}
