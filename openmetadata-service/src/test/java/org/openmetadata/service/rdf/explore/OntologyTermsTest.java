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

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.rdf.OntologyActiveGraph;

class OntologyTermsTest {

  static final String RELEASE = OntologyActiveGraph.GRAPH_PREFIX + UUID.randomUUID();
  static final String KNOWLEDGE = "https://open-metadata.org/graph/knowledge";
  static final String SUBSCRIBER = "https://example.org/model/Subscriber";
  static final String SUBSCRIPTION_PLAN = "https://example.org/model/SubscriptionPlan";
  static final String POSTAL_CODE = "https://example.org/model/postalCode";
  static final String POSTAL_CODE_CAMEL = "https://example.org/model/postalCodeCamel";
  static final String POSTAL_CODE_SNAKE = "https://example.org/model/postalCodeSnake";
  static final String CLIENT = "https://example.org/model/Client";
  static final String AGE = "https://example.org/model/Age";
  static final String RELEASE_TERMS =
      """
      <%s> a owl:Class ; rdfs:label "Subscriber" .
      <%s> a owl:Class , skos:Concept ; rdfs:label "Subscription plan" .
      <%s> a owl:DatatypeProperty ; rdfs:label "Postal code" ; rdfs:domain <%s> .
      <%s> a owl:DatatypeProperty ; rdfs:label "postalCode" .
      <%s> a owl:DatatypeProperty ; rdfs:label "postal_code" .
      <%s> a owl:Class ; rdfs:label "Client" ; skos:altLabel "Account holder" .
      <%s> a owl:Class ; rdfs:label "Age" .
      <urn:Subscriber> a owl:Class ; rdfs:label "Subscriber in a urn" .
      <https://example.org/model> a owl:Ontology ; rdfs:label "Subscriber model" .
      """
          .formatted(
              SUBSCRIBER,
              SUBSCRIPTION_PLAN,
              POSTAL_CODE,
              SUBSCRIBER,
              POSTAL_CODE_CAMEL,
              POSTAL_CODE_SNAKE,
              CLIENT,
              AGE);
  static final String CATALOG =
      """
      e:table-subscribers a om:Table ; rdfs:label "subscribers" .
      <https://open-metadata.org/entity/glossaryTerm/subscriber> a owl:Class ;
          rdfs:label "Subscriber" .
      """;

  private static final Set<OntologyTermKind> EVERY_KIND = EnumSet.allOf(OntologyTermKind.class);

  static InMemoryGraph releaseAndCatalog() {
    final Map<String, String> graphs = new LinkedHashMap<>();
    graphs.put(RELEASE, RELEASE_TERMS);
    graphs.put(KNOWLEDGE, CATALOG);
    return InMemoryGraph.ofNamedGraphs(graphs);
  }

  private final InMemoryGraph graph = releaseAndCatalog();
  private final OntologyTerms terms = new OntologyTerms(graph);

  private TermLookup lookup(final String question) {
    return terms.matching(question, EVERY_KIND, 10);
  }

  @Test
  void termsWhoseLabelAppearsInAQuestionAreFoundTheMostSpecificFirst() {
    assertThat(lookup("which tables hold subscriber postal codes").terms())
        .containsExactly(POSTAL_CODE, POSTAL_CODE_CAMEL, POSTAL_CODE_SNAKE, SUBSCRIBER);
  }

  @Test
  void aTermIsFoundWhenTheQuestionAppearsInItsLabel() {
    assertThat(lookup("subscription").terms()).containsExactly(SUBSCRIPTION_PLAN);
  }

  @Test
  void camelCaseSnakeCaseCapitalsAndPluralsDoNotDefeatTheMatch() {
    assertThat(lookup("postal code").terms())
        .containsExactly(POSTAL_CODE, POSTAL_CODE_CAMEL, POSTAL_CODE_SNAKE);
    assertThat(lookup("Postal Codes").terms())
        .containsExactly(POSTAL_CODE, POSTAL_CODE_CAMEL, POSTAL_CODE_SNAKE);
  }

  @Test
  void aLabelIsNeverMatchedInsideAnUnrelatedWord() {
    assertThat(lookup("average order value").terms()).isEmpty();
  }

  @Test
  void aQuestionOfOnlyShortOrCommonWordsLooksForNothing() {
    assertThat(lookup("what is the id of it").terms()).isEmpty();
    assertThat(graph.queries()).isEmpty();
  }

  @Test
  void aConceptIsFoundByTheAlternativeLabelTheReleaseCarries() {
    assertThat(lookup("who is the account holder").terms()).containsExactly(CLIENT);
  }

  @Test
  void onlyTheRequestedKindsOfTermAreMatched() {
    assertThat(
            terms
                .matching("subscriber postal code", EnumSet.of(OntologyTermKind.PROPERTY), 10)
                .terms())
        .containsExactly(POSTAL_CODE, POSTAL_CODE_CAMEL, POSTAL_CODE_SNAKE);
    assertThat(
            terms
                .matching("subscriber postal code", EnumSet.of(OntologyTermKind.CONCEPT), 10)
                .terms())
        .containsExactly(SUBSCRIBER);
  }

  @Test
  void labelsOutsideTheReleaseGraphsTheOntologyItselfAndUrnTermsAreNotMatched() {
    assertThat(lookup("subscriber").terms()).containsExactly(SUBSCRIBER);
  }

  @Test
  void theLimitKeepsTheMostSpecificTerms() {
    assertThat(terms.matching("subscriber postal codes", EVERY_KIND, 1).terms())
        .containsExactly(POSTAL_CODE);
  }

  @Test
  void quotesBackslashesAndBracesInTheQuestionAreTextAndNeverQuery() {
    final OntologyTerms hostile =
        new OntologyTerms(
            InMemoryGraph.ofNamedGraphs(
                Map.of(
                    RELEASE,
                    RELEASE_TERMS
                        + "<https://example.org/model/Odd> a owl:Class ;"
                        + " rdfs:label \"Odd \\\" \\\\ } term\" .")));

    assertThat(hostile.matching("odd\" \\ } term", EVERY_KIND, 10).terms())
        .containsExactly("https://example.org/model/Odd");
    assertThat(hostile.matching("x\" ) ) } UNION { ?term ?p ?o } #", EVERY_KIND, 10).terms())
        .isEmpty();
    assertThat(hostile.matching("\" \\ }", EVERY_KIND, 10).terms()).isEmpty();
  }

  @Test
  void noReleaseGraphMeansNoTerms() {
    final OntologyTerms catalogOnly =
        new OntologyTerms(InMemoryGraph.ofNamedGraphs(Map.of(KNOWLEDGE, CATALOG)));

    assertThat(catalogOnly.matching("subscriber", EVERY_KIND, 10).terms()).isEmpty();
  }

  @Test
  void aLookupThatRanReportsNothingSkipped() {
    assertThat(lookup("subscriber").skipped()).isNull();
  }
}
