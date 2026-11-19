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

import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.openmetadata.schema.entity.context.ContextMemory;
import org.openmetadata.schema.entity.context.ContextMemoryScope;
import org.openmetadata.schema.entity.context.ContextMemorySourceType;
import org.openmetadata.schema.entity.context.ContextMemoryStatus;
import org.openmetadata.schema.entity.context.MemoryShareConfig;
import org.openmetadata.schema.entity.context.MemoryVisibility;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.context.center.MemoryReviewHolds;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.ContextMemoryRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.rdf.RdfUtils;
import org.openmetadata.service.rdf.explore.LearnedCandidate;
import org.openmetadata.service.rdf.explore.MemoryEntry;
import org.openmetadata.service.rdf.explore.MemoryWriter;
import org.openmetadata.service.rules.RuleEngine;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.ImpersonationContext;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.CreateResourceContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;

/**
 * Saves what a turn learnt as context memories, as the caller, through the create path
 * {@code create_entity} takes: the create permission, the entity limits, the entity rules, then the
 * repository, which decides the stage and checks the writer may point at the anchor. A candidate
 * that says what a memory the run showed, one on its anchor or one of the caller's already says is
 * not saved; a correction of a memory still awaiting review edits it in place through {@code
 * patch_entity}, and one of any other memory is a new memory that names it.
 */
final class MemoryCapture implements MemoryWriter {

  private static final String NAME_PREFIX = "learned-";
  private static final String FIELDS = "owners,primaryEntity";
  private static final String EVIDENCE = "Learnt in a knowledge_graph run. Evidence (%s): \"%s\"";
  private static final String TOOL_EVIDENCE =
      "Learnt in a knowledge_graph run. Evidence (tool %s): \"%s\"";
  private static final Set<ContextMemoryStatus> COMPARED =
      EnumSet.of(
          ContextMemoryStatus.APPROVED, ContextMemoryStatus.UNPROCESSED, ContextMemoryStatus.DRAFT);
  private static final Set<ContextMemoryStatus> AWAITING_REVIEW =
      EnumSet.of(ContextMemoryStatus.UNPROCESSED, ContextMemoryStatus.DRAFT);

  private final Authorizer authorizer;
  private final Limits limits;
  private final CatalogSecurityContext caller;
  private final String baseUri;

  MemoryCapture(
      final Authorizer authorizer,
      final Limits limits,
      final CatalogSecurityContext caller,
      final String baseUri) {
    this.authorizer = authorizer;
    this.limits = limits;
    this.caller = caller;
    this.baseUri = baseUri;
  }

  @Override
  public Written write(
      final LearnedCandidate candidate, final Set<String> shownMemories, final UUID run) {
    Written written;
    try {
      final Optional<ContextMemory> corrected = memory(candidate.corrects());
      final ContextMemory proposed = proposal(candidate, run, corrected);
      written =
          repeatOf(proposed, comparedWith(candidate, shownMemories))
              .<Written>map(same -> new Written.Repeat(iri(same), same.getTitle()))
              .orElseGet(() -> save(proposed, corrected));
    } catch (AuthorizationException
        | WebApplicationException
        | EntityNotFoundException
        | IllegalArgumentException refused) {
      written = new Written.Refused(refused.getMessage());
    }
    return written;
  }

  private Written save(final ContextMemory proposed, final Optional<ContextMemory> corrected) {
    final boolean inPlace =
        corrected.map(memory -> AWAITING_REVIEW.contains(memory.getEntityStatus())).orElse(false);
    return new Written.Saved(entry(inPlace ? revise(corrected.get(), proposed) : create(proposed)));
  }

  /** A candidate as the memory it would be: the caller's, anchored to its card when it has one. */
  private ContextMemory proposal(
      final LearnedCandidate candidate, final UUID run, final Optional<ContextMemory> corrected) {
    final Optional<EntityReference> anchor = anchor(candidate.about());
    final ContextMemory memory =
        new ContextMemory()
            .withId(UUID.randomUUID())
            .withName(NAME_PREFIX + UUID.randomUUID())
            .withTitle(candidate.title())
            .withQuestion(candidate.question())
            .withAnswer(candidate.answer())
            .withDescription(evidence(candidate.evidence()))
            .withMemoryType(candidate.kind().memoryType())
            .withMemoryScope(
                anchor.isPresent()
                    ? ContextMemoryScope.ENTITY_SCOPED
                    : ContextMemoryScope.USER_GLOBAL)
            .withShareConfig(
                new MemoryShareConfig()
                    .withVisibility(
                        anchor.isPresent() ? MemoryVisibility.ENTITY : MemoryVisibility.PRIVATE))
            .withPrimaryEntity(anchor.orElse(null))
            .withOwners(List.of(callerReference()))
            .withSourceType(ContextMemorySourceType.CONVERSATION_EXTRACTION)
            .withSourceConversation(run)
            .withUpdatedBy(callerName())
            .withUpdatedAt(System.currentTimeMillis());
    corrected.ifPresent(earlier -> memory.setParentMemory(earlier.getEntityReference()));
    return memory;
  }

  private static String evidence(final LearnedCandidate.Evidence evidence) {
    return evidence.tool() == null
        ? EVIDENCE.formatted(evidence.source().value(), evidence.quote())
        : TOOL_EVIDENCE.formatted(evidence.tool(), evidence.quote());
  }

