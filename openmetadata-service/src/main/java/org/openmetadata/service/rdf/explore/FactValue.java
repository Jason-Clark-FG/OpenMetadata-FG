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

import java.util.Objects;
import java.util.stream.Stream;
import org.apache.jena.query.QuerySolution;

/** One fact value: a literal, or a linked node with whatever name the graph gives it. */
record FactValue(String value, String label, String displayName, String tagFqn) {

  static FactValue of(final QuerySolution row) {
    return new FactValue(
        SparqlRows.text(row, ExplorationQueries.VALUE).orElseThrow(),
        SparqlRows.text(row, ExplorationQueries.VALUE_LABEL).orElse(null),
        SparqlRows.text(row, ExplorationQueries.VALUE_DISPLAY).orElse(null),
        SparqlRows.text(row, ExplorationQueries.VALUE_TAG).orElse(null));
  }

  /** The most readable name of a linked node, falling back to its IRI. */
  String name() {
    return Stream.of(displayName, label, tagFqn, value)
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow();
  }

  /** A tag is named by its fully qualified name, the form a caller filters on. */
  String tagName() {
    return tagFqn != null ? tagFqn : name();
  }
}
