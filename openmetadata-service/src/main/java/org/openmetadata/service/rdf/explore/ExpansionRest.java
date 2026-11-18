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

import java.util.ArrayList;
import java.util.List;

/** The sentence a group of an expansion carries when it leaves far nodes or their cards out. */
final class ExpansionRest {

  private ExpansionRest() {}

  static String of(
      final GraphExpansion.GroupKey key,
      final String relationshipIri,
      final long total,
      final int listed,
      final int carded,
      final Integer nextOffset,
      final int offset) {
    final List<String> parts = new ArrayList<>();
    if (listed < total) {
      parts.add(
          "%d of %d are listed. To list the rest, run query with: %s"
              .formatted(listed, total, restQuery(key, relationshipIri, listed)));
    }
    final boolean moreCards = nextOffset != null;
    if (carded < total && (moreCards || offset == 0)) {
      parts.add(
          "Cards for %d of %d are here; %s."
              .formatted(
                  carded,
                  total,
                  moreCards
                      ? "pass nextCursor back as cursor for more, or expand a listed id"
                      : "expand a listed id for its card"));
    }
    return parts.isEmpty() ? null : String.join(" ", parts);
  }

  private static String restQuery(
      final GraphExpansion.GroupKey key, final String relationshipIri, final int offset) {
    final String relationship = ExplorationQueries.iri(relationshipIri);
    final String edge =
        key.direction() == Direction.OUTGOING
            ? "%s %s ?id . FILTER(isIRI(?id))"
                .formatted(ExplorationQueries.iri(key.node()), relationship)
            : "?id %s %s .".formatted(relationship, ExplorationQueries.iri(key.node()));
    return "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> SELECT ?id ?name WHERE { %s OPTIONAL { ?id rdfs:label ?name } } ORDER BY ?id OFFSET %d"
        .formatted(edge, offset);
  }
}
