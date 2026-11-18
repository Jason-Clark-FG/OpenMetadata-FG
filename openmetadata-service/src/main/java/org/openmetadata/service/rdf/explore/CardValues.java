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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.jena.query.QuerySolution;

/** Turns the value rows of a batch of nodes into each node's values, one per property. */
final class CardValues {

  static final String NODE = "node";
  static final String PREDICATE = "predicate";
  static final String COUNT = "count";
  static final String FIRST = "first";
  static final String LAST = "last";
  static final String LONGEST = "longest";
  static final String DATATYPE = "datatype";
  private static final String CUT_MARK = "…";

  private CardValues() {}

  static Map<String, List<CardValue>> byNode(
      final List<QuerySolution> rows, final OntologyVocabulary vocabulary) {
    return rows.stream()
        .collect(
            Collectors.groupingBy(
                row -> SparqlRows.iri(row, NODE),
                Collectors.mapping(
                    row -> value(row, vocabulary),
                    Collectors.collectingAndThen(
                        Collectors.toList(),
                        values ->
                            values.stream()
                                .sorted(Comparator.comparing(CardValue::property))
                                .toList()))));
  }

  private static CardValue value(final QuerySolution row, final OntologyVocabulary vocabulary) {
    final int count = row.getLiteral(COUNT).getInt();
    final int longest = row.getLiteral(LONGEST).getInt();
    final boolean several = count > 1;
    return new CardValue(
        vocabulary.compact(SparqlRows.iri(row, PREDICATE)),
        type(row),
        shown(row.getLiteral(FIRST).getLexicalForm(), longest),
        several ? shown(row.getLiteral(LAST).getLexicalForm(), longest) : null,
        several ? count : null,
        longest > CardValue.VALUE_LIMIT ? longest : null);
  }

  private static String type(final QuerySolution row) {
    return row.contains(DATATYPE)
        ? ValueType.of(row.getResource(DATATYPE).getURI()).map(ValueType::value).orElse(null)
        : null;
  }

  /** The query cuts every value at the limit; one that reached it was longer when any was. */
  private static String shown(final String text, final int longest) {
    final boolean cut = text.length() >= CardValue.VALUE_LIMIT && longest > CardValue.VALUE_LIMIT;
    return cut ? text + CUT_MARK : text;
  }
}
