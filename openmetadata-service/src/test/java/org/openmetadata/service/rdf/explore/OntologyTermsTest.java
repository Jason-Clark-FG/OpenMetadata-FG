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
  static final String CLIENT = "https://example.org/model/Client";
  static final String RELEASE_TERMS =
      """
      <%s> a owl:Class ; rdfs:label "Subscriber" .
      <%s> a owl:Class , skos:Concept ; rdfs:label "Subscription plan" .
      <%s> a owl:DatatypeProperty ; rdfs:label "postal code" ; rdfs:domain <%s> .
      <%s> a owl:Class ; rdfs:label "Client" ; skos:altLabel "Account holder" .
      <urn:Subscriber> a owl:Class ; rdfs:label "Subscriber in a urn" .
      <https://example.org/model> a owl:Ontology ; rdfs:label "Subscriber model" .
      """
          .formatted(SUBSCRIBER, SUBSCRIPTION_PLAN, POSTAL_CODE, SUBSCRIBER, CLIENT);
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

  private final OntologyTerms terms = new OntologyTerms(releaseAndCatalog());

  @Test
  void aConceptOfAnActiveReleaseIsFoundByPartOfItsLabelIgnoringCase() {
    assertThat(terms.matching("SUBSCRI", EVERY_KIND, 10))
        .containsExactly(SUBSCRIBER, SUBSCRIPTION_PLAN);
  }

  @Test
  void aPropertyIsFoundByItsLabel() {
    assertThat(terms.matching("Postal Code", EVERY_KIND, 10)).containsExactly(POSTAL_CODE);
  }

  @Test
  void aConceptIsFoundByTheAlternativeLabelTheReleaseCarries() {
    assertThat(terms.matching("account holder", EVERY_KIND, 10)).containsExactly(CLIENT);
  }

  @Test
  void onlyTheRequestedKindsOfTermAreMatched() {
    assertThat(terms.matching("s", EnumSet.of(OntologyTermKind.PROPERTY), 10))
        .containsExactly(POSTAL_CODE);
    assertThat(terms.matching("postal", EnumSet.of(OntologyTermKind.CONCEPT), 10)).isEmpty();
  }

  @Test
  void labelsOutsideTheReleaseGraphsAndTheOntologyItselfAreNotMatched() {
    assertThat(terms.matching("subscriber", EVERY_KIND, 10))
        .containsExactly(SUBSCRIBER)
        .doesNotContain("https://open-metadata.org/entity/glossaryTerm/subscriber");
  }

  @Test
  void theShortestMatchingLabelComesFirstAndTheLimitHolds() {
    assertThat(terms.matching("sub", EVERY_KIND, 1)).containsExactly(SUBSCRIBER);
  }

  @Test
  void quotesBackslashesAndBracesInTheQuestionAreMatchedAsTextAndNeverAsQuery() {
    final OntologyTerms hostile =
        new OntologyTerms(
            InMemoryGraph.ofNamedGraphs(
                Map.of(
                    RELEASE,
                    RELEASE_TERMS
                        + "<https://example.org/model/Odd> a owl:Class ;"
                        + " rdfs:label \"Odd \\\" \\\\ } term\" .")));

    assertThat(hostile.matching("\" \\ }", EVERY_KIND, 10))
        .containsExactly("https://example.org/model/Odd");
    assertThat(hostile.matching("x\" ) ) } UNION { ?term ?p ?o } #", EVERY_KIND, 10)).isEmpty();
    assertThat(hostile.matching("\\", EVERY_KIND, 10))
        .containsExactly("https://example.org/model/Odd");
  }

  @Test
  void noReleaseGraphMeansNoTerms() {
    final OntologyTerms catalogOnly =
        new OntologyTerms(InMemoryGraph.ofNamedGraphs(Map.of(KNOWLEDGE, CATALOG)));

    assertThat(catalogOnly.matching("subscriber", EVERY_KIND, 10)).isEmpty();
  }
}
