package org.openmetadata.it.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openmetadata.it.factories.ShortStackFactory;
import org.openmetadata.it.util.SdkClients;
import org.openmetadata.it.util.TestNamespace;
import org.openmetadata.it.util.TestNamespaceExtension;
import org.openmetadata.schema.api.context.CreateContextMemory;
import org.openmetadata.schema.api.teams.CreatePersona;
import org.openmetadata.schema.api.teams.CreateTeam;
import org.openmetadata.schema.api.teams.CreateTeam.TeamType;
import org.openmetadata.schema.api.teams.CreateUser;
import org.openmetadata.schema.entity.context.ContextMemory;
import org.openmetadata.schema.entity.context.ContextMemoryScope;
import org.openmetadata.schema.entity.context.ContextMemoryStatus;
import org.openmetadata.schema.entity.context.MemoryShareConfig;
import org.openmetadata.schema.entity.context.MemorySharedPrincipal;
import org.openmetadata.schema.entity.context.MemoryVisibility;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.Team;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.sdk.client.OpenMetadataClient;
import org.openmetadata.sdk.exceptions.ForbiddenException;
import org.openmetadata.sdk.exceptions.OpenMetadataException;
import org.openmetadata.sdk.models.ListParams;
import org.openmetadata.sdk.services.context.ContextMemoryService;
import org.openmetadata.service.Entity;

/**
 * A memory shared with a persona reaches the persona's members, decided when the memory is read:
 * users assigned the persona, members of a team whose default persona it is, and users whose
 * default persona it is. Every read path agrees: a read by id, the plain list, the search-backed
 * listing, and search.
 */
@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(TestNamespaceExtension.class)
public class PersonaMemoryVisibilityIT {

  private static final String MEMORY_INDEX = "context_memory_search_index";
  private static final Duration INDEXING = Duration.ofSeconds(60);

  @Test
  void aPersonaShareReachesEveryMemberAndNoOneElse(TestNamespace ns) {
    Persona persona = createPersona(ns, "analyst");
    Team team = createTeam(ns, persona);
    User assigned = createUser(ns, new CreateUser().withPersonas(List.of(ref(persona))));
    User teamMember = createUser(ns, new CreateUser().withTeams(List.of(team.getId())));
    User byDefault = createUser(ns, new CreateUser().withDefaultPersona(ref(persona)));
    User outsider = createUser(ns, new CreateUser());
    Shared memory = shareWith(ns, persona);

    for (User member : List.of(assigned, teamMember, byDefault)) {
      assertReadable(member, memory);
    }
    assertHidden(outsider, memory);
  }

  @Test
  void leavingThePersonaEndsAccessWithoutTouchingTheMemory(TestNamespace ns) {
    Persona persona = createPersona(ns, "steward");
    Team team = createTeam(ns, persona);
    User assigned = createUser(ns, new CreateUser().withPersonas(List.of(ref(persona))));
    User teamMember = createUser(ns, new CreateUser().withTeams(List.of(team.getId())));
    Shared memory = shareWith(ns, persona);
    assertReadable(assigned, memory);
    assertReadable(teamMember, memory);
    Double version = adminMemories().get(memory.id().toString()).getVersion();

    Persona withoutUsers = admin().personas().get(persona.getId().toString(), "users");
    withoutUsers.setUsers(List.of());
    admin().personas().update(persona.getId().toString(), withoutUsers);
    Team withoutDefault = admin().teams().get(team.getId().toString(), "defaultPersona");
    withoutDefault.setDefaultPersona(null);
    admin().teams().update(team.getId().toString(), withoutDefault);

    assertHidden(assigned, memory);
    assertHidden(teamMember, memory);
    assertEquals(
        version,
        adminMemories().get(memory.id().toString()).getVersion(),
        "access changed with membership alone; the memory was not rewritten");
  }

  /**
   * A deleted persona keeps its grants on the memories that named it, and they reach no one: the
   * delete removes every assignment of the persona, and a new persona never takes an old id.
   */
  @Test
  void aDeletedPersonasGrantsReachNoOne(TestNamespace ns) {
    Persona persona = createPersona(ns, "retired");
    User assigned = createUser(ns, new CreateUser().withPersonas(List.of(ref(persona))));
    Shared memory = shareWith(ns, persona);
    assertReadable(assigned, memory);

    admin().personas().delete(persona.getId());

    assertHidden(assigned, memory);
    assertEquals(
        List.of(persona.getId()),
        adminMemories().get(memory.id().toString()).getShareConfig().getSharedWith().stream()
            .map(shared -> shared.getPrincipal().getId())
            .toList());
    assertThrows(
        OpenMetadataException.class,
        () -> adminMemories().create(sharedRequest(ns, "after-delete", persona, null)),
        "a deleted persona cannot be given a new grant");
  }

  private record Shared(UUID id, UUID anchorId, String token) {}

