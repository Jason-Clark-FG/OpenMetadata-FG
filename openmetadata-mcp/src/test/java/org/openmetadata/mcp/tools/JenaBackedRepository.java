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

package org.openmetadata.mcp.tools;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.ResultSetMgr;
import org.apache.jena.riot.resultset.ResultSetLang;
import org.openmetadata.schema.api.configuration.rdf.RdfConfiguration;
import org.openmetadata.service.rdf.RdfRepository;

/**
 * An enabled repository whose asserted-only reads run against an in-memory model, so every read
 * path the tools take answers from the same graph.
 */
final class JenaBackedRepository {

  static final String BASE = "https://open-metadata.org/";
  private static final String PREFIXES =
      """
      @prefix om: <https://open-metadata.org/ontology/> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix e: <https://open-metadata.org/entity/> .
      """;

  private JenaBackedRepository() {}

  static RdfRepository ofTurtle(final String turtle) {
    final Model model = ModelFactory.createDefaultModel();
    model.read(new StringReader(PREFIXES + turtle), null, "TTL");
    final RdfRepository repository = mock(RdfRepository.class);
    when(repository.isEnabled()).thenReturn(true);
    when(repository.getBaseUri()).thenReturn(BASE);
    when(repository.getConfig()).thenReturn(new RdfConfiguration());
    when(repository.executeAssertedSparqlQuery(anyString(), anyString()))
        .thenAnswer(call -> select(model, call.getArgument(0, String.class)));
    return repository;
  }

  private static String select(final Model model, final String sparql) {
    try (QueryExecution execution = QueryExecutionFactory.create(sparql, model)) {
      final ByteArrayOutputStream output = new ByteArrayOutputStream();
      ResultSetMgr.write(output, execution.execSelect(), ResultSetLang.RS_JSON);
      return output.toString(StandardCharsets.UTF_8);
    }
  }
}
