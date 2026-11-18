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
import java.util.List;

/**
 * The facts a reader needs to tell nodes apart, each one only when the graph holds it. {@code
 * descriptionLength} is the full length of a description cut at {@link #DESCRIPTION_LIMIT}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record KeyFacts(
    String description,
    Integer descriptionLength,
    String tier,
    String certification,
    List<String> owners,
    String service,
    Usage usage) {

  public static final int DESCRIPTION_LIMIT = 280;

  public KeyFacts {
    owners = List.copyOf(owners);
  }
}
