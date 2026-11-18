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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.openmetadata.it.auth.JwtAuthProvider;
import org.openmetadata.it.bootstrap.TestSuiteBootstrap;
import org.openmetadata.schema.api.context.AgentHarnessState;
import org.openmetadata.schema.api.teams.CreatePersona;
import org.openmetadata.schema.api.teams.CreateUser;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.utils.JsonUtils;

/** HTTP calls to the agent harness API, with the If-Match header the draft endpoints take. */
final class AgentHarnessApi {
  static final String HARNESS = "agentHarness";
  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  private static final long TOKEN_TTL_SECONDS = 3_600;
  static final String ADMIN_TOKEN =
      bearer(
          JwtAuthProvider.tokenFor(
              "admin@open-metadata.org",
              "admin@open-metadata.org",
              new String[] {"admin"},
              TOKEN_TTL_SECONDS));

  private AgentHarnessApi() {}

  static HttpResponse<String> send(
      final String method, final String path, final Object body, final String token) {
    return send(method, path, body, token, null);
  }

  static HttpResponse<String> send(
      final String method,
      final String path,
      final Object body,
      final String token,
      final Integer ifMatch) {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create(TestSuiteBootstrap.getBaseUrl() + "/api/v1/" + path))
            .header("Authorization", token)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JsonUtils.pojoToJson(body)))
            .timeout(Duration.ofSeconds(30));
    if (ifMatch != null) {
      request.header("If-Match", "\"" + ifMatch + "\"");
    }
    try {
      return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException exception) {
      throw new IllegalStateException(method + " " + path + " failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(method + " " + path + " was interrupted", exception);
    }
  }

  static <T> T read(final HttpResponse<String> response, final int status, final Class<T> type) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    return JsonUtils.readValue(response.body(), type);
  }

  static AgentHarnessState admin(
      final String method, final String path, final Object body, final Integer ifMatch) {
    return read(
        send(method, HARNESS + path, body, ADMIN_TOKEN, ifMatch), 200, AgentHarnessState.class);
  }

  static AgentHarnessState createPersonaHarness(final Persona persona) {
    return read(
        send(
            "POST",
            HARNESS + "/persona/" + encoded(persona.getFullyQualifiedName()),
            null,
            ADMIN_TOKEN),
        201,
        AgentHarnessState.class);
  }

  static Persona createPersona(final String name, final List<UUID> users) {
    return read(
        send(
            "POST",
            "personas",
            new CreatePersona()
                .withName(name)
                .withDescription("Agent harness test")
                .withUsers(users),
            ADMIN_TOKEN),
        201,
        Persona.class);
  }

  static User createUser(final String name) {
    return read(
        send(
            "POST",
            "users",
            new CreateUser().withName(name).withEmail(name + "@example.com"),
            ADMIN_TOKEN),
        201,
        User.class);
  }

  static String tokenFor(final User user) {
    return bearer(
        JwtAuthProvider.tokenFor(
            user.getEmail(), user.getEmail(), new String[] {}, TOKEN_TTL_SECONDS));
  }

  static String encoded(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  static String uniqueName(final String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String bearer(final String token) {
    return "Bearer " + token;
  }
}
