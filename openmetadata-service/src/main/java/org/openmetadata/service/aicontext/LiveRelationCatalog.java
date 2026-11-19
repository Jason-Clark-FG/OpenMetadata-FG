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

import jakarta.ws.rs.core.SecurityContext;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.TableConstraint;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.ConceptRelationsBuilder.ForeignKey;
import org.openmetadata.service.aicontext.ConceptRelationsBuilder.MappedTable;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.util.FullyQualifiedName;

/** The catalog behind {@link ConceptRelationsBuilder}, answering as the caller. */
@Slf4j
public final class LiveRelationCatalog implements ConceptRelationsBuilder.Catalog {
  static final int MAX_TABLES_CHECKED = 5;

  private final Authorizer authorizer;
  private final SecurityContext securityContext;

  public LiveRelationCatalog(final Authorizer authorizer, final SecurityContext securityContext) {
    this.authorizer = authorizer;
    this.securityContext = securityContext;
  }

  /** A catalog pages its search by a cursor it keeps, so each term needs its own. */
  private ConceptContextCatalog assets() {
    return new ConceptContextCatalog(authorizer, securityContext, table -> null, table -> null);
  }

  @Override
  public boolean canView(final String entityType, final String fqn) {
    return assets().canView(entityType, fqn);
  }

  /** The certified table the term is applied to, else the first table by name. */
  @Override
  public Optional<MappedTable> tableOf(final String termFqn) {
    Optional<MappedTable> mapped = Optional.empty();
    try {
      final EntityInterface<?> term =
          Entity.getEntityByName(Entity.GLOSSARY_TERM, termFqn, "", Include.NON_DELETED);
      final ConceptContextCatalog catalog = assets();
      final List<EntityReference> tables =
          catalog.candidates(term, 0).references().stream()
              .filter(reference -> Entity.TABLE.equals(reference.getType()))
              .filter(reference -> catalog.canView(Entity.TABLE, reference.getFullyQualifiedName()))
              .sorted(Comparator.comparing(EntityReference::getFullyQualifiedName))
              .limit(MAX_TABLES_CHECKED)
              .toList();
      mapped = pick(tables);
    } catch (EntityNotFoundException | IllegalStateException | UncheckedIOException exception) {
      LOG.warn("No table found for term {}: {}", termFqn, exception.getMessage());
    }
    return mapped;
  }

  private static Optional<MappedTable> pick(final List<EntityReference> tables) {
    MappedTable first = null;
    for (final EntityReference reference : tables) {
      final EntityInterface<?> table =
          Entity.getEntity(reference, "certification", Include.NON_DELETED);
      final boolean certified = LiveTrustReader.isCertified(table);
      if (certified) {
        return Optional.of(new MappedTable(reference.getFullyQualifiedName(), true));
      }
      first = first == null ? new MappedTable(reference.getFullyQualifiedName(), false) : first;
    }
    return Optional.ofNullable(first);
  }

  @Override
  public List<ForeignKey> foreignKeys(final String tableFqn) {
    final List<ForeignKey> keys = new ArrayList<>();
    try {
      final Table table =
          Entity.getEntityByName(Entity.TABLE, tableFqn, "tableConstraints", Include.NON_DELETED);
      for (final TableConstraint constraint :
          table.getTableConstraints() == null
              ? List.<TableConstraint>of()
              : table.getTableConstraints()) {
        if (constraint.getConstraintType() == TableConstraint.ConstraintType.FOREIGN_KEY
            && constraint.getColumns() != null
            && constraint.getReferredColumns() != null) {
          for (int index = 0;
              index
                  < Math.min(
                      constraint.getColumns().size(), constraint.getReferredColumns().size());
              index++) {
            final String referred = constraint.getReferredColumns().get(index);
            keys.add(
                new ForeignKey(
                    constraint.getColumns().get(index),
                    FullyQualifiedName.getTableFQN(referred),
                    FullyQualifiedName.getColumnName(referred)));
          }
        }
      }
    } catch (EntityNotFoundException exception) {
      LOG.debug("Table {} is gone", tableFqn);
    }
    return keys;
  }
}
