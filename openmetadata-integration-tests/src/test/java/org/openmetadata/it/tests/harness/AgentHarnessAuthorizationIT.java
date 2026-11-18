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
import static org.openmetadata.it.tests.harness.AgentHarnessApi.createUser;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.encoded;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.read;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.send;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.tokenFor;
import static org.openmetadata.it.tests.harness.AgentHarnessApi.uniqueName;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.User;

@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentHarnessAuthorizationIT {
  private AgentHarnessState harness;
  private Persona personaWithoutHarness;
  private String memberToken;
  private String botToken;

  private record Call(String method, String path, Object body, Integer ifMatch) {
    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  @BeforeAll
  void setUp() {
    User member = createUser(uniqueName("harness_member"));
    memberToken = tokenFor(member);
    User bot = read(send("GET", "users/name/ingestion-bot", null, ADMIN_TOKEN), 200, User.class);
    botToken = tokenFor(bot);
    harness =
        createPersonaHarness(createPersona(uniqueName("harness_auth"), List.of(member.getId())));
    personaWithoutHarness = createPersona(uniqueName("harness_auth_none"), List.of());
  }

  @Test
  void nonAdminUsersAndBotsAreRefusedOnEveryAdminEndpoint() {
    for (Map.Entry<String, String> caller :
        Map.of("member", memberToken, "bot", botToken).entrySet()) {
      for (Call call : adminCalls()) {
        assertThat(
                send(call.method(), call.path(), call.body(), caller.getValue(), call.ifMatch())
                    .statusCode())
            .as(caller.getKey() + " " + call)
            .isEqualTo(403);
      }
    }

    AgentHarnessState after =
        admin(
            "GET",
            "/persona/" + encoded(harness.getPublished().getPersona().getFullyQualifiedName()),
            null,
            null);
    assertThat(after.getDraftRevision()).isEqualTo(harness.getDraftRevision());
    assertThat(after.getPublished().getEnabled()).isTrue();
    assertThat(after.getPublished().getVersion()).isEqualTo(harness.getPublished().getVersion());
    assertThat(
            send(
                    "GET",
                    HARNESS + "/persona/" + encoded(personaWithoutHarness.getFullyQualifiedName()),
                    null,
                    ADMIN_TOKEN)
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void anAdminReadsEveryRegistryAndTheGenericEntityWritesAreNotExposed() {
    for (String path : List.of("/default", "/guardrails", "/skills", "/personas")) {
      assertThat(send("GET", HARNESS + path, null, ADMIN_TOKEN).statusCode())
          .as(path)
          .isEqualTo(200);
    }
    String byId = HARNESS + "/" + harness.getPublished().getId();
    for (Call call :
        List.of(
            new Call("PUT", HARNESS, harness.getPublished(), null),
            new Call("POST", HARNESS, harness.getPublished(), null),
            new Call("PATCH", byId, List.of(), null),
            new Call("DELETE", byId, null, null),
            new Call("GET", byId, null, null))) {
      assertThat(send(call.method(), call.path(), call.body(), ADMIN_TOKEN, null).statusCode())
          .as(call.toString())
          .isIn(404, 405);
    }
  }

  private List<Call> adminCalls() {
    String id = HARNESS + "/" + harness.getPublished().getId();
    String personaPath =
        HARNESS
            + "/persona/"
            + encoded(harness.getPublished().getPersona().getFullyQualifiedName());
    return List.of(
        new Call("GET", HARNESS + "/default", null, null),
        new Call("GET", personaPath, null, null),
        new Call(
            "POST",
            HARNESS + "/persona/" + encoded(personaWithoutHarness.getFullyQualifiedName()),
            null,
            null),
        new Call("GET", HARNESS + "/personas", null, null),
        new Call("PUT", id + "/draft", harness.getDraft(), harness.getDraftRevision()),
        new Call("DELETE", id + "/draft", null, harness.getDraftRevision()),
        new Call("POST", id + "/publish", null, harness.getDraftRevision()),
        new Call("PUT", id + "/enabled", false, null),
        new Call("GET", id + "/versions", null, null),
        new Call("GET", id + "/versions/0.1", null, null),
        new Call("GET", HARNESS + "/guardrails", null, null),
        new Call("GET", HARNESS + "/skills", null, null),
        new Call("GET", id + "/context", null, null),
        new Call(
            "GET", HARNESS + "/name/" + harness.getPublished().getName() + "/context", null, null),
        new Call(
            "GET", HARNESS + "/history?startTs=0&endTs=" + System.currentTimeMillis(), null, null));
  }
}
