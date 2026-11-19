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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.openmetadata.schema.entity.context.ContextMemoryType;

/**
 * The four kinds of knowledge a turn may teach, each with the memory type the AskCollate capture
 * gives it: a correction or a definition is a Note, a preference a Preference, a discovery a
 * Learning.
 */
public enum LearnedKind {
  CORRECTION(ContextMemoryType.NOTE),
  DEFINITION(ContextMemoryType.NOTE),
  PREFERENCE(ContextMemoryType.PREFERENCE),
  LEARNING(ContextMemoryType.LEARNING);

  private final ContextMemoryType memoryType;

  LearnedKind(final ContextMemoryType memoryType) {
    this.memoryType = memoryType;
  }

  public ContextMemoryType memoryType() {
    return memoryType;
  }

  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static LearnedKind of(final String value) {
    return fromValue(value).orElse(null);
  }

  public static Optional<LearnedKind> fromValue(final String value) {
    return Arrays.stream(values()).filter(kind -> kind.value().equals(value)).findFirst();
  }
}
