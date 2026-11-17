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

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDFS;
import org.openmetadata.service.rdf.OntologyDocument;

/**
 * What the canonical ontology says about relationships and classes, read once into immutable maps
 * so concurrent tool calls never touch a Jena model.
 *
 * <p>{@code notCovered} is derived from {@code om:projectionStatus}: an {@code om:InferenceOnly}
 * property is a fact family the projection does not write, unless it is deprecated or an alias
 * (sub-property or inverse) of a property the projection does write under another name.
 */
public final class OntologyVocabulary {

  private static final String OM = "https://open-metadata.org/ontology/";
  private static final Property PROJECTION_STATUS =
      ResourceFactory.createProperty(OM, "projectionStatus");
  private static final Resource STORED = ResourceFactory.createResource(OM + "Stored");
  private static final Resource INFERENCE_ONLY =
      ResourceFactory.createResource(OM + "InferenceOnly");
  private static final String PROJECTION_NOTE = "Not emitted by the metadata projection";
  private static final String EXAMPLE_RELATIONSHIP = "om:hasOwner";
  private static final String IRI_SCHEME_SEPARATOR = "://";

  private final PrefixMapping prefixes;
  private final Map<String, RelationshipAbout> abouts;
  private final Map<String, Set<String>> classAncestors;
  private final List<UnprojectedProperty> unprojected;

  OntologyVocabulary(final Model ontology) {
    prefixes = PrefixMapping.Factory.create().setNsPrefixes(ontology).lock();
    abouts = Map.copyOf(readAbouts(ontology));
    classAncestors = Map.copyOf(readClassAncestors(ontology));
    unprojected = readUnprojected(ontology);
  }

  private static final class Holder {
    private static final OntologyVocabulary INSTANCE = loadCanonical();

    private static OntologyVocabulary loadCanonical() {
      final Model ontology = OntologyDocument.loadCanonicalModel();
      try {
        return new OntologyVocabulary(ontology);
      } finally {
        ontology.close();
      }
    }
  }

  public static OntologyVocabulary canonical() {
    return Holder.INSTANCE;
  }

  public RelationshipAbout about(final String predicateIri) {
    return abouts.getOrDefault(predicateIri, new RelationshipAbout(localName(predicateIri), null));
  }

  /** Properties the projection never writes for a node of these types, sorted by name. */
  public List<NotCovered> notCoveredFor(final Collection<String> typeIris) {
    final Set<String> classes = withAncestors(typeIris);
    return unprojected.stream()
        .filter(property -> property.appliesTo(classes))
        .map(property -> new NotCovered(property.name(), property.label()))
        .toList();
  }

  public String compact(final String iri) {
    return prefixes.shortForm(iri);
  }

  /** The IRI behind a prefixed name or an absolute IRI, as a caller passes it back. */
  public String expand(final String name) {
    final String expanded = isAbsoluteIri(name) ? name : prefixes.expandPrefix(name);
    if (!ExplorationQueries.isQueryable(expanded)) {
      throw new IllegalArgumentException(
          "'%s' is not a relationship name. Pass a prefixed name such as %s, or an absolute http(s) IRI, exactly as a card's 'reachable' entry gives it."
              .formatted(name, EXAMPLE_RELATIONSHIP));
    }
    return expanded;
  }

  private static boolean isAbsoluteIri(final String name) {
    return name != null && name.contains(IRI_SCHEME_SEPARATOR);
  }

