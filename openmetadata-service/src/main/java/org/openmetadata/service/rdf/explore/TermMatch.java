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

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * How one label of a term meets the question. It matches when every word of the label is in the
 * question, or every word of the question is in the label. A more specific match covers more of the
 * question's words; between equal covers, the label with fewer words outside the question is the
 * more specific; the term IRI breaks the remaining ties.
 */
record TermMatch(String term, int covered, int outside) {

  static final Comparator<TermMatch> MOST_SPECIFIC_FIRST =
      Comparator.comparingInt(TermMatch::covered)
          .reversed()
          .thenComparingInt(TermMatch::outside)
          .thenComparing(TermMatch::term);

  static Optional<TermMatch> of(
      final String term, final String label, final Collection<String> question) {
    final Set<String> labelWords = new HashSet<>(TermWords.of(label));
    final boolean matches =
        !labelWords.isEmpty()
            && (question.containsAll(labelWords) || labelWords.containsAll(question));
    final Set<String> covered = new HashSet<>(labelWords);
    covered.retainAll(question);
    return matches
        ? Optional.of(new TermMatch(term, covered.size(), labelWords.size() - covered.size()))
        : Optional.empty();
  }
}
