package org.openmetadata.service.rdf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A model exploring the graph reads the ontology to learn what an edge carries. Every predicate
 * the projection stores and every class it types nodes with must say so, and the domains and
 * ranges the ontology declares must hold for what the projection writes.
 */
class RdfOntologyDescriptionContractTest {
  private static final String OM = "https://open-metadata.org/ontology/";
  private static final String DQV = "http://www.w3.org/ns/dqv#";
  private static final String PROV = "http://www.w3.org/ns/prov#";

  // Temporary: terms whose comments, domains and ranges are written on branches still in flight
  // (keys and constraints, Ontology Studio bindings, data quality). Remove an entry once the
  // branch that owns it has merged.
  private static final Set<String> DESCRIBED_BY_OPEN_BRANCHES =
      Set.of(
          OM + "constraintType",
          OM + "relationshipType",
          OM + "hasConstraint",
          OM + "hasConstrainedColumn",
          OM + "hasReferredColumn",
          OM + "references",
          OM + "memberOf",
          OM + "memberCount",
          OM + "boundAsset",
          OM + "boundColumn",
          OM + "testedBy",
          OM + "testSuite",
          OM + "testSuites",
          OM + "tableProfilerConfig",
          OM + "databaseProfilerConfig",
          OM + "databaseSchemaProfilerConfig",
          OM + "latestResult",
          OM + "odcsQualityRules",
          OM + "qualityExpectations",
          OM + "rowCount",
          OM + "columnCount",
          DQV + "hasQualityMeasurement",
          DQV + "isMeasurementOf",
          DQV + "value",
          DQV + "computedOn",
          PROV + "generatedAtTime");

  private static Model projection;
  private static Model ontology;
  private static Set<String> emitted;

  @BeforeAll
  static void project() throws IOException {
    projection = RdfProjectionFixtures.project();
    ontology = RDFDataMgr.loadModel("rdf/ontology/openmetadata.ttl");
    emitted = RdfOntologyContractTest.predicates(projection);
    emitted.addAll(RdfWriterPredicates.constantPredicates());
  }

  @AfterAll
  static void close() {
    if (projection != null) projection.close();
    if (ontology != null) ontology.close();
  }

  @Test
  void everyStoredPredicateSaysWhatItCarries() {
    assertEquals(Set.of(), uncommented(storedPredicates(ontology), ontology));
  }

  @Test
  void removingTheCommentOfAStoredPredicateFails() {
    final Model changed = RDFDataMgr.loadModel("rdf/ontology/openmetadata.ttl");
    try {
      changed.removeAll(changed.createResource(OM + "hasOwner"), RDFS.comment, null);
      assertEquals(Set.of(OM + "hasOwner"), uncommented(storedPredicates(changed), changed));
    } finally {
      changed.close();
    }
  }

  @Test
  void everyProjectedClassIsDeclaredWithAComment() {
    final Set<String> undeclared = projectedClasses();
    undeclared.removeIf(
        uri -> ontology.contains(ontology.createResource(uri), RDF.type, OWL.Class));
    assertEquals(Set.of(), undeclared, "Projected types without an owl:Class declaration");
    assertEquals(Set.of(), uncommented(projectedClasses(), ontology));
  }

  @Test
  void declaredDomainsHoldForEveryTypedSubject() {
    final Set<String> violations = new TreeSet<>();
    for (Statement statement : projection.listStatements().toList()) {
      for (Resource domain : declared(statement.getPredicate(), RDFS.domain)) {
        if (!instanceOf(statement.getSubject(), domain)) {
          violations.add(violation(statement, domain, typesOf(statement.getSubject())));
        }
      }
    }
    assertEquals(Set.of(), violations, "Subjects outside the declared rdfs:domain");
  }

