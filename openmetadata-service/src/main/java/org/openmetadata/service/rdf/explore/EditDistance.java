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

/** Levenshtein distance: the fewest single-character edits that turn one text into the other. */
final class EditDistance {

  private EditDistance() {}

  static int of(final String from, final String to) {
    int[] previous = new int[to.length() + 1];
    for (int column = 0; column <= to.length(); column++) {
      previous[column] = column;
    }
    for (int row = 1; row <= from.length(); row++) {
      final int[] current = new int[to.length() + 1];
      current[0] = row;
      for (int column = 1; column <= to.length(); column++) {
        final int substitution = from.charAt(row - 1) == to.charAt(column - 1) ? 0 : 1;
        current[column] =
            Math.min(
                Math.min(current[column - 1] + 1, previous[column] + 1),
                previous[column - 1] + substitution);
      }
      previous = current;
    }
    return previous[to.length()];
  }
}
