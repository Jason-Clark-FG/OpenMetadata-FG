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
import java.util.Optional;

/**
 * A label for a node the graph gives none, such as a table constraint or lineage details: the name
 * of its om: class and, when it has one, the value that tells it apart ({@code TableConstraint
 * PRIMARY_KEY}).
 */
final class DerivedLabel {

  private static final String OM_PREFIX = "om:";
  private static final List<String> QUALIFIERS =
      List.of("om:constraintType", "om:lineageSource", "om:memberPosition");

  private DerivedLabel() {}

  static String of(final List<String> types, final List<CardValue> values) {
    final Optional<String> kind =
        types.stream()
            .filter(type -> type.startsWith(OM_PREFIX))
            .map(type -> type.substring(OM_PREFIX.length()))
            .findFirst();
    final Optional<String> qualifier =
        QUALIFIERS.stream()
            .flatMap(property -> values.stream().filter(value -> property.equals(value.property())))
            .map(CardValue::value)
            .findFirst();
    return kind.map(name -> qualifier.map(value -> name + " " + value).orElse(name)).orElse(null);
  }
}
