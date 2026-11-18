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

import java.util.List;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.openmetadata.service.rdf.OntologyDocument;

/**
 * Tables with relationships the canonical ontology describes, so card sizes are those of real
 * responses: each relationship carries the comment a model reads cold.
 */
final class DescribedRelationships {

  static final String BASE = "https://open-metadata.org/";
  static final String OM = "https://open-metadata.org/ontology/";

  private DescribedRelationships() {}

  /** The first {@code count} stored object properties of the ontology with a comment, by IRI. */
  static List<String> predicates(final int count) {
    final Model ontology = OntologyDocument.loadCanonicalModel();
    try {
      return ontology.listSubjectsWithProperty(RDF.type, OWL2.ObjectProperty).toList().stream()
          .filter(Resource::isURIResource)
          .filter(property -> property.getURI().startsWith(OM))
          .filter(property -> property.hasProperty(RDFS.comment))
          .filter(property -> property.hasProperty(projectionStatus(ontology), stored(ontology)))
          .map(Resource::getURI)
          .sorted()
          .limit(count)
          .toList();
    } finally {
      ontology.close();
    }
  }

  private static Property projectionStatus(final Model ontology) {
    return ontology.createProperty(OM + "projectionStatus");
  }

  private static Resource stored(final Model ontology) {
    return ontology.createResource(OM + "Stored");
  }

  static String table(final UUID id) {
    return BASE + "entity/table/" + id;
  }

  /** A table typed om:Table with one far node behind each relationship. */
  static String wideTable(final UUID id, final List<String> predicates) {
    final StringBuilder turtle =
        new StringBuilder("<%s> a om:Table ; rdfs:label \"table %s\" .%n".formatted(table(id), id));
    for (int index = 0; index < predicates.size(); index++) {
      turtle.append(
          "<%s> <%s> e:target-%s-%d .%n".formatted(table(id), predicates.get(index), id, index));
    }
    return turtle.toString();
  }
}
