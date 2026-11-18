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
package org.openmetadata.service.aicontext.harness;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;

import java.util.List;
import java.util.Optional;
import org.openmetadata.schema.api.context.GuardrailDefinition;
import org.openmetadata.schema.entity.context.AgentHarnessGuardrail;
import org.openmetadata.schema.entity.context.GuardrailLevel;
import org.openmetadata.schema.entity.context.HookEvent;

/** The guardrails the server knows, in the order every listing shows them. */
public final class GuardrailRegistry {
  public static final String PREFER_CERTIFIED_TABLES = "preferCertifiedTables";
  public static final String BLOCK_DEPRECATED_ASSETS = "blockDeprecatedAssets";
  public static final String PII_COLUMNS_NEED_MASKING = "piiColumnsNeedMasking";
  public static final String FLAG_OPEN_INCIDENTS = "flagOpenIncidents";
  public static final String QUERY_COST_CEILING = "queryCostCeiling";

  private record Guardrail(
      String id, String name, String description, GuardrailLevel seedLevel, boolean available) {
    GuardrailDefinition definition() {
      return new GuardrailDefinition()
          .withId(id)
          .withName(name)
          .withDescription(description)
          .withDefaultLevel(seedLevel)
          .withEvent(HookEvent.PRE_TOOL_USE)
          .withAvailable(available);
    }
  }

  private static final List<Guardrail> GUARDRAILS =
      List.of(
          new Guardrail(
              PREFER_CERTIFIED_TABLES,
              "Prefer certified tables",
              "When a query touches a raw or staging table that has a certified equivalent.",
              GuardrailLevel.WARN,
              true),
          new Guardrail(
              BLOCK_DEPRECATED_ASSETS,
              "Block deprecated assets",
              "Stops reads of assets marked deprecated.",
              GuardrailLevel.BLOCK,
              true),
          new Guardrail(
              PII_COLUMNS_NEED_MASKING,
              "PII columns need masking",
              "Requires a masking policy before selecting PII-tagged columns.",
              GuardrailLevel.BLOCK,
              true),
          new Guardrail(
              FLAG_OPEN_INCIDENTS,
              "Flag open incidents",
              "Adds open data incidents on the assets used to the answer.",
              GuardrailLevel.WARN,
              true),
          // Needs a query cost estimate, which only the answer harness produces.
          new Guardrail(
              QUERY_COST_CEILING,
              "Query cost ceiling",
              "Warns when an estimated scan exceeds 500 GB.",
              GuardrailLevel.OFF,
              false));

  private GuardrailRegistry() {}

  public static List<String> ids() {
    return GUARDRAILS.stream().map(Guardrail::id).toList();
  }

  public static List<GuardrailDefinition> definitions() {
    return GUARDRAILS.stream().map(Guardrail::definition).toList();
  }

  public static boolean isRegistered(final String id) {
    return GUARDRAILS.stream().anyMatch(guardrail -> guardrail.id().equals(id));
  }

  /** The level every harness starts from, one entry per guardrail. */
  public static List<AgentHarnessGuardrail> seedLevels() {
    return GUARDRAILS.stream()
        .map(
            guardrail ->
                new AgentHarnessGuardrail().withId(guardrail.id()).withLevel(guardrail.seedLevel()))
        .toList();
  }

  public static Optional<GuardrailLevel> find(
      final List<AgentHarnessGuardrail> guardrails, final String id) {
    return listOrEmpty(guardrails).stream()
        .filter(guardrail -> id.equals(guardrail.getId()))
        .map(AgentHarnessGuardrail::getLevel)
        .findFirst();
  }

  /** The default harness's level of {@code id}, which is the seed level when it lists none. */
  public static GuardrailLevel defaultLevel(
      final List<AgentHarnessGuardrail> defaultGuardrails, final String id) {
    return find(defaultGuardrails, id).orElseGet(() -> seedLevel(id));
  }

  private static GuardrailLevel seedLevel(final String id) {
    return GUARDRAILS.stream()
        .filter(guardrail -> guardrail.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown guardrail: " + id))
        .seedLevel();
  }
}
