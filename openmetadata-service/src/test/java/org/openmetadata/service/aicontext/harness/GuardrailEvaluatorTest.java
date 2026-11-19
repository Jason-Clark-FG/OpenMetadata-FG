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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.context.GuardrailAction;
import org.openmetadata.schema.api.context.GuardrailDecision;
import org.openmetadata.schema.entity.context.AgentHarnessGuardrail;
import org.openmetadata.schema.entity.context.AgentHarnessToolAccess;
import org.openmetadata.schema.entity.context.GuardrailLevel;
import org.openmetadata.schema.entity.context.ToolAccessDefault;
import org.openmetadata.schema.entity.context.ToolAccessLevel;
import org.openmetadata.schema.entity.context.ToolAccessServer;
import org.openmetadata.service.aicontext.harness.ContextItem.AssetRef;
import org.openmetadata.service.aicontext.harness.GuardrailEvaluators.Finding;
import org.openmetadata.service.aicontext.harness.GuardrailEvaluators.LiveAsset;
import org.openmetadata.service.aicontext.harness.ToolAccessPolicy.McpCall;

class GuardrailEvaluatorTest {
  private static final AssetRef ORDERS = new AssetRef("table", "svc.db.sales.orders");
  private static final LiveAsset HEALTHY =
      new LiveAsset(ORDERS, false, List.of(), false, 0, 0, true, false);

  @Test
  void aHealthyAssetIsAllowed() {
    final GuardrailDecision decision = decide(List.of(HEALTHY), List.of());

    assertEquals(GuardrailAction.ALLOW, decision.getAction());
    assertEquals(List.of(), decision.getReasons());
  }

  @Test
  void aDeprecatedAssetIsDeniedAtTheSeededBlockLevel() {
    final GuardrailDecision decision =
        decide(
            List.of(new LiveAsset(ORDERS, true, List.of(), false, 0, 0, true, false)), List.of());

    assertEquals(GuardrailAction.DENY, decision.getAction());
    assertEquals(List.of("table svc.db.sales.orders is deprecated."), decision.getReasons());
  }

  @Test
  void aDeprecatedAssetNamesItsOwnerAsTheWayToTheReplacement() {
    final LiveAsset owned =
        new LiveAsset(ORDERS, true, List.of(), false, 0, 0, true, false, List.of("Sales Data"));

    assertEquals(
        List.of(
            "table svc.db.sales.orders is deprecated. Ask the owner, Sales Data, for the"
                + " replacement."),
        decide(List.of(owned), List.of()).getReasons());
  }

  @Test
  void piiColumnsAreDeniedOnlyWhenTheyAreMaskedForTheCaller() {
    final LiveAsset masked =
        new LiveAsset(ORDERS, false, List.of("email", "ssn"), false, 0, 0, true, false);
    final LiveAsset visible =
        new LiveAsset(ORDERS, false, List.of("email", "ssn"), true, 0, 0, true, false);

    assertEquals(
        List.of(
            "table svc.db.sales.orders has PII.Sensitive columns that are masked for you: email,"
                + " ssn."),
        decide(List.of(masked), List.of()).getReasons());
    assertEquals(GuardrailAction.DENY, decide(List.of(masked), List.of()).getAction());
    assertEquals(GuardrailAction.ALLOW, decide(List.of(visible), List.of()).getAction());
  }

  @Test
  void openIncidentsAndUncertifiedAssetsWarnAtTheirSeededLevels() {
    final LiveAsset incidents = new LiveAsset(ORDERS, false, List.of(), false, 2, 1, true, false);
    final LiveAsset uncertified = new LiveAsset(ORDERS, false, List.of(), false, 0, 0, false, true);
    final LiveAsset uncertifiedAlone =
        new LiveAsset(ORDERS, false, List.of(), false, 0, 0, false, false);

    assertEquals(
        new GuardrailDecision()
            .withAction(GuardrailAction.WARN)
            .withReasons(List.of("table svc.db.sales.orders has 2 open data incident(s).")),
        decide(List.of(incidents), List.of()));
    assertEquals(
        new GuardrailDecision()
            .withAction(GuardrailAction.WARN)
            .withReasons(
                List.of(
                    "table svc.db.sales.orders is not certified, and a certified table shares its"
                        + " glossary terms. Prefer the certified table.")),
        decide(List.of(uncertified), List.of()));
    assertEquals(GuardrailAction.ALLOW, decide(List.of(uncertifiedAlone), List.of()).getAction());
  }

  @Test
  void theHarnessLevelDecidesOverTheSeed() {
    final LiveAsset deprecated = new LiveAsset(ORDERS, true, List.of(), false, 3, 0, true, false);

    assertEquals(
        new GuardrailDecision()
            .withAction(GuardrailAction.WARN)
            .withReasons(List.of("table svc.db.sales.orders is deprecated.")),
        decide(
            List.of(deprecated),
            List.of(
                level(GuardrailRegistry.BLOCK_DEPRECATED_ASSETS, GuardrailLevel.WARN),
                level(GuardrailRegistry.FLAG_OPEN_INCIDENTS, GuardrailLevel.OFF))));
  }

  @Test
  void theStrictestFindingDecidesAndEveryReasonIsKept() {
    final LiveAsset deprecatedWithIncidents =
        new LiveAsset(ORDERS, true, List.of(), false, 1, 0, true, false);

    assertEquals(
        new GuardrailDecision()
            .withAction(GuardrailAction.DENY)
            .withReasons(
                List.of(
                    "table svc.db.sales.orders is deprecated.",
                    "table svc.db.sales.orders has 1 open data incident(s).")),
        decide(List.of(deprecatedWithIncidents), List.of()));
  }

