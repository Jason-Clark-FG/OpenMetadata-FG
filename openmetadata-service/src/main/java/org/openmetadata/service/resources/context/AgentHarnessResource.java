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
package org.openmetadata.service.resources.context;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;
import org.openmetadata.schema.api.configuration.MCPConfiguration;
import org.openmetadata.schema.api.configuration.OpenMetadataBaseUrlConfiguration;
import org.openmetadata.schema.api.context.AgentHarnessManagedSettings;
import org.openmetadata.schema.api.context.AgentHarnessPersonaSummary;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.api.context.AgentHarnessSurfaces;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.api.context.GuardrailDefinition;
import org.openmetadata.schema.api.context.PreviewAgentHarness;
import org.openmetadata.schema.api.context.SkillDefinition;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.settings.SettingsType;
import org.openmetadata.schema.type.EntityHistory;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.utils.ResultList;
import org.openmetadata.schema.utils.VersionUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.OpenMetadataApplicationConfig;
import org.openmetadata.service.aicontext.PersonaContextBuilder;
import org.openmetadata.service.aicontext.PersonaContextCache;
import org.openmetadata.service.aicontext.agents.SubAgentDefinition;
import org.openmetadata.service.aicontext.agents.SubAgentRegistry;
import org.openmetadata.service.aicontext.harness.AgentHarnessCompiler;
import org.openmetadata.service.aicontext.harness.AgentHarnessService;
import org.openmetadata.service.aicontext.harness.GuardrailRegistry;
import org.openmetadata.service.aicontext.harness.HarnessContexts;
import org.openmetadata.service.aicontext.harness.PluginBuilder;
import org.openmetadata.service.aicontext.harness.SkillRegistry;
import org.openmetadata.service.jdbi3.AgentHarnessRepository;
import org.openmetadata.service.jdbi3.PersonaRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.resources.Collection;
import org.openmetadata.service.resources.EntityResource;
import org.openmetadata.service.resources.settings.SettingsCache;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.SecurityConfigurationManager;

/**
 * Administrators' API for agent harnesses. The generic entity writes are not exposed, so every
 * change goes through the draft and its validation, and only a publish changes what agents
 * receive.
 */
@Path("/v1/agentHarness")
@Tag(
    name = "Agent Harness",
    description =
        "The context AI agents receive from OpenMetadata: the company default and the persona "
            + "harnesses that extend it.")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Collection(name = "agentHarness")
public class AgentHarnessResource extends EntityResource<AgentHarness, AgentHarnessRepository> {
  public static final String COLLECTION_PATH = "/v1/agentHarness";
  private static final String UUID_PATTERN = "{id:[0-9a-fA-F\\-]{36}}";
  private static final String MCP_PATH = "/mcp";
  private static final String PLUGIN_TARGET_CLAUDE = "claude";
  private static final String VERSION_RESOURCE = "/catalog/VERSION";

  private final AgentHarnessService service;

  public AgentHarnessResource(final Authorizer authorizer, final Limits limits) {
    super(Entity.AGENT_HARNESS, authorizer, limits);
    service =
        new AgentHarnessService(
            repository,
            (PersonaRepository) Entity.getEntityRepository(Entity.PERSONA),
            new AgentHarnessCompiler(repository, authorizer));
  }

  @Override
  public void initialize(final OpenMetadataApplicationConfig config) {
    service.seedDefault();
    PersonaContextCache.useDefinitionSource(new HarnessContexts(repository)::definitionFor);
  }

  @POST
  @Path("/compile")
  @Operation(
      operationId = "compileAgentContext",
      summary = "Compile the context the caller's agent receives at a hook event",
      description =
          "For any signed-in user, about themselves. The harness comes from the caller's active "
              + "persona and is never named in the request.")
  public CompiledAgentContext compile(
      @Context final SecurityContext securityContext, @NotNull final CompileAgentContext request) {
    return service.compile(securityContext, request);
  }

  @GET
  @Path("/managedSettings")
  @Operation(
      operationId = "getAgentHarnessManagedSettings",
      summary = "Agent host settings that enforce the default harness's MCP access")
  public AgentHarnessManagedSettings getManagedSettings(
      @Context final UriInfo uriInfo, @Context final SecurityContext securityContext) {
    authorizer.authorizeAdmin(securityContext);
    return service.managedSettings(mcpServerUrl(uriInfo));
  }