  /**
   * A literal range must match the literal's datatype, and a class range must not receive a literal.
   * Class ranges are not checked further: the fixtures point every entity reference at one shared
   * id, and a referenced node carries only the coarse type of its reference.
   */
  @Test
  void declaredRangesHoldForEveryProjectedValue() {
    final Set<String> violations = new TreeSet<>();
    for (Statement statement : projection.listStatements().toList()) {
      for (Resource range : declared(statement.getPredicate(), RDFS.range)) {
        if (!inRange(statement.getObject(), range)) {
          violations.add(violation(statement, range, observed(statement.getObject())));
        }
      }
    }
    assertEquals(Set.of(), violations, "Values outside the declared rdfs:range");
  }

  private static Set<String> storedPredicates(final Model declared) {
    final Property status = declared.createProperty(OM + "projectionStatus");
    final Resource stored = declared.createResource(OM + "Stored");
    final Set<String> predicates = RdfOntologyContractTest.declarations(declared);
    predicates.removeIf(
        uri ->
            !emitted.contains(uri)
                && !declared.contains(declared.createResource(uri), status, stored));
    return predicates;
  }

  private static Set<String> projectedClasses() {
    final Set<String> classes = new TreeSet<>();
    projection
        .listObjectsOfProperty(RDF.type)
        .filterKeep(type -> type.isURIResource() && type.asResource().getURI().startsWith(OM))
        .forEachRemaining(type -> classes.add(type.asResource().getURI()));
    return classes;
  }

  private static Set<String> uncommented(final Set<String> terms, final Model declared) {
    final Set<String> missing = new TreeSet<>(terms);
    missing.removeAll(DESCRIBED_BY_OPEN_BRANCHES);
    missing.removeIf(uri -> hasComment(declared, declared.createResource(uri)));
    return missing;
  }

  private static boolean hasComment(final Model declared, final Resource term) {
    return declared.listObjectsOfProperty(term, RDFS.comment).toList().stream()
        .anyMatch(value -> value.isLiteral() && !value.asLiteral().getString().isBlank());
  }

  private static List<Resource> declared(final Property predicate, final Property axis) {
    if (DESCRIBED_BY_OPEN_BRANCHES.contains(predicate.getURI())) {
      return List.of();
    }
    return ontology
        .listObjectsOfProperty(ontology.createResource(predicate.getURI()), axis)
        .filterKeep(RDFNode::isURIResource)
        .mapWith(RDFNode::asResource)
        .toList();
  }

  private static boolean instanceOf(final Resource node, final Resource type) {
    final List<Resource> types =
        projection.listObjectsOfProperty(node, RDF.type).mapWith(RDFNode::asResource).toList();
    return types.isEmpty()
        || types.stream().anyMatch(candidate -> superClasses(candidate).contains(type.getURI()));
  }

  private static Set<String> superClasses(final Resource start) {
    final Set<String> seen = new TreeSet<>();
    final List<Resource> frontier = new ArrayList<>(List.of(start));
    while (!frontier.isEmpty()) {
      final Resource next = ontology.createResource(frontier.remove(frontier.size() - 1).getURI());
      if (seen.add(next.getURI())) {
        ontology
            .listObjectsOfProperty(next, RDFS.subClassOf)
            .filterKeep(RDFNode::isURIResource)
            .forEachRemaining(parent -> frontier.add(parent.asResource()));
      }
    }
    return seen;
  }

  private static boolean inRange(final RDFNode object, final Resource range) {
    final boolean literalRange =
        range.getURI().startsWith(XSD.getURI()) || RDFS.Literal.equals(range);
    return literalRange
        ? object.isLiteral()
            && (RDFS.Literal.equals(range)
                || range.getURI().equals(object.asLiteral().getDatatypeURI()))
        : !object.isLiteral();
  }

  private static String observed(final RDFNode object) {
    return object.isLiteral()
        ? object.asLiteral().getDatatypeURI()
        : typesOf(object.asResource()).toString();
  }

  private static Set<String> typesOf(final Resource node) {
    return projection
        .listObjectsOfProperty(node, RDF.type)
        .mapWith(type -> type.asResource().getURI())
        .toSet();
  }

  private static String violation(
      final Statement statement, final Resource expected, final Object observed) {
    return statement.getPredicate().getURI()
        + " expects "
        + expected.getURI()
        + ", saw "
        + observed;
  }
}
