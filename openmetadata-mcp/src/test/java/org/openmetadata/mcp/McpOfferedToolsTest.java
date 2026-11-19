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
package org.openmetadata.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.dropwizard.core.setup.Environment;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openmetadata.mcp.prompts.DefaultPromptsContext;
import org.openmetadata.mcp.tools.DefaultToolContext;
import org.openmetadata.mcp.usage.McpUsageRecorder;
import org.openmetadata.schema.api.configuration.MCPConfiguration;
import org.openmetadata.service.OpenMetadataApplication;
import org.openmetadata.service.OpenMetadataApplicationConfig;
import org.openmetadata.service.apps.AbstractNativeApplication;
import org.openmetadata.service.apps.ApplicationContext;
import org.openmetadata.service.apps.InvalidMcpConfigurationException;
import org.openmetadata.service.security.JwtFilter;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.auth.SecurityConfigurationManager;

/** A deployment can offer only some MCP tools: the listing, the refusal and the start check. */
class McpOfferedToolsTest {

  private static final String TOKEN = "Bearer offered-tools-test";
  private static final String KNOWLEDGE_GRAPH = "knowledge_graph";

  private static final McpSchema.CallToolResult SUCCESS =
      McpSchema.CallToolResult.builder()
          .content(List.of(McpSchema.TextContent.builder("{}").build()))
          .isError(false)
          .build();

  /** The registered tools, with knowledge_graph, which a server without RDF leaves out. */
  private static List<String> registered() {
    return Stream.concat(
            new McpServer().getTools().stream().map(McpSchema.Tool::name),
            Stream.of(KNOWLEDGE_GRAPH))
        .toList();
  }

  @Test
  void anUnsetOrBlankSettingOffersEveryToolAsBefore() {
    final List<McpSchema.Tool> tools = new McpServer().getTools();

    for (final String setting : new String[] {null, "", "  "}) {
      assertThat(listed(tools, OfferedTools.parse(setting, registered())))
          .isEqualTo(tools.stream().map(McpSchema.Tool::name).toList());
    }
  }

  @Test
  void aListOffersExactlyTheToolsItNames() {
    final OfferedTools offered =
        OfferedTools.parse(" search_metadata , get_entity_details,", registered());

    assertThat(listed(new McpServer().getTools(), offered))
        .containsExactlyInAnyOrder("search_metadata", "get_entity_details");
  }

  @Test
  void aCallToAToolTheServerDoesNotOfferIsRefusedAsNotOffered() {
    final DefaultToolContext toolContext = mock(DefaultToolContext.class);
    final McpServer server = new McpServer(toolContext, new DefaultPromptsContext());
    server.jwtFilter = jwtFilterFor("alice");
    server.offeredTools = OfferedTools.parse("get_entity_details", registered());

    final McpSchema.CallToolResult result = call(server, "search_metadata");

    assertThat(result.isError()).isTrue();
    final Map<?, ?> error = (Map<?, ?>) result.structuredContent();
    assertThat(error.get("error").toString())
        .contains("search_metadata is not offered by this MCP server")
        .doesNotContainIgnoringCase("forbidden")
        .doesNotContainIgnoringCase("not found");
    assertThat(error.get("statusCode")).isEqualTo(400);
    verify(toolContext, never()).callToolWithMetadata(any(), any(), any(), any(), any());
  }

  @Test
  void anOfferedToolStillRuns() {
    final DefaultToolContext toolContext = mock(DefaultToolContext.class);
    when(toolContext.callToolWithMetadata(any(), any(), any(), any(), any()))
        .thenReturn(new DefaultToolContext.CallToolOutcome(SUCCESS, 0L, null));
    final McpServer server = new McpServer(toolContext, new DefaultPromptsContext());
    server.jwtFilter = jwtFilterFor("alice");
    server.offeredTools = OfferedTools.parse("search_metadata", registered());

    assertThat(call(server, "search_metadata")).isSameAs(SUCCESS);
  }

  @Test
  void aNameTheServerDoesNotRegisterIsRejectedNamingItAndWhatIsRegistered() {
    assertThatThrownBy(() -> OfferedTools.parse("search_metadata,no_such_tool", registered()))
        .isInstanceOf(InvalidMcpConfigurationException.class)
        .hasMessageContaining("no_such_tool")
        .hasMessageContaining("MCP_OFFERED_TOOLS")
        .hasMessageContaining("search_metadata")
        .hasMessageContaining("rdf.enabled");
  }

