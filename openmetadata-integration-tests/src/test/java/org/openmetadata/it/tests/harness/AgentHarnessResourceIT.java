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
package org.openmetadata.it.tests.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.ADMIN_TOKEN;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.HARNESS;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.admin;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersona;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createPersonaHarness;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.encoded;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.read;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.send;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.uniqueName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openmetadata.schema.api.context.AgentHarnessAdditions;
import org.openmetadata.schema.api.context.AgentHarnessPersonaSummary;
import org.openmetadata.schema.api.context.AgentHarnessSpec;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.api.context.AgentHarnessStatus;
import org.openmetadata.schema.entity.context.AgentHarness;
import org.openmetadata.schema.entity.context.AgentHarnessGuardrail;
import org.openmetadata.schema.entity.context.AgentHarnessKind;
import org.openmetadata.schema.entity.context.AgentMemoryReview;
import org.openmetadata.schema.entity.context.AgentMemoryVisibility;
import org.openmetadata.schema.entity.context.GuardrailLevel;
import org.openmetadata.schema.entity.context.HookEvent;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.type.EntityHistory;
import org.openmetadata.schema.type.personaContext.ContextRule;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.schema.utils.ResultList;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.harness.HarnessSpecs;
import org.openmetadata.service.jdbi3.AgentHarnessRepository;

@Execution(ExecutionMode.CONCURRENT)
class AgentHarnessResourceIT {

  @Test
  @ResourceLock(value = AgentHarnessApi.DEFAULT_HARNESS_LOCK, mode = ResourceAccessMode.READ)
  void theDefaultIsSeededOnceWithNoRulesAndTheSpecBudgets() {
    AgentHarnessState state = admin("GET", "/default", null, null);
    AgentHarness published = state.getPublished();

    ((AgentHarnessRepository) Entity.getEntityRepository(Entity.AGENT_HARNESS))
        .initializeEntity(HarnessSpecs.seedDefault(System.currentTimeMillis()));

    assertThat(admin("GET", "/default", null, null).getPublished().getId())
        .isEqualTo(published.getId());
    AgentHarness seeded =
        read(
            send("GET", HARNESS + "/" + published.getId() + "/versions/0.1", null, ADMIN_TOKEN),
            200,
            AgentHarness.class);
    assertThat(seeded.getKind()).isEqualTo(AgentHarnessKind.DEFAULT);
    assertThat(seeded.getName()).isEqualTo("CompanyDefault");
    assertThat(seeded.getEnabled()).isTrue();
    assertThat(seeded.getPublishedAt()).isNotNull();
    assertThat(seeded.getContext().getRules()).isEmpty();
    assertThat(seeded.getContext().getPrompt()).isNull();
    assertThat(seeded.getCompanyInstructions()).isNull();
    assertThat(seeded.getPerTurnTokenBudget()).isEqualTo(20_000);
    assertThat(seeded.getEvents())
        .extracting(
            event -> event.getId() + ":" + event.getTokenBudget() + ":" + event.getEnabled())
        .containsExactly(
            "SessionStart:6000:true",
            "UserPromptSubmit:4000:true",
            "PreToolUse:2000:true",
            "PostToolUse:1000:true",
            "Stop:1000:true");
    assertThat(seeded.getGuardrails())
        .extracting(guardrail -> guardrail.getId() + ":" + guardrail.getLevel())
        .containsExactly(
            "preferCertifiedTables:Warn",
            "blockDeprecatedAssets:Block",
            "piiColumnsNeedMasking:Block",
            "flagOpenIncidents:Warn",
            "queryCostCeiling:Off");
    assertThat(seeded.getMemory().getReview()).isEqualTo(AgentMemoryReview.STEWARD_REVIEW);
    assertThat(seeded.getMemory().getVisibility()).isEqualTo(AgentMemoryVisibility.PRIVATE);
  }

