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

/**
 * An edge of the explored subgraph, seen from {@code node}. Without {@code other} it is a
 * relationship a card offered, {@code count} far nodes wide; it is {@code explored} once an
 * expansion from {@code node} followed it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceEdge(
    String node,
    String relationship,
    Direction direction,
    String other,
    Long count,
    ExplorationState state,
    int firstStep) {}
