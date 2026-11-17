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

import java.util.List;
import java.util.stream.Stream;

/** Turns what a call served into the step the run records. */
final class RunSteps {

  private RunSteps() {}

  static List<RunStep.Node> served(final List<NodeCard> cards) {
    return cards.stream()
        .map(card -> new RunStep.Node(card.id(), card.label(), card.entityType(), offersOf(card)))
        .toList();
  }

  static List<RunStep.Edge> expanded(final ExpandPage page) {
    return page.expansions().stream().flatMap(RunSteps::edgesOf).toList();
  }

  private static List<RunStep.Offer> offersOf(final NodeCard card) {
    return card.reachable().stream()
        .map(entry -> new RunStep.Offer(entry.relationship(), entry.direction(), entry.count()))
        .toList();
  }

  private static Stream<RunStep.Edge> edgesOf(final ExpansionGroup group) {
    return group.nodes().stream()
        .map(
            other ->
                new RunStep.Edge(
                    group.node(), group.relationship(), group.direction(), other, null));
  }
}