  private static Shared shareWith(TestNamespace ns, Persona persona) {
    Table anchor = ShortStackFactory.table(ns);
    String token = "personashare" + UUID.randomUUID().toString().substring(0, 8);
    ContextMemory memory =
        adminMemories()
            .create(
                sharedRequest(ns, token, persona, anchor)
                    .withTitle(token)
                    .withQuestion("Which persona reads " + token + "?"));
    Shared shared = new Shared(memory.getId(), anchor.getId(), token);
    Awaitility.await("the shared memory is indexed")
        .atMost(INDEXING)
        .pollInterval(Duration.ofSeconds(1))
        .ignoreExceptions()
        .untilAsserted(() -> assertTrue(searchHits(admin(), shared).contains(shared.id())));
    return shared;
  }

  private static CreateContextMemory sharedRequest(
      TestNamespace ns, String name, Persona persona, Table anchor) {
    CreateContextMemory request =
        new CreateContextMemory()
            .withName(ns.prefix(name))
            .withEntityStatus(ContextMemoryStatus.APPROVED)
            .withQuestion("Which persona reads this?")
            .withAnswer("Every member of the persona it is shared with.")
            .withShareConfig(
                new MemoryShareConfig()
                    .withVisibility(MemoryVisibility.SHARED)
                    .withSharedWith(
                        List.of(new MemorySharedPrincipal().withPrincipal(ref(persona)))));
    if (anchor != null) {
      request
          .withMemoryScope(ContextMemoryScope.ENTITY_SCOPED)
          .withPrimaryEntity(new EntityReference().withId(anchor.getId()).withType(Entity.TABLE));
    }
    return request;
  }

  private static void assertReadable(User user, Shared memory) {
    OpenMetadataClient client = clientOf(user);
    String who = user.getName();
    assertEquals(memory.id(), memoriesOf(client).get(memory.id().toString()).getId(), who);
    assertTrue(plainListIds(client, memory).contains(memory.id()), who + " plain list");
    assertTrue(searchListingIds(client, memory).contains(memory.id()), who + " listing");
    Awaitility.await(who + " finds the memory through search")
        .atMost(INDEXING)
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> assertTrue(searchHits(client, memory).contains(memory.id())));
  }

  private static void assertHidden(User user, Shared memory) {
    OpenMetadataClient client = clientOf(user);
    String who = user.getName();
    assertThrows(
        ForbiddenException.class, () -> memoriesOf(client).get(memory.id().toString()), who);
    assertFalse(plainListIds(client, memory).contains(memory.id()), who + " plain list");
    assertFalse(searchListingIds(client, memory).contains(memory.id()), who + " listing");
    assertFalse(searchHits(client, memory).contains(memory.id()), who + " search");
  }

  private static Set<UUID> plainListIds(OpenMetadataClient client, Shared memory) {
    ListParams params =
        new ListParams().addQueryParam("primaryEntityId", memory.anchorId().toString());
    params.setLimit(10);
    return ids(memoriesOf(client).list(params).getData());
  }

  private static Set<UUID> searchListingIds(OpenMetadataClient client, Shared memory) {
    ListParams params = new ListParams().addQueryParam("q", memory.token());
    params.setLimit(10);
    params.setOffset(0);
    return ids(memoriesOf(client).list(params).getData());
  }

  private static Set<UUID> searchHits(OpenMetadataClient client, Shared memory) {
    Set<UUID> hits = new HashSet<>();
    JsonUtils.readTree(client.search().query(memory.token()).index(MEMORY_INDEX).size(10).execute())
        .path("hits")
        .path("hits")
        .forEach(hit -> hits.add(UUID.fromString(hit.path("_source").path("id").asText())));
    return hits;
  }

  private static Set<UUID> ids(List<ContextMemory> memories) {
    Set<UUID> ids = new HashSet<>();
    memories.forEach(memory -> ids.add(memory.getId()));
    return ids;
  }

  private static Persona createPersona(TestNamespace ns, String name) {
    return ns.trackRoot(
        Entity.PERSONA,
        admin()
            .personas()
            .create(new CreatePersona().withName(ns.shortPrefix(name)).withDescription(name)));
  }

  private static Team createTeam(TestNamespace ns, Persona persona) {
    return ns.trackRoot(
        Entity.TEAM,
        admin()
            .teams()
            .create(
                new CreateTeam()
                    .withName(ns.shortPrefix("team"))
                    .withTeamType(TeamType.GROUP)
                    .withDefaultPersona(persona.getId())));
  }

  private static User createUser(TestNamespace ns, CreateUser request) {
    String name = "pmv_" + UUID.randomUUID().toString().substring(0, 8);
    return ns.trackRoot(
        Entity.USER,
        admin().users().create(request.withName(name).withEmail(name + "@test.openmetadata.org")));
  }

  private static EntityReference ref(Persona persona) {
    return new EntityReference().withId(persona.getId()).withType(Entity.PERSONA);
  }

  private static OpenMetadataClient clientOf(User user) {
    return SdkClients.createClient(user.getEmail(), user.getEmail(), new String[] {});
  }

  private static ContextMemoryService memoriesOf(OpenMetadataClient client) {
    return new ContextMemoryService(client.getHttpClient());
  }

  private static ContextMemoryService adminMemories() {
    return memoriesOf(admin());
  }

  private static OpenMetadataClient admin() {
    return SdkClients.adminClient();
  }
}
