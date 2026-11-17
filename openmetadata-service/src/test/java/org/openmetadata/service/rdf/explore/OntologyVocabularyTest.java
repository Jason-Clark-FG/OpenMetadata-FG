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

import java.io.StringReader;
import java.util.List;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

class OntologyVocabularyTest {

  private static final String OM = "https://open-metadata.org/ontology/";

  private static final String FIXTURE =
      """
      @prefix om: <https://open-metadata.org/ontology/> .
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix dcat: <http://www.w3.org/ns/dcat#> .

      om:Entity a owl:Class .
      om:DataAsset a owl:Class ; rdfs:subClassOf om:Entity .
      om:Table a owl:Class ; rdfs:subClassOf om:DataAsset .
      om:User a owl:Class ; rdfs:subClassOf om:Entity .

      om:hasOwner a owl:ObjectProperty ;
          om:projectionStatus om:Stored ;
          rdfs:label "has owner" ;
          rdfs:comment "The user or team accountable for the asset." .

      om:hasTier a owl:ObjectProperty ;
          om:projectionStatus om:Stored ;
          rdfs:label "has tier" .

      om:accuracy a owl:DatatypeProperty ;
          om:projectionStatus om:InferenceOnly ;
          rdfs:label "accuracy" ;
          rdfs:comment "Not emitted by the metadata projection. Retained for custom inference." ;
          rdfs:comment "Degree of data accuracy" ;
          rdfs:domain om:DataAsset .

      om:followedBy a owl:ObjectProperty ;
          om:projectionStatus om:InferenceOnly ;
          rdfs:label "followed by" ;
          rdfs:domain om:User .

      om:storedQuality a owl:ObjectProperty ;
          om:projectionStatus om:Stored ;
          rdfs:label "stored quality" .

      om:qualityAlias a owl:ObjectProperty ;
          om:projectionStatus om:InferenceOnly ;
          rdfs:label "quality alias" ;
          rdfs:subPropertyOf om:storedQuality ;
          rdfs:domain om:DataAsset .

      om:legacyLineage a owl:ObjectProperty ;
          om:projectionStatus om:InferenceOnly ;
          owl:deprecated true ;
          rdfs:label "legacy lineage" ;
          rdfs:domain om:DataAsset .

      om:portName a owl:DatatypeProperty ;
          om:projectionStatus om:InferenceOnly ;
          rdfs:label "port name" ;
          rdfs:domain [ a owl:Class ; owl:unionOf ( om:User om:Table ) ] .

      om:anywhere a owl:DatatypeProperty ;
          om:projectionStatus om:InferenceOnly ;
          rdfs:label "anywhere" .
      """;

  private static OntologyVocabulary fixture() {
    final Model model = ModelFactory.createDefaultModel();
    model.read(new StringReader(FIXTURE), null, "TTL");
    return new OntologyVocabulary(model);
  }

  @Test
  void aboutCarriesTheOntologyCommentWhenThereIsOne() {
    final RelationshipAbout about = fixture().about(OM + "hasOwner");

    assertThat(about.label()).isEqualTo("has owner");
    assertThat(about.comment()).isEqualTo("The user or team accountable for the asset.");
  }

  @Test
  void aboutCarriesOnlyTheLabelWhenTheOntologyHasNoComment() {
    final RelationshipAbout about = fixture().about(OM + "hasTier");

    assertThat(about.label()).isEqualTo("has tier");
    assertThat(about.comment()).isNull();
  }

  @Test
  void aboutPrefersTheDescriptiveCommentOverTheProjectionNote() {
    assertThat(fixture().about(OM + "accuracy").comment()).isEqualTo("Degree of data accuracy");
  }

  @Test
  void anUndeclaredPredicateIsLabelledByItsLocalName() {
    final RelationshipAbout about = fixture().about(OM + "customRelation");

    assertThat(about.label()).isEqualTo("customRelation");
    assertThat(about.comment()).isNull();
  }

  @Test
  void notCoveredListsInferenceOnlyPropertiesWhoseDomainTheNodeBelongsTo() {
    final List<String> properties =
        fixture().notCoveredFor(List.of(OM + "Table")).stream().map(NotCovered::property).toList();

    assertThat(properties).containsExactly("om:accuracy", "om:anywhere", "om:portName");
  }

  @Test
  void notCoveredFollowsTheDomainOfEachNodeType() {
    final List<String> properties =
        fixture().notCoveredFor(List.of(OM + "User")).stream().map(NotCovered::property).toList();

    assertThat(properties).containsExactly("om:anywhere", "om:followedBy", "om:portName");
  }

  @Test
  void notCoveredCarriesTheOntologyLabel() {
    assertThat(fixture().notCoveredFor(List.of(OM + "Table")))
        .contains(new NotCovered("om:accuracy", "accuracy"));
  }

  @Test
  void theCanonicalOntologyMarksUnprojectedQualityDimensionsAsNotCoveredForTables() {
    final List<String> properties =
        OntologyVocabulary.canonical().notCoveredFor(List.of(OM + "Table")).stream()
            .map(NotCovered::property)
            .toList();

    assertThat(properties).contains("om:accuracy", "om:completeness").doesNotContain("om:UPSTREAM");
  }

  @Test
  void compactsAndExpandsPrefixedNames() {
    final OntologyVocabulary vocabulary = fixture();

    assertThat(vocabulary.compact(OM + "hasOwner")).isEqualTo("om:hasOwner");
    assertThat(vocabulary.expand("om:hasOwner")).isEqualTo(OM + "hasOwner");
    assertThat(vocabulary.expand(OM + "hasOwner")).isEqualTo(OM + "hasOwner");
  }

  @Test
  void anUnknownPrefixIsACorrectionTheCallerCanActOn() {
    assertThatThrownBy(() -> fixture().expand("owner"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'owner'")
        .hasMessageContaining("om:hasOwner");
  }
}