  @Test
  void aPersonaHarnessIsCreatedOnceAsAnUnpublishedDraft() {
    Persona persona = createPersona(uniqueName("harness_create"), List.of());

    AgentHarnessState created = createPersonaHarness(persona);

    assertThat(created.getStatus()).isEqualTo(AgentHarnessStatus.DRAFT);
    assertThat(created.getPublished().getKind()).isEqualTo(AgentHarnessKind.PERSONA);
    assertThat(created.getPublished().getPersona().getId()).isEqualTo(persona.getId());
    assertThat(created.getPublished().getPublishedAt()).isNull();
    assertThat(created.getDraft().getPerTurnTokenBudget()).isEqualTo(20_000);
    assertThat(created.getDraft().getContext().getRules()).isEmpty();
    assertThat(created.getResolved().getKind()).isEqualTo(AgentHarnessKind.PERSONA);
    assertThat(
            send(
                    "POST",
                    HARNESS + "/persona/" + encoded(persona.getFullyQualifiedName()),
                    null,
                    ADMIN_TOKEN)
                .statusCode())
        .isEqualTo(409);
    assertThat(
            admin("GET", "/persona/" + encoded(persona.getFullyQualifiedName()), null, null)
                .getPublished()
                .getId())
        .isEqualTo(created.getPublished().getId());
  }

  @Test
  void draftWritesLeaveThePublishedHarnessAloneAndAStaleRevisionIsRefused() {
    AgentHarnessState created =
        createPersonaHarness(createPersona(uniqueName("harness_draft"), List.of()));
    String draftPath = "/" + created.getPublished().getId() + "/draft";
    AgentHarnessSpec spec = withRule(created.getDraft(), "Orders");

    AgentHarnessState written = admin("PUT", draftPath, spec, created.getDraftRevision());

    assertThat(written.getDraftRevision()).isEqualTo(created.getDraftRevision() + 1);
    assertThat(ruleNames(written.getDraft())).containsExactly("Orders");
    assertThat(written.getPublished().getContext()).isNull();
    assertThat(written.getPublished().getVersion()).isEqualTo(created.getPublished().getVersion());
    assertThat(
            written.getResolved().getRules().stream()
                .map(rule -> rule.getRule().getName() + ":" + rule.getInherited())
                .toList())
        .containsExactly("Orders:false");
    String path = HARNESS + draftPath;
    assertThat(send("PUT", path, spec, ADMIN_TOKEN, created.getDraftRevision()).statusCode())
        .isEqualTo(412);
    assertThat(send("PUT", path, spec, ADMIN_TOKEN).statusCode()).isEqualTo(428);
    AgentHarnessState discarded = admin("DELETE", draftPath, null, written.getDraftRevision());
    assertThat(discarded.getDraft()).isNull();
    assertThat(discarded.getStatus()).isEqualTo(AgentHarnessStatus.NOT_CONFIGURED);
    assertThat(discarded.getDraftRevision()).isEqualTo(written.getDraftRevision() + 1);
  }

  @Test
  void anInvalidDraftIsRefusedAndNothingIsStored() {
    AgentHarnessState created =
        createPersonaHarness(createPersona(uniqueName("harness_invalid"), List.of()));
    AgentHarnessSpec looser =
        JsonUtils.deepCopy(created.getDraft(), AgentHarnessSpec.class)
            .withGuardrails(
                List.of(
                    new AgentHarnessGuardrail()
                        .withId("blockDeprecatedAssets")
                        .withLevel(GuardrailLevel.WARN)));
    String path = HARNESS + "/" + created.getPublished().getId() + "/draft";

    assertThat(send("PUT", path, looser, ADMIN_TOKEN, created.getDraftRevision()).statusCode())
        .isEqualTo(400);
    assertThat(
            admin(
                    "GET",
                    "/persona/"
                        + encoded(created.getPublished().getPersona().getFullyQualifiedName()),
                    null,
                    null)
                .getDraftRevision())
        .isEqualTo(created.getDraftRevision());
  }

