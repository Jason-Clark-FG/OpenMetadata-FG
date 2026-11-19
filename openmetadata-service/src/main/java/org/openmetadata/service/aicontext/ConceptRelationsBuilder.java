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
package org.openmetadata.service.aicontext;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.openmetadata.schema.api.context.ConceptJoinStep;
import org.openmetadata.schema.api.context.ConceptRelation;
import org.openmetadata.schema.api.context.ConceptRelations;
import org.openmetadata.schema.api.context.ConceptRelationsTable;
import org.openmetadata.schema.api.context.ConceptRelationsTerm;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.TermRelation;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.answer.EntityLinks;
import org.openmetadata.service.util.FullyQualifiedName;

/**
 * How a glossary term connects to the terms related to it, which table stands for each, and, when
 * a target term is named, the foreign keys that join the two terms' tables. Everything is read
 * through a {@link Catalog} that answers as the caller, and a table the caller may not view is
 * neither named nor walked through.
 */
public final class ConceptRelationsBuilder {
  static final int MAX_RELATIONS = 8;
  static final int MAX_JOIN_HOPS = 3;
  static final int MAX_TABLES_READ = 12;

  /** The table that stands for a term. */
  public record MappedTable(String fqn, boolean certified) {}

  /** A declared foreign key: {@code column} of the table refers to {@code toColumn} of {@code toTable}. */
  public record ForeignKey(String column, String toTable, String toColumn) {}

  public interface Catalog {
    boolean canView(String entityType, String fqn);

    Optional<MappedTable> tableOf(String termFqn);

    List<ForeignKey> foreignKeys(String tableFqn);
  }

  private record Hop(String fromTable, ForeignKey key) {}

  private final EntityLinks links;
  private final Catalog catalog;

  public ConceptRelationsBuilder(final EntityLinks links, final Catalog catalog) {
    this.links = links;
    this.catalog = catalog;
  }

  /** {@code target} is the term the join path should lead to, or null for none. */
  public ConceptRelations build(final GlossaryTerm term, final GlossaryTerm target) {
    final List<TermRelation> visible =
        listOrEmpty(term.getRelatedTerms()).stream()
            .filter(relation -> relation.getTerm() != null)
            .filter(
                relation ->
                    catalog.canView(
                        Entity.GLOSSARY_TERM, relation.getTerm().getFullyQualifiedName()))
            .toList();
    final ConceptRelations result =
        new ConceptRelations()
            .withConcept(
                termOf(term.getName(), term.getDisplayName(), term.getFullyQualifiedName()))
            .withGlossary(glossaryName(term))
            .withDescription(ConceptCardBuilder.firstSentence(term.getDescription()))
            .withUrl(links.ofFqn(Entity.GLOSSARY_TERM, term.getFullyQualifiedName()))
            .withRelations(visible.stream().limit(MAX_RELATIONS).map(this::relation).toList())
            .withTotalRelations(visible.size());
    if (target != null) {
      result
          .withTarget(
              termOf(target.getName(), target.getDisplayName(), target.getFullyQualifiedName()))
          .withJoinPath(joinPath(term, target));
    }
    return result;
  }

  private ConceptRelation relation(final TermRelation relation) {
    final EntityReference other = relation.getTerm();
    final String label =
        relation.getRelationshipType() != null
                && !nullOrEmpty(relation.getRelationshipType().getDisplayName())
            ? relation.getRelationshipType().getDisplayName().toLowerCase()
            : ConceptCardBuilder.relationLabel(relation.getRelationType());
    return new ConceptRelation()
        .withRelation(label)
        .withTerm(termOf(other.getName(), other.getDisplayName(), other.getFullyQualifiedName()))
        .withTable(catalog.tableOf(other.getFullyQualifiedName()).map(this::table).orElse(null));
  }

  private ConceptRelationsTable table(final MappedTable mapped) {
    return new ConceptRelationsTable()
        .withFqn(mapped.fqn())
        .withName(lastPart(mapped.fqn()))
        .withUrl(links.ofFqn(Entity.TABLE, mapped.fqn()))
        .withCertified(mapped.certified());
  }

