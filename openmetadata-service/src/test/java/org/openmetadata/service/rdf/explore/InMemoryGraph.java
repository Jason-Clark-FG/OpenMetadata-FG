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

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.ResultSetMgr;
import org.apache.jena.riot.resultset.ResultSetLang;

/** Answers SELECT queries from a Jena model as SPARQL JSON, the way the triplestore does. */
final class InMemoryGraph implements GraphReader {

  static final String PREFIXES =
      """
      @prefix om: <https://open-metadata.org/ontology/> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
      @prefix dct: <http://purl.org/dc/terms/> .
      @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
      @prefix e: <https://open-metadata.org/entity/> .
      """;

  private final Model model;
  private final List<String> queries = new ArrayList<>();

  private InMemoryGraph(final Model model) {
    this.model = model;
  }

  static InMemoryGraph ofTurtle(final String turtle) {
    final Model model = ModelFactory.createDefaultModel();
    model.read(new StringReader(PREFIXES + turtle), null, "TTL");
    return new InMemoryGraph(model);
  }

  static InMemoryGraph of(final Model model) {
    return new InMemoryGraph(model);
  }

  @Override
  public String select(final String sparql) {
    queries.add(sparql);
    try (QueryExecution execution = QueryExecutionFactory.create(sparql, model)) {
      final ByteArrayOutputStream output = new ByteArrayOutputStream();
      ResultSetMgr.write(output, execution.execSelect(), ResultSetLang.RS_JSON);
      return output.toString(StandardCharsets.UTF_8);
    }
  }

  List<String> queries() {
    return List.copyOf(queries);
  }
}