  /**
   * The memories a new one is compared with: those the run showed, those on its anchor and the
   * caller's own, in a stage still in use.
   */
  private List<ContextMemory> comparedWith(
      final LearnedCandidate candidate, final Set<String> shownMemories) {
    final Set<UUID> ids = new LinkedHashSet<>();
    shownMemories.stream().map(MemoryCapture::idOf).flatMap(Optional::stream).forEach(ids::add);
    anchor(candidate.about())
        .ifPresent(
            anchor ->
                ids.addAll(
                    memoriesFrom(anchor.getId(), anchor.getType(), Relationship.APPLIED_TO)));
    ids.addAll(memoriesFrom(callerReference().getId(), Entity.USER, Relationship.OWNS));
    return ids.isEmpty()
        ? List.of()
        : repository()
            .get(null, new ArrayList<>(ids), repository().getFields(FIELDS), Include.NON_DELETED)
            .stream()
            .filter(memory -> COMPARED.contains(memory.getEntityStatus()))
            .toList();
  }

  private static List<UUID> memoriesFrom(
      final UUID from, final String fromType, final Relationship relationship) {
    return Entity.getCollectionDAO()
        .relationshipDAO()
        .findTo(from, fromType, relationship.ordinal(), Entity.CONTEXT_MEMORY)
        .stream()
        .map(record -> record.getId())
        .toList();
  }

  private static Optional<ContextMemory> repeatOf(
      final ContextMemory proposed, final List<ContextMemory> existing) {
    return existing.stream()
        .filter(memory -> MemoryReviewHolds.restates(memory, proposed))
        .findFirst();
  }

  private ContextMemory create(final ContextMemory memory) {
    final OperationContext operation =
        new OperationContext(Entity.CONTEXT_MEMORY, MetadataOperation.CREATE);
    final CreateResourceContext<ContextMemory> resource =
        new CreateResourceContext<>(Entity.CONTEXT_MEMORY, memory);
    limits.enforceLimits(caller, resource, operation);
    authorizer.authorize(caller, operation, resource);
    RuleEngine.getInstance().evaluate(memory);
    final ContextMemory saved =
        repository().create(null, memory, callerName(), ImpersonationContext.getImpersonatedBy());
    McpChangeEventUtil.publishChangeEvent(saved, EventType.ENTITY_CREATED, callerName());
    return saved;
  }

  /** A correction of a memory awaiting review: its text replaced through patch_entity. */
  private ContextMemory revise(final ContextMemory pending, final ContextMemory proposed) {
    final String patch =
        JsonUtils.pojoToJson(
            Stream.of("title", "question", "answer", "description")
                .map(
                    field ->
                        Map.of("op", "add", "path", "/" + field, "value", text(proposed, field)))
                .toList());
    new PatchEntityTool()
        .execute(
            authorizer,
            caller,
            Map.of(
                "entityType",
                Entity.CONTEXT_MEMORY,
                "fqn",
                pending.getFullyQualifiedName(),
                "patch",
                patch));
    return repository()
        .get(null, pending.getId(), repository().getFields(FIELDS), Include.NON_DELETED, false);
  }

  private static String text(final ContextMemory memory, final String field) {
    return switch (field) {
      case "title" -> memory.getTitle();
      case "question" -> memory.getQuestion();
      case "answer" -> memory.getAnswer();
      default -> memory.getDescription();
    };
  }

  private MemoryEntry entry(final ContextMemory saved) {
    return MemoryEntry.of(
        iri(saved),
        saved.getTitle(),
        saved.getQuestion(),
        saved.getAnswer(),
        saved.getEntityStatus() == null ? null : saved.getEntityStatus().value(),
        saved.getLastContentUpdatedAt() == null
            ? saved.getUpdatedAt()
            : saved.getLastContentUpdatedAt());
  }

  private String iri(final ContextMemory memory) {
    return RdfUtils.entityUri(baseUri, Entity.CONTEXT_MEMORY, memory.getId());
  }

  private Optional<ContextMemory> memory(final String iri) {
    return idOf(iri)
        .map(
            id ->
                repository()
                    .get(null, id, repository().getFields(FIELDS), Include.NON_DELETED, false));
  }

  /** The asset a card names, by the type and id the projection minted its IRI from. */
  private Optional<EntityReference> anchor(final String iri) {
    return idOf(iri).map(id -> new EntityReference().withId(id).withType(typeOf(iri)));
  }

  private String typeOf(final String iri) {
    final String path =
        iri.substring(
            (baseUri.endsWith("/") ? baseUri : baseUri + "/").length() + "entity/".length());
    return path.substring(0, path.indexOf('/'));
  }

  private static Optional<UUID> idOf(final String iri) {
    Optional<UUID> id = Optional.empty();
    if (iri != null) {
      try {
        id = Optional.of(UUID.fromString(iri.substring(iri.lastIndexOf('/') + 1)));
      } catch (IllegalArgumentException notAnId) {
        // Only an entity minted by id can be named here; the run's checks already said so.
      }
    }
    return id;
  }

  private EntityReference callerReference() {
    return DefaultAuthorizer.getSubjectContext(caller).user().getEntityReference();
  }

  private String callerName() {
    return caller.getUserPrincipal().getName();
  }

  private static ContextMemoryRepository repository() {
    return (ContextMemoryRepository) Entity.getEntityRepository(Entity.CONTEXT_MEMORY);
  }
}
