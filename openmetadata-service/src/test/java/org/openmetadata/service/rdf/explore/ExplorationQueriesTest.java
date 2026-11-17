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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplorationQueriesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://x/>}UNION{?a?b?c}#",
        "http://x/\\u003E}UNION{?a ?b ?c}#",
        "java://org.example.Evil",
        "urn:x:y",
        "http://x/a{b",
        "http://x/a}b",
        "http://x/a|b",
        "http://x/a^b",
        "http://x/a\\b"
      })
  void theInterpolationPointAcceptsOnlyAbsoluteHttpIris(final String value) {
    assertThatThrownBy(() -> ExplorationQueries.iri(value))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://open-metadata.org/ontology/hasOwner",
        "http://www.w3.org/2000/01/rdf-schema#label",
        "https://open-metadata.org/entity/column/svc.db.schema.orders.customer%2Eid"
      })
  void anAbsoluteHttpIriIsInterpolatedInAngleBrackets(final String value) {
    assertThat(ExplorationQueries.iri(value)).isEqualTo("<" + value + ">");
  }
}
