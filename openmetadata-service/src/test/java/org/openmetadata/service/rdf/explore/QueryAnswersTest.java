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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.jena.query.QueryFactory;
import org.junit.jupiter.api.Test;

/** What the query path adds to a caller's SPARQL and to an answer with no rows. */
class QueryAnswersTest {

  private static final String OM_PREFIX = "PREFIX om: <https://open-metadata.org/ontology/>\n";

  private final OntologyVocabulary vocabulary = OntologyVocabulary.canonical();

  private KnowledgeGraphExplorer shop() {
    return new KnowledgeGraphExplorer(
        InMemoryGraph.ofTurtle(NodeCardReaderTest.GRAPH),
        vocabulary,
        NodeCardReaderTest.BASE,
        new InMemoryRunTraceStore(),
        Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void aQueryUsingTheCardsPrefixesWithoutDeclaringThemGetsTheirDeclarations() {
    final QueryPrefixes.Completed completed =
        vocabulary.completePrefixes(
            "SELECT ?table ?text WHERE { ?table a om:Table ; dct:description ?text }");

    assertThat(completed.added()).containsExactly("dct", "om");
    assertThat(QueryFactory.create(completed.sparql())).isNotNull();
  }

  @Test
  void aQueryThatParsesOrNamesAnUnknownPrefixIsLeftAsWritten() {
    final String declared = OM_PREFIX + "SELECT ?t WHERE { ?t a om:Table }";
    final String unknown = "SELECT ?t WHERE { ?t a foo:Table }";

    assertThat(vocabulary.completePrefixes(declared))
        .isEqualTo(new QueryPrefixes.Completed(declared, List.of()));
    assertThat(vocabulary.completePrefixes(unknown))
        .isEqualTo(new QueryPrefixes.Completed(unknown, List.of()));
  }

  @Test
  void noRowsNamesATermTheOntologyDoesNotDeclareWithTheClosestOneItDoes() {
    final NoRows noRows =
        shop().explainNoRows(OM_PREFIX + "SELECT ?o WHERE { ?t a om:Table ; om:owner ?o }");

    assertThat(noRows.undeclared()).containsExactly(new NoRows.Term("om:owner", "om:hasOwner"));
    assertThat(noRows.unused()).isEmpty();
    assertThat(noRows.means()).contains("not proof", "Correct them");
  }

  @Test
  void noRowsNamesADeclaredTermThatNoTripleInTheGraphUses() {
    final NoRows noRows =
        shop()
            .explainNoRows(
                OM_PREFIX + "SELECT ?g WHERE { ?t om:hasGlossaryTerm ?g . ?g a om:GlossaryTerm }");

    assertThat(noRows.undeclared()).isEmpty();
    assertThat(noRows.unused()).containsExactlyInAnyOrder("om:hasGlossaryTerm", "om:GlossaryTerm");
  }

  @Test
  void noRowsFromTermsTheGraphUsesSaysOnlyThatNothingMatchedTheWholePattern() {
    final NoRows noRows =
        shop()
            .explainNoRows(
                OM_PREFIX
                    + "SELECT ?t WHERE { ?t a om:Table ; om:hasOwner ?o . FILTER(?o = om:nobody) }");

    assertThat(noRows.undeclared()).isEmpty();
    assertThat(noRows.unused()).isEmpty();
    assertThat(noRows.means())
        .contains("nothing in the graph matched the whole pattern", "not proof of absence")
        .contains("filter, a literal value or a direction", "'reachable'", "'notCovered'")
        .doesNotContain("declared and used", "evidence");
  }
}
