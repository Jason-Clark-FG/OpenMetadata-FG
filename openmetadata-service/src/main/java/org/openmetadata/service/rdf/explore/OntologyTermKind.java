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

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.SKOS;
import org.openmetadata.service.Entity;

/**
 * The kinds of ontology term {@code find} matches by label in the active release graphs, each
 * named by the entity type a caller lists in {@code entityTypes} and the RDF types a release
 * declares it with.
 */
public enum OntologyTermKind {
  CONCEPT(Entity.ONTOLOGY_CONCEPT, List.of(OWL2.Class.getURI(), SKOS.Concept.getURI())),
  PROPERTY(
      Entity.ONTOLOGY_PROPERTY,
      List.of(
          OWL2.ObjectProperty.getURI(),
          OWL2.DatatypeProperty.getURI(),
          OWL2.AnnotationProperty.getURI()));

  private final String entityType;
  private final List<String> typeIris;

  OntologyTermKind(final String entityType, final List<String> typeIris) {
    this.entityType = entityType;
    this.typeIris = typeIris;
  }

  public String entityType() {
    return entityType;
  }

  List<String> typeIris() {
    return typeIris;
  }

  public static boolean isTermType(final String entityType) {
    return Arrays.stream(values()).anyMatch(kind -> kind.entityType.equals(entityType));
  }

  /** Every kind when no entity type is named; otherwise the kinds named. */
  public static Set<OntologyTermKind> requestedBy(final List<String> entityTypes) {
    return entityTypes.isEmpty()
        ? EnumSet.allOf(OntologyTermKind.class)
        : Arrays.stream(values())
            .filter(kind -> entityTypes.contains(kind.entityType))
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(OntologyTermKind.class)));
  }
}