  @GET
  @Path("/plugin")
  @Produces("application/zip")
  @Operation(
      operationId = "getAgentHarnessPlugin",
      summary = "Download the OpenMetadata plugin for an AI agent host",
      description =
          "A zip of the skill, MCP connector and hooks, built per request from the server URL and "
              + "version. It holds no harness content and no secrets, so any signed-in user may "
              + "download it.")
  public Response getPlugin(
      @Context final UriInfo uriInfo,
      @Context final SecurityContext securityContext,
      @QueryParam("target") @DefaultValue("claude") final String target) {
    if (!PLUGIN_TARGET_CLAUDE.equals(target)) {
      throw new ClientErrorException("Unsupported plugin target: " + target, 400);
    }
    final String version = VersionUtils.getOpenMetadataServerVersion(VERSION_RESOURCE).getVersion();
    return Response.ok(PluginBuilder.build(mcpServerUrl(uriInfo), version))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + PluginBuilder.PLUGIN_NAME + ".zip\"")
        .build();
  }

  @GET
  @Path("/surfaces")
  @Operation(
      operationId = "getAgentHarnessSurfaces",
      summary = "AI surfaces, the channels each supports, and whether each has connected")
  public AgentHarnessSurfaces getSurfaces(@Context final SecurityContext securityContext) {
    authorizer.authorizeAdmin(securityContext);
    return service.surfaces();
  }

  @POST
  @Path("/" + UUID_PATTERN + "/preview")
  @Operation(
      operationId = "previewAgentHarness",
      summary = "Preview what the calling administrator's agent would receive",
      description =
          "Compiles the request's settings, else the draft, else the published settings, without "
              + "the cache.")
  public CompiledAgentContext preview(
      @Context final SecurityContext securityContext,
      @PathParam("id") final UUID id,
      @QueryParam("event") @DefaultValue("SessionStart") final String event,
      final PreviewAgentHarness request) {
    authorizer.authorizeAdmin(securityContext);
    return service.preview(id, compileEvent(event), request, securityContext);
  }

  @POST
  @Path("/" + UUID_PATTERN + "/rules/preview")
  @Operation(
      operationId = "previewAgentHarnessRule",
      summary = "Preview the entities a context rule selects")
  public PersonaContextBuilder.RulePreview previewRule(
      @Context final SecurityContext securityContext,
      @PathParam("id") final UUID id,
      @NotNull @Valid final ContextRule rule) {
    authorizer.authorizeAdmin(securityContext);
    return service.previewRule(id, rule);
  }

  @Override
  protected List<MetadataOperation> getEntitySpecificOperations() {
    return List.of();
  }

  @GET
  @Path("/default")
  @Operation(
      operationId = "getDefaultAgentHarness",
      summary = "Get the company default agent harness with its draft")
  public AgentHarnessState getDefault(@Context final SecurityContext securityContext) {
    authorizer.authorizeAdmin(securityContext);
    return service.defaultState();
  }

  @GET
  @Path("/persona/{personaFqn}")
  @Operation(
      operationId = "getPersonaAgentHarness",
      summary = "Get a persona's agent harness with its draft")
  public AgentHarnessState getForPersona(
      @Context final SecurityContext securityContext,
      @Parameter(description = "Persona fully qualified name") @PathParam("personaFqn")
          final String personaFqn) {
    authorizer.authorizeAdmin(securityContext);
    return service.personaState(personaFqn);
  }

  @POST
  @Path("/persona/{personaFqn}")
  @Operation(
      operationId = "createPersonaAgentHarness",
      summary = "Create a persona's agent harness",
      description = "Creates an unpublished harness whose draft adds nothing to the default.")
  public Response createForPersona(
      @Context final SecurityContext securityContext,
      @Parameter(description = "Persona fully qualified name") @PathParam("personaFqn")
          final String personaFqn) {
    authorizer.authorizeAdmin(securityContext);
    return Response.status(Response.Status.CREATED)
        .entity(service.createForPersona(personaFqn, userName(securityContext)))
        .build();
  }

  @GET
  @Path("/personas")
  @Operation(
      operationId = "listAgentHarnessPersonas",
      summary = "List personas with their agent harness",
      description =
          "Pages exactly like `GET /v1/personas`. Context figures come from the context cache and "
              + "are never compiled for the list.")
  public ResultList<AgentHarnessPersonaSummary> listPersonas(
      @Context final UriInfo uriInfo,
      @Context final SecurityContext securityContext,
      @DefaultValue("10")
          @Min(value = 1, message = "must be greater than or equal to 1")
          @Max(value = 1000, message = "must be less than or equal to 1000")
          @QueryParam("limit")
          final int limit,
      @QueryParam("before") final String before,
      @QueryParam("after") final String after) {
    authorizer.authorizeAdmin(securityContext);
    return service.personaSummaries(uriInfo, limit, before, after);
  }

  @PUT
  @Path("/" + UUID_PATTERN + "/draft")
  @Operation(
      operationId = "putAgentHarnessDraft",
      summary = "Replace a harness's draft",
      description =
          "Takes the harness's own settings whole. `If-Match` must carry the `draftRevision` the "
              + "caller read; a stale revision answers 412.")
  public AgentHarnessState putDraft(
      @Context final SecurityContext securityContext,
      @PathParam("id") final UUID id,
      @HeaderParam(HttpHeaders.IF_MATCH) final String ifMatch,
      @NotNull final AgentHarnessSpec spec) {
    authorizer.authorizeAdmin(securityContext);
    final Integer revision = draftRevision(ifMatch);
    if (revision == null) {
      throw new ClientErrorException(
          "If-Match with the draftRevision is required", Response.Status.PRECONDITION_REQUIRED);
    }
    return service.writeDraft(id, revision, spec, userName(securityContext));
  }

  @DELETE
  @Path("/" + UUID_PATTERN + "/draft")
  @Operation(operationId = "deleteAgentHarnessDraft", summary = "Discard a harness's draft")
  public AgentHarnessState deleteDraft(
      @Context final SecurityContext securityContext,
      @PathParam("id") final UUID id,
      @HeaderParam(HttpHeaders.IF_MATCH) final String ifMatch) {
    authorizer.authorizeAdmin(securityContext);
    return service.discardDraft(id, draftRevision(ifMatch), userName(securityContext));
  }

  @POST
  @Path("/" + UUID_PATTERN + "/publish")
  @Operation(
      operationId = "publishAgentHarness",
      summary = "Publish a harness's draft",
      description = "Makes the draft the published harness as one version and removes the draft.")
  public AgentHarnessState publish(
      @Context final SecurityContext securityContext,
      @PathParam("id") final UUID id,
      @HeaderParam(HttpHeaders.IF_MATCH) final String ifMatch) {
    authorizer.authorizeAdmin(securityContext);
    return service.publish(id, draftRevision(ifMatch), userName(securityContext));
  }

  @PUT
  @Path("/" + UUID_PATTERN + "/enabled")
  @Operation(
      operationId = "setAgentHarnessEnabled",
      summary = "Turn a harness on or off",
      description = "Takes effect at once, without a draft. The body is `true` or `false`.")
  public AgentHarnessState setEnabled(
      @Context final SecurityContext securityContext,
      @PathParam("id") final UUID id,
      @NotNull final Boolean enabled) {
    authorizer.authorizeAdmin(securityContext);
    return service.setEnabled(id, enabled, userName(securityContext));
  }

  @GET
  @Path("/" + UUID_PATTERN + "/versions")
  @Operation(operationId = "listAgentHarnessVersions", summary = "List a harness's publishes")
  public EntityHistory listVersions(
      @Context final SecurityContext securityContext, @PathParam("id") final UUID id) {
    authorizer.authorizeAdmin(securityContext);
    return service.versions(id);
  }

  @GET
  @Path("/" + UUID_PATTERN + "/versions/{version}")
  @Operation(operationId = "getAgentHarnessVersion", summary = "Get one published version")
  public AgentHarness getVersion(
      @Context final SecurityContext securityContext,
      @PathParam("id") final UUID id,
      @PathParam("version") final String version) {
    authorizer.authorizeAdmin(securityContext);
    return service.version(id, version);
  }

  @GET
  @Path("/guardrails")
  @Operation(operationId = "listAgentHarnessGuardrails", summary = "List registered guardrails")
  public List<GuardrailDefinition> listGuardrails(@Context final SecurityContext securityContext) {
    authorizer.authorizeAdmin(securityContext);
    return GuardrailRegistry.definitions();
  }

  @GET
  @Path("/skills")
  @Operation(operationId = "listAgentHarnessSkills", summary = "List registered skills")
  public List<SkillDefinition> listSkills(@Context final SecurityContext securityContext) {
    authorizer.authorizeAdmin(securityContext);
    return SkillRegistry.definitions();
  }

  @GET
  @Path("/subAgents")
  @Operation(
      operationId = "listAgentHarnessSubAgents",
      summary = "List the sub-agents knowledge_graph can delegate to")
  public List<SubAgentDefinition> listSubAgents(@Context final SecurityContext securityContext) {
    authorizer.authorizeAdmin(securityContext);
    return SubAgentRegistry.getInstance().agents();
  }

  // EntityResource serves these to anyone with view access. A harness is for administrators.
  @Override
  @GET
  @Path("/{id}/context")
  @Operation(operationId = "getAgentHarnessAiContextById", hidden = true)
  public Response getAiContextById(
      @Context final UriInfo uriInfo,
      @Context final SecurityContext securityContext,
      @PathParam("id") final UUID id,
      @QueryParam("format") @DefaultValue("markdown") final String format,
      @QueryParam("query") final String query) {
    authorizer.authorizeAdmin(securityContext);
    return Response.status(Response.Status.NOT_FOUND).build();
  }

  @Override
  @GET
  @Path("/name/{fqn}/context")
  @Operation(operationId = "getAgentHarnessAiContextByName", hidden = true)
  public Response getAiContextByName(
      @Context final UriInfo uriInfo,
      @Context final SecurityContext securityContext,
      @PathParam("fqn") final String fqn,
      @QueryParam("format") @DefaultValue("markdown") final String format,
      @QueryParam("query") final String query) {
    authorizer.authorizeAdmin(securityContext);
    return Response.status(Response.Status.NOT_FOUND).build();
  }

  @Override
  @GET
  @Path("/history")
  @Operation(operationId = "listAgentHarnessHistoryByTimestamp", hidden = true)
  public ResultList<AgentHarness> listEntityHistoryByTimestamp(
      @Context final UriInfo uriInfo,
      @Context final SecurityContext securityContext,
      @QueryParam("startTs") final long startTs,
      @QueryParam("endTs") final long endTs,
      @DefaultValue("10") @QueryParam("limit") final int limit,
      @QueryParam("before") final String before,
      @QueryParam("after") final String after) {
    authorizer.authorizeAdmin(securityContext);
    return super.listEntityHistoryByTimestamp(
        uriInfo, securityContext, startTs, endTs, limit, before, after);
  }

  private static CompileEvent compileEvent(final String event) {
    try {
      return CompileEvent.fromValue(event);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "event must be SessionStart, UserPromptSubmit, PreToolUse or PostToolUse: " + event,
          exception);
    }
  }

  /** The MCP endpoint agents connect to: the MCP base URL, else the OpenMetadata URL. */
  private static String mcpServerUrl(final UriInfo uriInfo) {
    final MCPConfiguration mcp = SecurityConfigurationManager.getCurrentMcpConfig();
    final String configured =
        mcp != null && !nullOrEmpty(mcp.getBaseUrl())
            ? mcp.getBaseUrl()
            : SettingsCache.getSetting(
                    SettingsType.OPEN_METADATA_BASE_URL_CONFIGURATION,
                    OpenMetadataBaseUrlConfiguration.class)
                .getOpenMetadataUrl();
    final String base =
        nullOrEmpty(configured) ? uriInfo.getBaseUri().resolve("/").toString() : configured;
    return base.replaceAll("/+$", "") + MCP_PATH;
  }

  /** The draft revision an {@code If-Match} header names, quoted as an entity tag or not. */
  static Integer draftRevision(final String ifMatch) {
    Integer revision = null;
    if (ifMatch != null && !ifMatch.isBlank()) {
      final String tag = ifMatch.strip().replaceFirst("^W/", "").replace("\"", "");
      try {
        revision = Integer.valueOf(tag);
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException(
            "If-Match must be the draftRevision, a whole number: " + ifMatch, exception);
      }
    }
    return revision;
  }

  private static String userName(final SecurityContext securityContext) {
    return securityContext.getUserPrincipal().getName();
  }
}
