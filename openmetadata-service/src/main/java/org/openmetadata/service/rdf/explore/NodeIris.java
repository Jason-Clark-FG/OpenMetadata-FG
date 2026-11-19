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

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.openmetadata.service.Entity;
import org.openmetadata.service.rdf.RdfUtils;

/** Maps between catalog identities and the IRIs the projection mints for them. */
final class NodeIris {

  private static final String ENTITY_PATH = "entity/";
  private static final char SEGMENT_SEPARATOR = '/';
  private static final String MEMORY = "contextMemory";

  private final String baseUri;

  NodeIris(final String baseUri) {
    final String configured = Objects.requireNonNull(baseUri, "baseUri");
    this.baseUri = configured.endsWith("/") ? configured : configured + "/";
  }

  /** The IRI the projection gives a search hit: by id for entities, by FQN for columns. */
  String of(final SearchHit hit) {
    return Entity.TABLE_COLUMN.equals(hit.entityType())
        ? RdfUtils.columnUri(baseUri, hit.fullyQualifiedName())
        : RdfUtils.entityUri(baseUri, hit.entityType(), hit.id());
  }

  /**
   * Whether {@code iri} names a catalog entity a memory can be about: one minted by id, as a
   * column or an ontology term is not, and not a memory itself.
   */
  boolean isAnchor(final String iri) {
    final String type = entityType(iri);
    return type != null && !MEMORY.equals(type) && entityId(iri).isPresent();
  }

  /** The id segment of a catalog entity IRI minted by id. */
  Optional<UUID> entityId(final String iri) {
    Optional<UUID> id = Optional.empty();
    try {
      id = Optional.of(UUID.fromString(iri.substring(iri.lastIndexOf(SEGMENT_SEPARATOR) + 1)));
    } catch (IllegalArgumentException notAnId) {
      // A column is minted by its qualified name, an ontology term by its own IRI.
    }
    return id;
  }

  /** The entity type segment of a catalog entity IRI, or null for any other node. */
  String entityType(final String iri) {
    final String prefix = baseUri + ENTITY_PATH;
    final int end = iri.startsWith(prefix) ? iri.indexOf(SEGMENT_SEPARATOR, prefix.length()) : -1;
    return end > prefix.length() ? iri.substring(prefix.length(), end) : null;
  }
}