  @Test
  void eachPublishIsOneVersionAndAnOldVersionCanBeRestoredIntoTheDraft() {
    AgentHarnessState created =
        createPersonaHarness(createPersona(uniqueName("harness_publish"), List.of()));
    UUID id = created.getPublished().getId();
    AgentHarnessState first =
        publish(id, withRule(created.getDraft(), "Orders"), created.getDraftRevision());
    AgentHarnessState second =
        publish(id, withRule(created.getDraft(), "Customers"), first.getDraftRevision());

    assertThat(second.getStatus()).isEqualTo(AgentHarnessStatus.PUBLISHED);
    assertThat(second.getDraft()).isNull();
    assertThat(ruleNames(second.getPublished().getContext().getRules()))
        .containsExactly("Customers");
    assertThat(second.getPublished().getVersion()).isEqualTo(0.3);
    EntityHistory history =
        read(
            send("GET", HARNESS + "/" + id + "/versions", null, ADMIN_TOKEN),
            200,
            EntityHistory.class);
    assertThat(history.getVersions()).hasSize(3);

    AgentHarness firstPublish =
        read(
            send("GET", HARNESS + "/" + id + "/versions/0.2", null, ADMIN_TOKEN),
            200,
            AgentHarness.class);
    AgentHarnessState restored =
        admin(
            "PUT",
            "/" + id + "/draft",
            HarnessSpecs.specOf(firstPublish),
            second.getDraftRevision());
    assertThat(ruleNames(restored.getDraft())).containsExactly("Orders");
    assertThat(ruleNames(restored.getPublished().getContext().getRules()))
        .containsExactly("Customers");
    assertThat(restored.getStatus()).isEqualTo(AgentHarnessStatus.DRAFT);
  }

  @Test
  void theEnabledSwitchTakesEffectWithoutTouchingTheDraft() {
    AgentHarnessState created =
        createPersonaHarness(createPersona(uniqueName("harness_enabled"), List.of()));

    AgentHarnessState disabled =
        admin("PUT", "/" + created.getPublished().getId() + "/enabled", false, null);

    assertThat(disabled.getPublished().getEnabled()).isFalse();
    assertThat(disabled.getDraftRevision()).isEqualTo(created.getDraftRevision());
    assertThat(disabled.getDraft()).isEqualTo(created.getDraft());
  }

  @Test
  void thePersonaListPagesAndShowsAPersonaWithoutAHarnessAsInherited() {
    Persona without = createPersona(uniqueName("harness_list_none"), List.of());
    Persona with = createPersona(uniqueName("harness_list_some"), List.of());
    AgentHarnessState harness = createPersonaHarness(with);

    Map<UUID, AgentHarnessPersonaSummary> summaries = allPersonaSummaries();

    AgentHarnessPersonaSummary inherited = summaries.get(without.getId());
    assertThat(inherited.getStatus()).isEqualTo(AgentHarnessStatus.INHERITED);
    assertThat(inherited.getHarnessId()).isNull();
    assertThat(inherited.getEnabledEvents()).contains(HookEvent.SESSION_START);
    AgentHarnessPersonaSummary drafted = summaries.get(with.getId());
    assertThat(drafted.getStatus()).isEqualTo(AgentHarnessStatus.DRAFT);
    assertThat(drafted.getHarnessId()).isEqualTo(harness.getPublished().getId());
    assertThat(drafted.getEnabled()).isTrue();
    assertThat(inherited.getAdditions()).isNull();
  }

  @Test
  void thePersonaListCountsWhatAPublishedHarnessAddsWithoutPerRowReads() {
    Persona persona = createPersona(uniqueName("harness_list_additions"), List.of());
    AgentHarnessState created = createPersonaHarness(persona);
    publish(
        created.getPublished().getId(),
        withRule(created.getDraft(), "Orders"),
        created.getDraftRevision());

    AgentHarnessAdditions additions = allPersonaSummaries().get(persona.getId()).getAdditions();

    assertThat(additions.getRules()).isEqualTo(1);
    assertThat(additions.getGlossaryTerms()).isZero();
    assertThat(additions.getSkills()).isZero();
    assertThat(additions.getGuardrails()).isZero();
  }

