/*
 *  Copyright 2021 Collate
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

package org.openmetadata.service.jdbi3;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.service.Entity.PERSONA;
import static org.openmetadata.service.Entity.USER;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.schema.type.change.ChangeSource;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.PersonaContextCache;
import org.openmetadata.service.cache.CacheBundle;
import org.openmetadata.service.jdbi3.AccessControlDAOs.PersonaUserCount;
import org.openmetadata.service.resources.teams.PersonaResource;
import org.openmetadata.service.security.policyevaluator.SubjectCache;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.EntityUtil.RelationIncludes;

@Slf4j
public class PersonaRepository extends EntityRepository<Persona> {
  static final String PERSONA_UPDATE_FIELDS = "users,default";
  static final String PERSONA_PATCH_FIELDS = "users,default";
  static final String FIELD_USERS = "users";
  private static final String FIELD_USER_COUNT = "userCount";

  public PersonaRepository() {
    super(
        PersonaResource.COLLECTION_PATH,
        PERSONA,
        Persona.class,
        Entity.getCollectionDAO().personaDAO(),
        PERSONA_PATCH_FIELDS,
        PERSONA_UPDATE_FIELDS);
    this.quoteFqn = true;
    supportsSearch = false;
    fieldFetchers.put(FIELD_USERS, this::fetchAndSetUsers);
    fieldFetchers.put(FIELD_USER_COUNT, this::fetchAndSetUserCounts);
  }

  @Override
  public void setFields(Persona persona, Fields fields, RelationIncludes relationIncludes) {
    persona.setUsers(fields.contains(FIELD_USERS) ? getUsers(persona) : persona.getUsers());
    fetchAndSetUserCounts(List.of(persona), fields);
  }

  @Override
  public void clearFields(Persona persona, Fields fields) {
    persona.setUsers(fields.contains(FIELD_USERS) ? persona.getUsers() : null);
    persona.setUserCount(fields.contains(FIELD_USER_COUNT) ? persona.getUserCount() : null);
  }

  @Override
  public void prepare(Persona persona, boolean update) {
    persona.setUserCount(null);
    validateUsers(persona.getUsers());
    if (Boolean.TRUE.equals(persona.getDefault())) {
      unsetExistingDefaultPersona(persona.getId().toString());
    }
  }

  @Override
  protected List<String> getFieldsStrippedFromStorageJson() {
    return List.of(FIELD_USERS, FIELD_USER_COUNT);
  }

  @Override
  public void storeEntity(Persona persona, boolean update) {
    store(persona, update);
  }

  @Override
  public void storeEntities(List<Persona> entities) {
    storeMany(entities);
  }

  @Override
  protected void clearEntitySpecificRelationshipsForMany(List<Persona> entities) {
    if (entities.isEmpty()) return;
    List<UUID> ids = entities.stream().map(Persona::getId).toList();
    deleteFromMany(ids, Entity.PERSONA, Relationship.APPLIED_TO, Entity.USER);
  }

  @Override
  public void storeRelationships(Persona persona) {
    for (EntityReference user : listOrEmpty(persona.getUsers())) {
      addRelationship(persona.getId(), user.getId(), PERSONA, Entity.USER, Relationship.APPLIED_TO);
    }
  }

  @Override
  public EntityRepository<Persona>.EntityUpdater getUpdater(
      Persona original, Persona updated, Operation operation, ChangeSource changeSource) {
    return new PersonaUpdater(original, updated, operation);
  }

  private List<EntityReference> getUsers(Persona persona) {
    return findTo(persona.getId(), PERSONA, Relationship.APPLIED_TO, Entity.USER);
  }

  private void fetchAndSetUserCounts(List<Persona> personas, Fields fields) {
    if (fields.contains(FIELD_USER_COUNT) && !nullOrEmpty(personas)) {
      Persona systemDefault = getSystemDefaultPersona();
      List<PersonaUserCount> counts =
          daoCollection
              .personaDAO()
              .countEffectiveUsers(
                  personas.stream().map(persona -> persona.getId().toString()).toList(),
                  systemDefault == null ? null : systemDefault.getId().toString(),
                  Relationship.APPLIED_TO.ordinal(),
                  Relationship.DEFAULTS_TO.ordinal(),
                  Relationship.HAS.ordinal());
      Map<UUID, Integer> byId = new HashMap<>();
      counts.forEach(count -> byId.put(count.personaId(), count.userCount()));
      personas.forEach(persona -> persona.setUserCount(byId.getOrDefault(persona.getId(), 0)));
    }
  }

  // List endpoints go through setFieldsInBulk, which only runs fieldFetchers — setFields is never
  // called there, so without this the list response carried no users.
  private void fetchAndSetUsers(List<Persona> personas, Fields fields) {
    if (!fields.contains(FIELD_USERS) || nullOrEmpty(personas)) {
      return;
    }
    List<String> personaIds =
        personas.stream().map(Persona::getId).map(UUID::toString).distinct().toList();
    List<CollectionDAO.EntityRelationshipObject> records =
        daoCollection
            .relationshipDAO()
            .findToBatch(personaIds, Relationship.APPLIED_TO.ordinal(), PERSONA, USER);
    Map<UUID, EntityReference> userRefsById =
        batchResolveRefs(USER, records.stream().map(r -> UUID.fromString(r.getToId())).toList());
    Map<UUID, List<EntityReference>> personaToUsers = new HashMap<>();
    for (CollectionDAO.EntityRelationshipObject relation : records) {
      EntityReference userRef = userRefsById.get(UUID.fromString(relation.getToId()));
      if (userRef != null) {
        personaToUsers
            .computeIfAbsent(UUID.fromString(relation.getFromId()), k -> new ArrayList<>())
            .add(userRef);
      }
    }
    for (Persona persona : personas) {
      persona.setUsers(personaToUsers.getOrDefault(persona.getId(), new ArrayList<>()));
    }
  }

  @Transaction
  private void unsetExistingDefaultPersona(String newDefaultPersonaId) {
    // Capture both id and FQN *before* the bulk update. The bulk update rewrites JSON directly —
    // bypassing invalidateCachesAfterStore — so every affected persona would keep stale
    // "default=true" in both EntityRepository.CACHE_WITH_ID and EntityRepository.CACHE_WITH_NAME
    // variants.
    // Passing fqn lets
    // invalidateCacheForEntity drop the by-name cache alongside the by-id one.
    List<EntityDAO.EntityIdFqnPair> affected =
        daoCollection.personaDAO().findOtherDefaultPersonaIdsWithFqn(newDefaultPersonaId);
    daoCollection.personaDAO().unsetOtherDefaultPersonas(newDefaultPersonaId);
    for (EntityDAO.EntityIdFqnPair persona : affected) {
      EntityRepository.invalidateCacheForEntity(Entity.PERSONA, persona.id, persona.fqn);
    }
  }

  public Persona getSystemDefaultPersona() {
    String json = daoCollection.personaDAO().findDefaultPersona();
    if (json != null) {
      return JsonUtils.readValue(json, Persona.class);
    }
    return null;
  }

  @Override
  protected void postCreate(Persona persona) {
    super.postCreate(persona);
    if (Boolean.TRUE.equals(persona.getDefault())) {
      SubjectCache.invalidateAllUserContexts();
    } else {
      invalidateUserContexts(persona.getUsers(), List.of());
    }
    // Creates don't broadcast (only the update and delete paths publish), so peers would keep a
    // user context without the persona this create just assigned for the full 15-minute TTL —
    // long enough for the user's persona selection to be rejected as "not assigned".
    var pubsub = CacheBundle.getCacheInvalidationPubSub();
    if (pubsub != null) {
      pubsub.publish(PERSONA, persona.getId(), persona.getFullyQualifiedName(), "create");
    }
  }

  @Override
  @Transaction
  protected void preDelete(Persona persona, String deletedBy) {
    // Remove all user-persona relationships (APPLIED_TO)
    List<EntityReference> users = findTo(persona.getId(), PERSONA, Relationship.APPLIED_TO, USER);
    for (EntityReference user : listOrEmpty(users)) {
      deleteRelationship(persona.getId(), PERSONA, user.getId(), USER, Relationship.APPLIED_TO);
    }

    // Remove all default persona relationships (DEFAULTS_TO)
    List<EntityReference> defaultUsers =
        findTo(persona.getId(), PERSONA, Relationship.DEFAULTS_TO, USER);
    for (EntityReference user : listOrEmpty(defaultUsers)) {
      deleteRelationship(user.getId(), USER, persona.getId(), PERSONA, Relationship.DEFAULTS_TO);
    }

    // Remove all team default persona relationships (HAS)
    List<EntityReference> teams = findFrom(persona.getId(), PERSONA, Relationship.HAS, Entity.TEAM);
    for (EntityReference team : listOrEmpty(teams)) {
      deleteRelationship(team.getId(), Entity.TEAM, persona.getId(), PERSONA, Relationship.HAS);
    }

    // Users/teams that had this persona cached embed the persona reference in their serialized
    // JSON. Drop their cached entries so the next read rebuilds without the now-deleted persona.
    for (EntityReference user : listOrEmpty(users)) {
      EntityRepository.invalidateCacheForEntity(USER, user.getId(), user.getFullyQualifiedName());
    }
    for (EntityReference user : listOrEmpty(defaultUsers)) {
      EntityRepository.invalidateCacheForEntity(USER, user.getId(), user.getFullyQualifiedName());
    }
    for (EntityReference team : listOrEmpty(teams)) {
      EntityRepository.invalidateCacheForEntity(
          Entity.TEAM, team.getId(), team.getFullyQualifiedName());
    }
    if (Boolean.TRUE.equals(persona.getDefault()) || !teams.isEmpty()) {
      SubjectCache.invalidateAllUserContexts();
    } else {
      invalidateUserContexts(users, defaultUsers);
    }
  }

  @Override
  protected void postUpdate(Persona original, Persona updated) {
    super.postUpdate(original, updated);
    PersonaContextCache.getInstance().invalidate(original, updated);
  }

  @Override
  protected void entitySpecificCleanup(String deletedBy, Persona persona) {
    ((AgentHarnessRepository) Entity.getEntityRepository(Entity.AGENT_HARNESS))
        .deleteForPersona(persona.getId(), deletedBy);
  }

  @Override
  protected void postDelete(Persona persona, boolean hardDelete) {
    PersonaContextCache.getInstance().invalidate(persona);
    super.postDelete(persona, hardDelete);
  }

  private boolean userAssignmentsMatch(
      List<EntityReference> originalUsers, List<EntityReference> updatedUsers) {
    Set<UUID> originalUserIds = new HashSet<>();
    Set<UUID> updatedUserIds = new HashSet<>();
    listOrEmpty(originalUsers).forEach(user -> originalUserIds.add(user.getId()));
    listOrEmpty(updatedUsers).forEach(user -> updatedUserIds.add(user.getId()));
    return originalUserIds.equals(updatedUserIds);
  }

  private void invalidateUserContexts(
      List<EntityReference> originalUsers, List<EntityReference> updatedUsers) {
    List<EntityReference> affectedUsers = new ArrayList<>(listOrEmpty(originalUsers));
    affectedUsers.addAll(listOrEmpty(updatedUsers));
    SubjectCache.invalidateUserContexts(affectedUsers);
  }

  /** Handles entity updated from PUT and POST operation. */
  public class PersonaUpdater extends EntityUpdater {
    public PersonaUpdater(Persona original, Persona updated, Operation operation) {
      super(original, updated, operation);
    }

    @Override
    public void entitySpecificUpdate(boolean consolidatingChanges) {
      compareAndUpdate("users", () -> updateUsers(original, updated));
      compareAndUpdate("default", () -> updateDefault(original, updated));
    }

    @Transaction
    private void updateUsers(Persona origPersona, Persona updatedPersona) {
      List<EntityReference> origUsers = listOrEmpty(origPersona.getUsers());
      List<EntityReference> updatedUsers = listOrEmpty(updatedPersona.getUsers());
      boolean assignmentsChanged = !userAssignmentsMatch(origUsers, updatedUsers);
      updateToRelationships(
          "users",
          PERSONA,
          origPersona.getId(),
          Relationship.APPLIED_TO,
          Entity.USER,
          origUsers,
          updatedUsers,
          false);
      if (assignmentsChanged) {
        deferReactOperation(() -> invalidateUserContexts(origUsers, updatedUsers));
      }
    }

    private void updateDefault(Persona origPersona, Persona updatedPersona) {
      Boolean origDefault = origPersona.getDefault();
      Boolean updatedDefault = updatedPersona.getDefault();
      if (!Objects.equals(origDefault, updatedDefault)) {
        recordChange("default", origDefault, updatedDefault);
        deferReactOperation(SubjectCache::invalidateAllUserContexts);
      }
    }
  }
}
