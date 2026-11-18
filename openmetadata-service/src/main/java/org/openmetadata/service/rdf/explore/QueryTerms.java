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

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.P_Path1;
import org.apache.jena.sparql.path.P_Path2;
import org.apache.jena.sparql.path.P_ReverseLink;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementVisitorBase;
import org.apache.jena.sparql.syntax.ElementWalker;
import org.apache.jena.vocabulary.RDF;

/**
 * The properties and classes a query's pattern names as IRIs: each predicate, every link of a
 * property path, and each object of {@code rdf:type}. A query that does not parse names none.
 */
record QueryTerms(Set<String> properties, Set<String> classes) {

  static QueryTerms of(final String sparql) {
    final QueryTerms terms = new QueryTerms(new LinkedHashSet<>(), new LinkedHashSet<>());
    try {
      Optional.ofNullable(QueryFactory.create(sparql, Syntax.syntaxARQ).getQueryPattern())
          .ifPresent(pattern -> ElementWalker.walk(pattern, terms.collector()));
    } catch (QueryException unparsable) {
      return new QueryTerms(Set.of(), Set.of());
    }
    return new QueryTerms(Set.copyOf(terms.properties), Set.copyOf(terms.classes));
  }

  private ElementVisitorBase collector() {
    return new ElementVisitorBase() {
      @Override
      public void visit(final ElementPathBlock block) {
        block.patternElts().forEachRemaining(QueryTerms.this::add);
      }
    };
  }

  private void add(final TriplePath triple) {
    if (triple.isTriple()) {
      addPredicate(triple.getPredicate(), triple.getObject());
    } else {
      addPath(triple.getPath());
    }
  }

  private void addPath(final Path path) {
    switch (path) {
      case P_Link link -> addProperty(link.getNode());
      case P_ReverseLink link -> addProperty(link.getNode());
      case P_Path1 single -> addPath(single.getSubPath());
      case P_Path2 pair -> {
        addPath(pair.getLeft());
        addPath(pair.getRight());
      }
      default -> {}
    }
  }

  private void addPredicate(final Node predicate, final Node object) {
    if (RDF.type.asNode().equals(predicate)) {
      if (object.isURI()) {
        classes.add(object.getURI());
      }
    } else {
      addProperty(predicate);
    }
  }

  private void addProperty(final Node property) {
    if (property.isURI()) {
      properties.add(property.getURI());
    }
  }
}