  @Test
  void deletingAPersonaRemovesItsHarnessAndDraft() {
    Persona persona = createPersona(uniqueName("harness_delete"), List.of());
    AgentHarnessState created = createPersonaHarness(persona);
    UUID harnessId = created.getPublished().getId();

    assertThat(
            send("DELETE", "personas/" + persona.getId() + "?hardDelete=true", null, ADMIN_TOKEN)
                .statusCode())
        .isEqualTo(200);

    assertThat(
            send(
                    "GET",
                    HARNESS + "/persona/" + encoded(persona.getFullyQualifiedName()),
                    null,
                    ADMIN_TOKEN)
                .statusCode())
        .isEqualTo(404);
    assertThat(send("GET", HARNESS + "/" + harnessId + "/versions", null, ADMIN_TOKEN).statusCode())
        .isEqualTo(404);
    assertThat(Entity.getCollectionDAO().agentHarnessDraftDAO().find(harnessId)).isNull();
  }

  private static AgentHarnessState publish(UUID id, AgentHarnessSpec spec, int revision) {
    AgentHarnessState drafted = admin("PUT", "/" + id + "/draft", spec, revision);
    return admin("POST", "/" + id + "/publish", null, drafted.getDraftRevision());
  }

  private static Map<UUID, AgentHarnessPersonaSummary> allPersonaSummaries() {
    ResultList<AgentHarnessPersonaSummary> first = personaPage(1, null);
    assertThat(first.getData()).hasSize(1);
    ResultList<AgentHarnessPersonaSummary> second = personaPage(1, first.getPaging().getAfter());
    assertThat(second.getData().getFirst().getPersona().getId())
        .isNotEqualTo(first.getData().getFirst().getPersona().getId());
    Map<UUID, AgentHarnessPersonaSummary> summaries = new HashMap<>();
    String after = null;
    do {
      ResultList<AgentHarnessPersonaSummary> page = personaPage(100, after);
      page.getData().forEach(summary -> summaries.put(summary.getPersona().getId(), summary));
      after = page.getPaging().getAfter();
    } while (after != null);
    return summaries;
  }

  private static ResultList<AgentHarnessPersonaSummary> personaPage(int limit, String after) {
    String path =
        HARNESS + "/personas?limit=" + limit + (after == null ? "" : "&after=" + encoded(after));
    ResultList<?> page = read(send("GET", path, null, ADMIN_TOKEN), 200, ResultList.class);
    List<AgentHarnessPersonaSummary> rows =
        page.getData().stream()
            .map(row -> JsonUtils.convertValue(row, AgentHarnessPersonaSummary.class))
            .toList();
    return new ResultList<>(rows).setPaging(page.getPaging());
  }

  private static AgentHarnessSpec withRule(AgentHarnessSpec base, String name) {
    AgentHarnessSpec spec = JsonUtils.deepCopy(base, AgentHarnessSpec.class);
    spec.getContext()
        .setRules(
            new ArrayList<>(
                List.of(
                    new ContextRule()
                        .withName(name)
                        .withEntityType(Entity.TABLE)
                        .withQueryFilter(
                            "{\"query\":{\"term\":{\"fullyQualifiedName\":\"svc.db.schema."
                                + name
                                + "\"}}}")
                        .withMaxAssets(5))));
    return spec;
  }

  private static List<String> ruleNames(AgentHarnessSpec spec) {
    return ruleNames(spec.getContext().getRules());
  }

  private static List<String> ruleNames(List<ContextRule> rules) {
    return rules.stream().map(ContextRule::getName).toList();
  }
}
