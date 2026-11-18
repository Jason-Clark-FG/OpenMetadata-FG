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

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stored values that record how a node was kept rather than what it is: version stamps, when and
 * by whom it last changed, deleted flags, links back to the API. A card names them under {@code
 * bookkeeping} instead of spelling them out; {@code query} reads any of them whole.
 */
final class Bookkeeping {

  private static final String OM = "https://open-metadata.org/ontology/";
  private static final String DCT = "http://purl.org/dc/terms/";
  private static final Set<String> PROPERTIES =
      Set.of(
          DCT + "hasVersion",
          DCT + "modified",
          DCT + "created",
          DCT + "publisher",
          "http://www.w3.org/ns/dcat#version",
          OM + "isDeleted",
          OM + "deleted",
          OM + "updatedAt",
          OM + "updatedBy",
          OM + "version",
          OM + "href",
          OM + "fqnHash",
          OM + "processedLineage");

  private Bookkeeping() {}

  /** The bookkeeping properties under the names cards give them. */
  static Set<String> namesIn(final OntologyVocabulary vocabulary) {
    return PROPERTIES.stream().map(vocabulary::compact).collect(Collectors.toUnmodifiableSet());
  }
}
