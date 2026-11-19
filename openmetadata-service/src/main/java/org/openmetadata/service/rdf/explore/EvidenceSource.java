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

/** Whose words a candidate's evidence quotes: the person's, or a tool result's. */
public enum EvidenceSource {
  USER,
  TOOL;

  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static EvidenceSource of(final String value) {
    return fromValue(value).orElse(null);
  }

  public static Optional<EvidenceSource> fromValue(final String value) {
    return Arrays.stream(values()).filter(source -> source.value().equals(value)).findFirst();
  }
}