  private ConceptRelationsTerm termOf(
      final String name, final String displayName, final String fqn) {
    return new ConceptRelationsTerm()
        .withName(nullOrEmpty(displayName) ? name : displayName)
        .withFqn(fqn)
        .withUrl(links.ofFqn(Entity.GLOSSARY_TERM, fqn));
  }

  private static String glossaryName(final GlossaryTerm term) {
    final EntityReference glossary = term.getGlossary();
    return glossary == null
        ? null
        : nullOrEmpty(glossary.getDisplayName()) ? glossary.getName() : glossary.getDisplayName();
  }

  private List<ConceptJoinStep> joinPath(final GlossaryTerm term, final GlossaryTerm target) {
    final Optional<MappedTable> from = catalog.tableOf(term.getFullyQualifiedName());
    final Optional<MappedTable> to = catalog.tableOf(target.getFullyQualifiedName());
    List<ConceptJoinStep> path = List.of();
    if (from.isPresent() && to.isPresent()) {
      final List<Hop> forward = walk(from.get().fqn(), to.get().fqn());
      if (!forward.isEmpty()) {
        path = steps(forward, false);
      } else {
        final List<Hop> backward = walk(to.get().fqn(), from.get().fqn());
        path = backward.isEmpty() ? List.of() : steps(backward, true);
      }
    }
    return path;
  }

  /**
   * Breadth-first over declared foreign keys from {@code start}, reading at most {@value
   * #MAX_TABLES_READ} tables. Only the keys a table declares are followed, so a path that runs
   * against the keys is found by walking from its other end.
   */
  private List<Hop> walk(final String start, final String goal) {
    final Map<String, Hop> arrivedBy = new HashMap<>();
    final Set<String> seen = new HashSet<>(Set.of(start));
    final Deque<String> queue = new ArrayDeque<>(List.of(start));
    final Map<String, Integer> depth = new HashMap<>(Map.of(start, 0));
    int read = 0;
    boolean found = false;
    while (!queue.isEmpty() && !found && read < MAX_TABLES_READ) {
      final String current = queue.poll();
      if (depth.get(current) >= MAX_JOIN_HOPS) {
        continue;
      }
      read++;
      for (final ForeignKey key : catalog.foreignKeys(current)) {
        if (seen.add(key.toTable()) && catalog.canView(Entity.TABLE, key.toTable())) {
          arrivedBy.put(key.toTable(), new Hop(current, key));
          depth.put(key.toTable(), depth.get(current) + 1);
          queue.add(key.toTable());
          found = found || key.toTable().equals(goal);
        }
      }
    }
    final List<Hop> hops = new ArrayList<>();
    if (found) {
      String at = goal;
      while (!at.equals(start)) {
        final Hop hop = arrivedBy.get(at);
        hops.add(hop);
        at = hop.fromTable();
      }
      Collections.reverse(hops);
    }
    return hops;
  }

  /** The columns the path joins on, from the first term's table; consecutive repeats collapse. */
  private List<ConceptJoinStep> steps(final List<Hop> hops, final boolean reversed) {
    final List<String[]> ends = new ArrayList<>();
    for (final Hop hop : hops) {
      ends.add(new String[] {hop.fromTable(), hop.key().column()});
      ends.add(new String[] {hop.key().toTable(), hop.key().toColumn()});
    }
    if (reversed) {
      Collections.reverse(ends);
    }
    final List<ConceptJoinStep> steps = new ArrayList<>();
    for (final String[] end : ends) {
      final boolean repeat =
          !steps.isEmpty()
              && steps.get(steps.size() - 1).getTable().equals(lastPart(end[0]))
              && steps.get(steps.size() - 1).getColumn().equals(end[1]);
      if (!repeat) {
        steps.add(
            new ConceptJoinStep()
                .withTable(lastPart(end[0]))
                .withColumn(end[1])
                .withUrl(links.ofFqn(Entity.TABLE, end[0])));
      }
    }
    return steps;
  }

  private static String lastPart(final String fqn) {
    final String[] parts = FullyQualifiedName.split(fqn);
    return FullyQualifiedName.unquoteName(parts[parts.length - 1]);
  }
}
