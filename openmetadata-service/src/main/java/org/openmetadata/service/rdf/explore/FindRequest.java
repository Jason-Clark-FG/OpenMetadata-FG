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

import java.util.Set;

/**
 * A find: the caller's words, the kinds of ontology term to match by label (none for a catalog
 * only find), at most {@code limit} cards, and the characters they may take.
 */
public record FindRequest(
    String question, Set<OntologyTermKind> termKinds, int limit, long maxChars) {

  public FindRequest {
    termKinds = Set.copyOf(termKinds);
  }
}