  @Test
  void theServerRefusesToStartWithAnUnknownNameBeforeServingAnything() {
    final OpenMetadataApplicationConfig config = new OpenMetadataApplicationConfig();
    config.setMcpOfferedTools("no_such_tool");
    final Environment environment = mock(Environment.class);
    final ApplicationContext apps = mock(ApplicationContext.class);
    when(apps.getAppIfExists("McpApplication")).thenReturn(mock(AbstractNativeApplication.class));

    try (MockedStatic<ApplicationContext> context = mockStatic(ApplicationContext.class)) {
      context.when(ApplicationContext::getInstance).thenReturn(apps);

      assertThatThrownBy(() -> new StartingApplication().registerMcp(config, environment))
          .isInstanceOf(InvalidMcpConfigurationException.class)
          .hasMessageContaining("no_such_tool");
    }
    verifyNoInteractions(environment);
  }

  @Test
  void initializeTellsAModelWithOnlyTheKnowledgeGraphToolHowToUseIt() {
    final String instructions =
        initialize(OfferedTools.parse(KNOWLEDGE_GRAPH, registered())).instructions();

    assertThat(instructions).contains("knowledge_graph", "find", "expand", "cite");
    assertThat(instructions).contains("failing tests", "open incidents", "in one call", "noRows");
    assertThat(instructions)
        .contains("short form", "full cards", "matched", "rest", "assignee and severity");
    assertThat(instructions).contains("notes people saved", "preferences", "name it");
    assertThat(instructions.length()).isLessThan(800);
    assertThat(initialize(OfferedTools.ALL).instructions()).isNull();
    assertThat(
            initialize(OfferedTools.parse(KNOWLEDGE_GRAPH + ",search_metadata", registered()))
                .instructions())
        .isNull();
  }

  private static McpSchema.InitializeResult initialize(final OfferedTools offered) {
    final McpStatelessServerTransport transport = mock(McpStatelessServerTransport.class);
    when(transport.protocolVersions()).thenReturn(List.of("2025-11-25"));
    McpServer.buildServer(
        transport, new McpServer().buildServerCapabilities(), MCPConfiguration::new, offered);
    final ArgumentCaptor<McpStatelessServerHandler> handler =
        ArgumentCaptor.forClass(McpStatelessServerHandler.class);
    verify(transport).setMcpHandler(handler.capture());
    final McpSchema.JSONRPCResponse response =
        handler
            .getValue()
            .handleRequest(
                McpTransportContext.EMPTY,
                new McpSchema.JSONRPCRequest(
                    McpSchema.METHOD_INITIALIZE,
                    1,
                    Map.of(
                        "protocolVersion",
                        "2025-11-25",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "test", "version", "1"))))
            .block();
    return (McpSchema.InitializeResult) response.result();
  }

  private static List<String> listed(final List<McpSchema.Tool> tools, final OfferedTools offered) {
    final var filter = McpServer.toolFilter(MCPConfiguration::new, offered);
    return tools.stream()
        .filter(tool -> filter.isVisible(McpTransportContext.EMPTY, tool))
        .map(McpSchema.Tool::name)
        .toList();
  }

  private static McpSchema.CallToolResult call(final McpServer server, final String toolName) {
    final McpSchema.Tool tool =
        new McpServer()
            .getTools().stream()
                .filter(candidate -> candidate.name().equals(toolName))
                .findFirst()
                .orElseThrow();
    final McpSchema.CallToolRequest request =
        McpSchema.CallToolRequest.builder(toolName).arguments(Map.of()).build();
    try (MockedStatic<SecurityConfigurationManager> config =
            mockStatic(SecurityConfigurationManager.class);
        MockedStatic<McpUsageRecorder> usage = mockStatic(McpUsageRecorder.class)) {
      config.when(SecurityConfigurationManager::getCurrentMcpConfig).thenReturn(null);
      return server
          .getTool(tool)
          .callHandler()
          .apply(
              McpTransportContext.create(
                  Map.of(AuthEnrichedMcpContextExtractor.AUTHORIZATION_HEADER, TOKEN)),
              request);
    }
  }

  private static JwtFilter jwtFilterFor(final String userName) {
    final CatalogSecurityContext securityContext = mock(CatalogSecurityContext.class);
    when(securityContext.getUserPrincipal()).thenReturn(() -> userName);
    final JwtFilter jwtFilter = mock(JwtFilter.class);
    when(jwtFilter.getCatalogSecurityContext(TOKEN)).thenReturn(securityContext);
    return jwtFilter;
  }

  /** Reaches the server's own MCP registration, as a start does. */
  private static final class StartingApplication extends OpenMetadataApplication {
    void registerMcp(final OpenMetadataApplicationConfig config, final Environment environment) {
      registerMCPServer(config, environment);
    }
  }
}
