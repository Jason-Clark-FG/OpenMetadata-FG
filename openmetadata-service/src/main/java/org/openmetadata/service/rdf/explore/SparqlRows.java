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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.ResultSetMgr;
import org.apache.jena.riot.resultset.ResultSetLang;

/** SPARQL JSON results read once into solutions, with typed accessors for optional bindings. */
final class SparqlRows {

  private SparqlRows() {}

  static List<QuerySolution> parse(final String sparqlJson) {
    final ResultSet results =
        ResultSetMgr.read(
            new ByteArrayInputStream(sparqlJson.getBytes(StandardCharsets.UTF_8)),
            ResultSetLang.RS_JSON);
    final List<QuerySolution> rows = new ArrayList<>();
    results.forEachRemaining(rows::add);
    return List.copyOf(rows);
  }

  static Optional<String> text(final QuerySolution row, final String variable) {
    return Optional.ofNullable(row.get(variable)).map(SparqlRows::lexical);
  }

  static String iri(final QuerySolution row, final String variable) {
    return row.getResource(variable).getURI();
  }

  private static String lexical(final RDFNode node) {
    return node.isLiteral() ? node.asLiteral().getLexicalForm() : node.asResource().getURI();
  }
}
