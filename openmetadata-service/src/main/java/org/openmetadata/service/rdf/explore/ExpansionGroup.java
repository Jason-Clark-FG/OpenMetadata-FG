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
 * The far nodes one relationship reaches from one node in one direction: {@code total} of them,
 * {@code listed} in short form (all of them unless the response could not hold them), and {@code
 * nodes}, the ids of those whose full card this page returns. {@code rest} says in words what is
 * left out and the call that reads it; it is absent when the group is complete.
 */
public record ExpansionGroup(
    String node,
    String relationship,
    Direction direction,
    long total,
    List<FarNode> listed,
    List<String> nodes,
    @JsonInclude(JsonInclude.Include.NON_NULL) String rest) {

  public ExpansionGroup {
    listed = List.copyOf(listed);
    nodes = List.copyOf(nodes);
  }
}