  private Set<String> withAncestors(final Collection<String> typeIris) {
    return typeIris.stream()
        .flatMap(type -> classAncestors.getOrDefault(type, Set.of(type)).stream())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Map<String, RelationshipAbout> readAbouts(final Model ontology) {
    final Map<String, RelationshipAbout> result = new HashMap<>();
    ontology
        .listSubjectsWithProperty(RDFS.label)
        .filterKeep(Resource::isURIResource)
        .forEach(subject -> result.put(subject.getURI(), aboutOf(subject)));
    return result;
  }

  private static RelationshipAbout aboutOf(final Resource subject) {
    return new RelationshipAbout(labelOf(subject), descriptiveComment(subject));
  }

  private static String labelOf(final Resource subject) {
    return literals(subject, RDFS.label).stream()
        .findFirst()
        .orElseGet(() -> localName(subject.getURI()));
  }

  /** The ontology's description of the term, ahead of the note that records projection status. */
  private static String descriptiveComment(final Resource subject) {
    final List<String> comments = literals(subject, RDFS.comment);
    return comments.stream()
        .filter(comment -> !comment.startsWith(PROJECTION_NOTE))
        .findFirst()
        .orElse(comments.isEmpty() ? null : comments.getFirst());
  }

  private static List<String> literals(final Resource subject, final Property property) {
    return subject
        .listProperties(property)
        .filterKeep(statement -> statement.getObject().isLiteral())
        .mapWith(statement -> statement.getString())
        .toList()
        .stream()
        .sorted()
        .toList();
  }

  private static String localName(final String iri) {
    final String name = ResourceFactory.createResource(iri).getLocalName();
    return nullOrEmpty(name) ? iri : name;
  }

  private static Map<String, Set<String>> readClassAncestors(final Model ontology) {
    final Map<String, Set<String>> result = new HashMap<>();
    ontology
        .listSubjectsWithProperty(RDFS.subClassOf)
        .filterKeep(Resource::isURIResource)
        .forEach(type -> result.put(type.getURI(), ancestorsOf(type)));
    return result;
  }

  private static Set<String> ancestorsOf(final Resource type) {
    final Set<String> seen = new LinkedHashSet<>();
    final Deque<Resource> pending = new ArrayDeque<>(List.of(type));
    while (!pending.isEmpty()) {
      final Resource current = pending.pop();
      if (current.isURIResource() && seen.add(current.getURI())) {
        current
            .listProperties(RDFS.subClassOf)
            .mapWith(s -> s.getResource())
            .forEach(pending::push);
      }
    }
    return Set.copyOf(seen);
  }

  private List<UnprojectedProperty> readUnprojected(final Model ontology) {
    return ontology
        .listSubjectsWithProperty(PROJECTION_STATUS, INFERENCE_ONLY)
        .filterKeep(Resource::isURIResource)
        .filterDrop(OntologyVocabulary::isCarriedElsewhere)
        .mapWith(this::unprojectedProperty)
        .toList()
        .stream()
        .sorted(Comparator.comparing(UnprojectedProperty::name))
        .toList();
  }

  private UnprojectedProperty unprojectedProperty(final Resource property) {
    return new UnprojectedProperty(
        compact(property.getURI()), labelOf(property), domainsOf(property));
  }

  private static boolean isCarriedElsewhere(final Resource property) {
    return property.hasLiteral(OWL2.deprecated, true)
        || hasStoredObject(property, RDFS.subPropertyOf)
        || hasStoredObject(property, OWL2.inverseOf)
        || property
            .getModel()
            .listSubjectsWithProperty(OWL2.inverseOf, property)
            .filterKeep(OntologyVocabulary::isStored)
            .hasNext();
  }

  private static boolean hasStoredObject(final Resource property, final Property link) {
    return property
        .listProperties(link)
        .mapWith(statement -> statement.getObject())
        .filterKeep(RDFNode::isResource)
        .filterKeep(node -> isStored(node.asResource()))
        .hasNext();
  }

  private static boolean isStored(final Resource property) {
    return property.hasProperty(PROJECTION_STATUS, STORED);
  }

  private static Set<String> domainsOf(final Resource property) {
    return property
        .listProperties(RDFS.domain)
        .mapWith(statement -> statement.getResource())
        .toList()
        .stream()
        .flatMap(OntologyVocabulary::namedClasses)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Stream<String> namedClasses(final Resource domain) {
    return domain.isURIResource()
        ? Stream.of(domain.getURI())
        : Optional.ofNullable(domain.getPropertyResourceValue(OWL2.unionOf))
            .map(members -> members.as(RDFList.class).asJavaList())
            .orElseGet(List::of)
            .stream()
            .filter(RDFNode::isURIResource)
            .map(member -> member.asResource().getURI());
  }

  /** {@code domains} is empty when the ontology does not restrict where the property applies. */
  private record UnprojectedProperty(String name, String label, Set<String> domains) {
    boolean appliesTo(final Set<String> classes) {
      return domains.isEmpty() || domains.stream().anyMatch(classes::contains);
    }
  }
}
