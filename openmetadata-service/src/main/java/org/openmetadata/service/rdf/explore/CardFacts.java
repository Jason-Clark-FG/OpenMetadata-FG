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
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Collects the fact rows of one node; one instance per node and request. */
final class CardFacts {

  private static final String ELLIPSIS = "…";

  private final Set<String> types = new TreeSet<>();
  private final Set<String> owners = new TreeSet<>();
  private String label;
  private String displayName;
  private String fullyQualifiedName;
  private String description;
  private String tier;
  private String certification;
  private String service;
  private Long dailyUsage;
  private Long weeklyUsage;
  private Long monthlyUsage;
  private Double weeklyPercentile;

  void addType(final FactValue value) {
    types.add(value.value());
  }

  void setLabel(final FactValue value) {
    label = value.value();
  }

  void setDisplayName(final FactValue value) {
    displayName = value.value();
  }

  void setFullyQualifiedName(final FactValue value) {
    fullyQualifiedName = value.value();
  }

  void setDescription(final FactValue value) {
    description = truncate(value.value());
  }

  void setTier(final FactValue value) {
    tier = value.tagName();
  }

  void setCertification(final FactValue value) {
    certification = value.value();
  }

  void addOwner(final FactValue value) {
    owners.add(value.name());
  }

  void setService(final FactValue value) {
    service = value.name();
  }

  void setDailyUsage(final FactValue value) {
    dailyUsage = Long.parseLong(value.value());
  }

  void setWeeklyUsage(final FactValue value) {
    weeklyUsage = Long.parseLong(value.value());
  }

  void setMonthlyUsage(final FactValue value) {
    monthlyUsage = Long.parseLong(value.value());
  }

  void setWeeklyPercentile(final FactValue value) {
    weeklyPercentile = Double.parseDouble(value.value());
  }

  Set<String> types() {
    return Set.copyOf(types);
  }

  String fullyQualifiedName() {
    return fullyQualifiedName;
  }

  String label() {
    return Stream.of(displayName, label, fullyQualifiedName)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  KeyFacts keyFacts() {
    final Usage usage = new Usage(dailyUsage, weeklyUsage, monthlyUsage, weeklyPercentile);
    return new KeyFacts(
        description,
        tier,
        certification,
        List.copyOf(owners),
        service,
        usage.isEmpty() ? null : usage);
  }

  /** Cuts at the last word boundary inside the limit and marks the cut. */
  static String truncate(final String text) {
    final boolean fits = text.length() <= KeyFacts.DESCRIPTION_LIMIT;
    final String head = fits ? text : text.substring(0, KeyFacts.DESCRIPTION_LIMIT);
    final int boundary = head.lastIndexOf(' ');
    final String cut = boundary > 0 ? head.substring(0, boundary) : head;
    return fits ? text : cut.stripTrailing() + ELLIPSIS;
  }
}