  @Test
  void toolAccessBlockDeniesAndWarnWarns() {
    final McpCall call = new McpCall("github", "create_issue");

    assertEquals(
        Optional.of(
            new Finding(
                GuardrailAction.DENY,
                "MCP tool create_issue on server github is blocked by the agent harness.")),
        GuardrailEvaluators.toolAccess(ToolAccessLevel.BLOCK, call));
    assertEquals(
        Optional.of(
            new Finding(
                GuardrailAction.WARN,
                "MCP tool create_issue on server github is flagged by the agent harness.")),
        GuardrailEvaluators.toolAccess(ToolAccessLevel.WARN, call));
    assertEquals(Optional.empty(), GuardrailEvaluators.toolAccess(ToolAccessLevel.ALLOW, call));
  }

  @Test
  void toolAccessTakesTheFirstMatchingEntryElseTheDefault() {
    final AgentHarnessToolAccess access =
        access(
            ToolAccessDefault.DENY,
            server("github", ToolAccessLevel.BLOCK, List.of("delete_repo")),
            server("GitHub", ToolAccessLevel.WARN, List.of()));

    assertEquals(
        ToolAccessLevel.BLOCK,
        ToolAccessPolicy.decide(access, new McpCall("github", "delete_repo")));
    assertEquals(
        ToolAccessLevel.WARN,
        ToolAccessPolicy.decide(access, new McpCall("github", "list_issues")));
    assertEquals(
        ToolAccessLevel.BLOCK, ToolAccessPolicy.decide(access, new McpCall("slack", "post")));
  }

  @Test
  void aPersonaLayerTightensTheDefaultAndNeverLoosensIt() {
    final AgentHarnessToolAccess defaults =
        access(ToolAccessDefault.ALLOW, server("slack", ToolAccessLevel.BLOCK, List.of()));
    final AgentHarnessToolAccess persona =
        access(ToolAccessDefault.ALLOW, server("github", ToolAccessLevel.WARN, List.of()));

    assertEquals(
        ToolAccessLevel.BLOCK,
        ToolAccessPolicy.decide(List.of(defaults, persona), new McpCall("slack", "post")));
    assertEquals(
        ToolAccessLevel.WARN,
        ToolAccessPolicy.decide(List.of(defaults, persona), new McpCall("github", "list")));
    assertEquals(
        ToolAccessLevel.ALLOW,
        ToolAccessPolicy.decide(List.of(defaults, persona), new McpCall("linear", "list")));
  }

  @Test
  void openMetadataToolsAreNeverRefused() {
    final AgentHarnessToolAccess denyAll = access(ToolAccessDefault.DENY);

    assertEquals(
        ToolAccessLevel.ALLOW,
        ToolAccessPolicy.decide(List.of(denyAll), new McpCall("openmetadata", "get_context")));
    assertEquals(
        ToolAccessLevel.ALLOW,
        ToolAccessPolicy.decide(
            List.of(denyAll), new McpCall("plugin_openmetadata_openmetadata", "harness_hook")));
  }

  @Test
  void hostToolNamesParseIntoServerAndTool() {
    assertEquals(
        Optional.of(new McpCall("github", "create_issue")),
        ToolAccessPolicy.parse("mcp__github__create_issue"));
    assertEquals(
        Optional.of(new McpCall("plugin_x_db", "run__query")),
        ToolAccessPolicy.parse("mcp__plugin_x_db__run__query"));
    assertEquals(Optional.empty(), ToolAccessPolicy.parse("Bash"));
    assertEquals(Optional.empty(), ToolAccessPolicy.parse("mcp__github__"));
  }

  @Test
  void aPersonaCannotLoosenTheDefaultsToolAccess() {
    final AgentHarnessToolAccess defaults =
        access(ToolAccessDefault.DENY, server("github", ToolAccessLevel.WARN, List.of()));

    assertThrows(
        IllegalArgumentException.class,
        () -> ToolAccessPolicy.requireNoLooserThan(access(ToolAccessDefault.ALLOW), defaults));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolAccessPolicy.requireNoLooserThan(
                access(ToolAccessDefault.DENY, server("github", ToolAccessLevel.ALLOW, List.of())),
                defaults));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolAccessPolicy.requireNoLooserThan(
                access(ToolAccessDefault.DENY, server("slack", ToolAccessLevel.WARN, List.of())),
                defaults));
    ToolAccessPolicy.requireNoLooserThan(
        access(ToolAccessDefault.DENY, server("github", ToolAccessLevel.BLOCK, List.of())),
        defaults);
  }

  private static GuardrailDecision decide(
      final List<LiveAsset> assets, final List<AgentHarnessGuardrail> levels) {
    return GuardrailEvaluators.decision(GuardrailEvaluators.evaluate(assets, levels));
  }

  private static AgentHarnessGuardrail level(final String id, final GuardrailLevel level) {
    return new AgentHarnessGuardrail().withId(id).withLevel(level);
  }

  private static AgentHarnessToolAccess access(
      final ToolAccessDefault defaultLevel, final ToolAccessServer... servers) {
    return new AgentHarnessToolAccess().withDefault(defaultLevel).withServers(List.of(servers));
  }

  private static ToolAccessServer server(
      final String match, final ToolAccessLevel level, final List<String> tools) {
    return new ToolAccessServer().withMatch(match).withLevel(level).withTools(tools);
  }
}
