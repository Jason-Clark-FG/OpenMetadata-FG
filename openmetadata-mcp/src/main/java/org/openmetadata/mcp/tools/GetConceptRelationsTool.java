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

import java.util.Map;
import java.util.Set;
import org.openmetadata.schema.api.context.ConceptRelations;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.ConceptRelationsBuilder;
import org.openmetadata.service.aicontext.LiveRelationCatalog;
import org.openmetadata.service.aicontext.answer.EntityLinks;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;

/**
 * How a glossary term connects to the terms related to it and which table stands for each. With a
 * target term it also returns the foreign keys that join the two. Both terms are authorized like
 * get_concept_context, and a related term or table the caller may not view is left out.
 */
public class GetConceptRelationsTool implements TypedMcpTool<ConceptRelations> {
  private static final Set<String> FIELDS = Set.of("relatedTerms", "glossary");

  @Override
  public ConceptRelations execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> values) {
    McpToolParameters params = McpToolParameters.from(values);
    String fqn = params.requiredString("fqn");
    String to = params.optionalString("to");
    GlossaryTerm term = read(authorizer, securityContext, fqn);
    GlossaryTerm target =
        McpToolParameters.isBlank(to) ? null : read(authorizer, securityContext, to);
    return new ConceptRelationsBuilder(
            EntityLinks.fromSettings(), new LiveRelationCatalog(authorizer, securityContext))
        .build(term, target);
  }

  private static GlossaryTerm read(
      Authorizer authorizer, CatalogSecurityContext securityContext, String fqn) {
    authorizer.authorize(
        securityContext,
        new OperationContext(Entity.GLOSSARY_TERM, MetadataOperation.VIEW_ALL),
        new ResourceContext<>(Entity.GLOSSARY_TERM, null, fqn));
    return (GlossaryTerm)
        CommonUtils.readEntityForCaller(
            Entity.GLOSSARY_TERM,
            fqn,
            CommonUtils.allowedFields(Entity.GLOSSARY_TERM, FIELDS),
            Include.NON_DELETED,
            securityContext);
  }

  @Override
  public ConceptRelations execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    return execute(authorizer, securityContext, params);
  }
}
