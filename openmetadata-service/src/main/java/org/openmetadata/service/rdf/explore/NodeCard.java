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
 * A node as the knowledge graph describes it: identity, key facts, every relationship populated
 * for it in both directions ({@code reachable}), and the fact families the projection does not
 * carry for its type ({@code notCovered}), so an absent edge is never read as an absent fact.
 * {@code inGraph} is false when the graph holds nothing about the node.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record NodeCard(
    String id,
    String entityType,
    String fullyQualifiedName,
    String label,
    List<String> types,
    KeyFacts facts,
    List<Reachable> reachable,
    List<NotCovered> notCovered,
    boolean inGraph) {

  public NodeCard {
    types = List.copyOf(types);
    reachable = List.copyOf(reachable);
    notCovered = List.copyOf(notCovered);
  }
}
