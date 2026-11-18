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
package org.openmetadata.service.rdf.explore;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The words a label or a question is compared by: camel case and any run of characters other than
 * letters and digits split words, case is ignored, a plural folds to its singular, and words under
 * three characters or too common to name anything are dropped. Labels and questions go through the
 * same rule, so they compare word for word.
 */
final class TermWords {

  private static final int SHORTEST_WORD = 3;
  private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[\\p{Ll}\\p{Nd}])(?=\\p{Lu})");
  private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{Nd}]+");
  private static final Set<String> COMMON =
      Set.of(
          "the", "and", "for", "are", "but", "not", "all", "any", "can", "has", "have", "had",
          "was", "were", "which", "what", "who", "whom", "whose", "when", "where", "why", "how",
          "many", "much", "with", "from", "that", "this", "these", "those", "there", "their",
          "them", "they", "into", "than", "then", "does", "did", "our", "your", "its", "per",
          "each", "every", "show", "list", "give", "tell", "get", "about", "been", "being");

  private TermWords() {}

  static List<String> of(final String text) {
    return Arrays.stream(NOT_A_WORD.split(CAMEL_BOUNDARY.matcher(text).replaceAll(" ")))
        .map(word -> word.toLowerCase(Locale.ROOT))
        .filter(word -> word.length() >= SHORTEST_WORD && !COMMON.contains(word))
        .map(TermWords::singular)
        .distinct()
        .toList();
  }

  /** The text a label must contain to be a candidate for the word: its stem without a final y. */
  static String candidateKey(final String word) {
    return word.length() > SHORTEST_WORD && word.endsWith("y")
        ? word.substring(0, word.length() - 1)
        : word;
  }

  private static String singular(final String word) {
    final String stem = isIesPlural(word) ? word.substring(0, word.length() - 3) + "y" : word;
    return isSPlural(stem) ? stem.substring(0, stem.length() - 1) : stem;
  }

  private static boolean isIesPlural(final String word) {
    return word.length() > SHORTEST_WORD + 1 && word.endsWith("ies");
  }

  private static boolean isSPlural(final String word) {
    return word.length() > SHORTEST_WORD && word.endsWith("s") && !word.endsWith("ss");
  }
}
