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

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;

/** Which end of a relationship the node is on: the subject (outgoing) or the object (incoming). */
public enum Direction {
  OUTGOING,
  INCOMING;

  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Direction fromValue(final String value) {
    return Arrays.stream(values())
        .filter(direction -> direction.value().equals(value))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "'direction' must be 'outgoing' or 'incoming'; got '%s'".formatted(value)));
  }
}
