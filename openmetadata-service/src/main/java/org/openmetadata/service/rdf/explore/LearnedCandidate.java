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
 * One thing a turn taught, as {@code cite} receives it under {@code learned}, in the shape of the
 * AskCollate capture's candidates: the rule as its {@code title}, the general {@code question} it
 * answers, the self-contained {@code answer}, its {@code kind}, the card it is {@code about}
 * (absent for a personal one), the {@code evidence} and the shown memory it {@code corrects}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LearnedCandidate(
    String title,
    String question,
    String answer,
    LearnedKind kind,
    String about,
    Evidence evidence,
    String corrects) {

  /** Where a candidate's knowledge was read: the person's words or a tool result, quoted. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Evidence(EvidenceSource source, String quote, String tool) {}
}
