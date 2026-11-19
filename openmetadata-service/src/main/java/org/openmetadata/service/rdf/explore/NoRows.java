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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What the tool can tell about a query that returned no rows: the properties and classes it names
 * that the ontology does not declare, each with the closest declared term when one is close, and
 * those no triple in the graph uses. Either makes the empty answer a mistake in the query, not
 * proof that nothing exists.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record NoRows(String means, List<Term> undeclared, List<String> unused) {

  static final String UNMATCHABLE =
      "No rows, and this is not proof that nothing exists: the query names terms the graph cannot"
          + " match, listed here. Correct them and run it again.";
  static final String UNMATCHED =
      "No rows: nothing in the graph matched the whole pattern. That is not proof of absence, since"
          + " a filter, a literal value or a direction may be wrong. To confirm an absence, read the"
          + " node's card: a relationship missing from its 'reachable' and not under 'notCovered'"
          + " has no stored edge in either direction, and a property missing from its 'facts',"
          + " 'values' and 'bookkeeping' has no stored value.";

  public NoRows {
    undeclared = List.copyOf(undeclared);
    unused = List.copyOf(unused);
  }

  static NoRows of(final List<Term> undeclared, final List<String> unused) {
    return new NoRows(
        undeclared.isEmpty() && unused.isEmpty() ? UNMATCHED : UNMATCHABLE, undeclared, unused);
  }

  /** A term the ontology does not declare and the declared one it most likely means, if any. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Term(String term, String closest) {}
}
