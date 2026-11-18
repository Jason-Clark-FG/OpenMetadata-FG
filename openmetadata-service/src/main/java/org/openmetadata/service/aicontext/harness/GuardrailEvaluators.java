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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.openmetadata.schema.api.context.GuardrailAction;
import org.openmetadata.schema.api.context.GuardrailDecision;
import org.openmetadata.schema.entity.context.AgentHarnessGuardrail;
import org.openmetadata.schema.entity.context.GuardrailLevel;
import org.openmetadata.schema.entity.context.ToolAccessLevel;
import org.openmetadata.service.aicontext.harness.ContextItem.AssetRef;
import org.openmetadata.service.aicontext.harness.ToolAccessPolicy.McpCall;

/**
 * Decides a tool call from the guardrails and the MCP access the caller's harness sets. Each
 * guardrail looks at assets as they are now, read for the call, never at the context cache, so an
 * asset deprecated a minute ago is caught.
 */
public final class GuardrailEvaluators {
  /** The state of an asset a tool call names, read when the call is checked. */
  public record LiveAsset(
      AssetRef ref,
      boolean deprecated,
      List<String> piiColumns,
      boolean piiVisibleUnmasked,
      int openIncidents,
      int failedTests,
      boolean certified,
      boolean hasCertifiedAlternative) {
    public LiveAsset {
      piiColumns = List.copyOf(piiColumns);
    }
  }

  /** One guardrail or access rule that matched, with what it asks for and why. */
  public record Finding(GuardrailAction action, String reason) {}

  // Query cost ceiling has no evaluator: it needs the answer harness's cost estimate.
  private static final Map<String, Function<LiveAsset, Optional<String>>> EVALUATORS =
      Map.of(
          GuardrailRegistry.BLOCK_DEPRECATED_ASSETS, GuardrailEvaluators::deprecated,
          GuardrailRegistry.PII_COLUMNS_NEED_MASKING, GuardrailEvaluators::unmaskedPii,
          GuardrailRegistry.FLAG_OPEN_INCIDENTS, GuardrailEvaluators::openIncidents,
          GuardrailRegistry.PREFER_CERTIFIED_TABLES, GuardrailEvaluators::uncertified);

  private GuardrailEvaluators() {}

  /** What every guardrail that is not Off finds on the assets, in registry order. */
  public static List<Finding> evaluate(
      final List<LiveAsset> assets, final List<AgentHarnessGuardrail> levels) {
    final List<Finding> findings = new ArrayList<>();
    for (final String id : GuardrailRegistry.ids()) {
      final GuardrailLevel level = GuardrailRegistry.defaultLevel(levels, id);
      final Function<LiveAsset, Optional<String>> evaluator = EVALUATORS.get(id);
      if (level != GuardrailLevel.OFF && evaluator != null) {
        for (final LiveAsset asset : assets) {
          evaluator.apply(asset).ifPresent(reason -> findings.add(finding(level, reason)));
        }
      }
    }
    return findings;
  }

  /** What the MCP access rules say about a call, when they do more than allow it. */
  public static Optional<Finding> toolAccess(final ToolAccessLevel level, final McpCall call) {
    final String target = "MCP tool " + call.tool() + " on server " + call.server();
    return switch (level) {
      case ALLOW -> Optional.empty();
      case WARN -> Optional.of(
          new Finding(GuardrailAction.WARN, target + " is flagged by the agent harness."));
      case BLOCK -> Optional.of(
          new Finding(GuardrailAction.DENY, target + " is blocked by the agent harness."));
    };
  }

  /** The strictest action the findings ask for, with every reason. Allow when nothing matched. */
  public static GuardrailDecision decision(final List<Finding> findings) {
    final GuardrailAction action =
        findings.stream()
            .map(Finding::action)
            .max(Comparator.comparingInt(GuardrailEvaluators::strictness))
            .orElse(GuardrailAction.ALLOW);
    return new GuardrailDecision()
        .withAction(action)
        .withReasons(findings.stream().map(Finding::reason).toList());
  }

  private static Finding finding(final GuardrailLevel level, final String reason) {
    return new Finding(
        level == GuardrailLevel.BLOCK ? GuardrailAction.DENY : GuardrailAction.WARN, reason);
  }

  private static int strictness(final GuardrailAction action) {
    return switch (action) {
      case ALLOW -> 0;
      case WARN -> 1;
      case DENY -> 2;
    };
  }

  private static Optional<String> deprecated(final LiveAsset asset) {
    return asset.deprecated() ? Optional.of(name(asset) + " is deprecated.") : Optional.empty();
  }

  private static Optional<String> unmaskedPii(final LiveAsset asset) {
    return !listOrEmpty(asset.piiColumns()).isEmpty() && !asset.piiVisibleUnmasked()
        ? Optional.of(
            name(asset)
                + " has PII.Sensitive columns that are masked for you: "
                + String.join(", ", asset.piiColumns())
                + ".")
        : Optional.empty();
  }

  private static Optional<String> openIncidents(final LiveAsset asset) {
    return asset.openIncidents() > 0
        ? Optional.of(name(asset) + " has " + asset.openIncidents() + " open data incident(s).")
        : Optional.empty();
  }

  private static Optional<String> uncertified(final LiveAsset asset) {
    return !asset.certified() && asset.hasCertifiedAlternative()
        ? Optional.of(
            name(asset)
                + " is not certified, and a certified table shares its glossary terms. Prefer the"
                + " certified table.")
        : Optional.empty();
  }

  private static String name(final LiveAsset asset) {
    return asset.ref().entityType() + " " + asset.ref().fullyQualifiedName();
  }
}
