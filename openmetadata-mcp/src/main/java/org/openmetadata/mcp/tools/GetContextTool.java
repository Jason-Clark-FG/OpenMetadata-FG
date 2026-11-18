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
package org.openmetadata.mcp.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import org.openmetadata.schema.api.context.CompileAgentContext;
import org.openmetadata.schema.api.context.CompileEvent;
import org.openmetadata.schema.api.context.CompiledAgentContext;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.harness.AgentHarnessCompiler;
import org.openmetadata.service.jdbi3.AgentHarnessRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * The context the caller's agent receives at the start of a task: the SessionStart compile of the
 * caller's harness, and the skills it offers. The harness comes from the caller's sign-in.
 */
public class GetContextTool implements McpTool {
  private static final String SKILLS_KEY = "skills";

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    AgentHarnessCompiler compiler = compiler(authorizer);
    CompiledAgentContext compiled =
        compiler.compile(
            securityContext, new CompileAgentContext().withEvent(CompileEvent.SESSION_START));
    Map<String, Object> result = new LinkedHashMap<>(JsonUtils.getMap(compiled));
    result.put(SKILLS_KEY, compiler.skills(securityContext));
    return result;
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    throw new UnsupportedOperationException("GetContextTool does not require limit validation.");
  }

  static AgentHarnessCompiler compiler(Authorizer authorizer) {
    return new AgentHarnessCompiler(
        (AgentHarnessRepository) Entity.getEntityRepository(Entity.AGENT_HARNESS), authorizer);
  }
}
