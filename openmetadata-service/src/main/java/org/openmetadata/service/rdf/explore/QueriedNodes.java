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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementData;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementTriplesBlock;
import org.apache.jena.sparql.syntax.ElementVisitorBase;
import org.apache.jena.sparql.syntax.ElementWalker;

/**
 * The nodes a query served: the IRIs its pattern names as a subject, an object or a {@code VALUES}
 * entry when it returned rows, then the IRI values of its result bindings, in row order, at most
 * {@link #MAX_NODES}. A body that is not complete result JSON, such as one cut at the read bound,
 * names none.
 */
final class QueriedNodes {

  static final int MAX_NODES = 100;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String URI = "uri";

  private QueriedNodes() {}

  static List<String> of(final String sparql, final String body) {
    final List<JsonNode> rows = bindings(body).toList();
    final Stream<String> bound =
        rows.stream()
            .flatMap(row -> StreamSupport.stream(row.spliterator(), false))
            .filter(term -> URI.equals(term.path("type").asText()))
            .map(term -> term.path("value").asText());
    return Stream.concat(rows.isEmpty() ? Stream.empty() : askedAbout(sparql), bound)
        .filter(ExplorationQueries::isQueryable)
        .distinct()
        .limit(MAX_NODES)
        .toList();
  }

  private static Stream<String> askedAbout(final String sparql) {
    try {
      final Query query = QueryFactory.create(sparql, Syntax.syntaxARQ);
      return Optional.ofNullable(query.getQueryPattern()).stream().flatMap(QueriedNodes::named);
    } catch (QueryException unparsable) {
      return Stream.empty();
    }
  }

  private static Stream<String> named(final Element pattern) {
    final List<Node> nodes = new ArrayList<>();
    ElementWalker.walk(
        pattern,
        new ElementVisitorBase() {
          @Override
          public void visit(final ElementPathBlock block) {
            block
                .patternElts()
                .forEachRemaining(
                    path -> nodes.addAll(List.of(path.getSubject(), path.getObject())));
          }

          @Override
          public void visit(final ElementTriplesBlock block) {
            block
                .patternElts()
                .forEachRemaining(
                    triple -> nodes.addAll(List.of(triple.getSubject(), triple.getObject())));
          }

          @Override
          public void visit(final ElementData data) {
            data.getRows()
                .forEach(row -> row.vars().forEachRemaining(name -> nodes.add(row.get(name))));
          }
        });
    return nodes.stream().filter(node -> node != null && node.isURI()).map(Node::getURI);
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
