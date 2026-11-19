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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One property whose values are literals stored on a node. {@code type} is {@code integer}, {@code
 * decimal}, {@code boolean}, {@code dateTime} or {@code date}, absent for text. {@code value} is the
 * value, or the first in text order when there are several; {@code last} and {@code count} are
 * present only then. A value longer than {@link #VALUE_LIMIT} characters is cut and ends with
 * {@code …}, and {@code length} gives the full length of the longest value.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CardValue(
    String property, String type, String value, String last, Integer count, Integer length) {

  public static final int VALUE_LIMIT = 280;
}
