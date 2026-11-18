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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The nodes a query's rows named: the IRI values of its result bindings, in row order, at most
 * {@link #MAX_NODES}. A body that is not complete result JSON, such as one cut at the read bound,
 * names none.
 */
final class QueriedNodes {

  static final int MAX_NODES = 100;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String URI = "uri";

  private QueriedNodes() {}

  static List<String> of(final String body) {
    return bindings(body)
        .flatMap(row -> StreamSupport.stream(row.spliterator(), false))
        .filter(term -> URI.equals(term.path("type").asText()))
        .map(term -> term.path("value").asText())
        .filter(ExplorationQueries::isQueryable)
        .distinct()
        .limit(MAX_NODES)
        .toList();
  }

  private static Stream<JsonNode> bindings(final String body) {
    try {
      final JsonNode rows =
          JSON.readTree(body == null ? "" : body).path("results").path("bindings");
      return StreamSupport.stream(rows.spliterator(), false);
    } catch (JsonProcessingException unreadable) {
      return Stream.empty();
    }
  }
}
