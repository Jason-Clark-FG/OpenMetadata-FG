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

/**
 * One populated relationship of a node: the name a caller passes back to {@code expand}, which end
 * the node is on and how many distinct nodes are on the other side. What the relationship is comes
 * once per response, under its name in {@code relationships}.
 */
public record Reachable(String relationship, Direction direction, long count) {}
