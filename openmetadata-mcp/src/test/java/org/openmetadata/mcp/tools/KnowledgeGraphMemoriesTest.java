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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openmetadata.schema.api.rdf.RdfProjectionState;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.rdf.RdfRepository;
import org.openmetadata.service.rdf.explore.MemoryEntry;
import org.openmetadata.service.rdf.explore.MemoryNote;
import org.openmetadata.service.rdf.explore.NodeCard;
import org.openmetadata.service.rdf.explore.RunStep;
import org.openmetadata.service.rdf.explore.RunTraceStore;
import org.openmetadata.service.rdf.explore.SearchHit;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/** What knowledge_graph answers carry of context memories. */
class KnowledgeGraphMemoriesTest {

  private static final String E = JenaBackedRepository.BASE + "entity/";
  private static final UUID ORDERS_ID = UUID.fromString("0b0b0b0b-0000-0000-0000-000000000001");
  private static final UUID CUSTOMERS_ID = UUID.fromString("0b0b0b0b-0000-0000-0000-000000000002");
  private static final UUID ALICE_ID = UUID.fromString("0a0a0a0a-0000-0000-0000-00000000000a");
  private static final String ORDERS = E + "table/" + ORDERS_ID;
  private static final String CUSTOMERS = E + "table/" + CUSTOMERS_ID;
  private static final String ALICE = E + "user/" + ALICE_ID;
  private static final String TABLES =
      """
      <%1$s> a om:Table ; rdfs:label "orders" .
      <%2$s> a om:Table ; rdfs:label "customers" .
      """
          .formatted(ORDERS, CUSTOMERS);
  private static final String MEMORIES =
      """
      <%3$scontextMemory/tickets> a om:ContextMemory ; om:title "Test tickets" ;
          om:question "Which orders are test tickets?" ; om:answer "Orders with no lines." ;
          om:entityStatus "Approved" .
      <%1$s> om:appliedTo <%3$scontextMemory/tickets> .
      <%3$scontextMemory/refunds> a om:ContextMemory ; om:title "Refunds" ;
          om:answer "Refunded orders stay in the revenue of their month." ;
          om:entityStatus "Approved" .
      <%3$scontextMemory/euros> a om:ContextMemory ; om:title "Euros" ; om:memoryType "Preference" ;
          om:answer "Report amounts in euros." ; om:entityStatus "Approved" ; om:hasOwner <%2$s> .
      """
          .formatted(ORDERS, ALICE, E);
  private static final CatalogSecurityContext CALLER = RdfToolAuthorization.caller("alice");
  private static final Authorizer GRANTED = mock(Authorizer.class);

  private MockedStatic<DefaultAuthorizer> subjects;

  @BeforeEach
  void theCallerIsAlice() {
    subjects = mockStatic(DefaultAuthorizer.class);
    subjects
        .when(() -> DefaultAuthorizer.getSubjectContext(any(SecurityContext.class)))
        .thenReturn(new SubjectContext(new User().withId(ALICE_ID).withName("alice"), null, null));
  }

  @AfterEach
  void release() {
    subjects.close();
  }

  private static KnowledgeGraphTool tool(final String turtle) {
    final RdfRepository repository = JenaBackedRepository.ofTurtle(turtle);
    return new KnowledgeGraphTool(
        () -> repository,
        () -> RdfProjectionState.READY,
        (authorizer, caller, find) ->
            CatalogHits.of(
                List.of(
                    new SearchHit("table", "warehouse.shop.public.orders", ORDERS_ID),
                    new SearchHit("table", "warehouse.shop.public.customers", CUSTOMERS_ID))),
        Traces::new,
        Clock.systemUTC());
  }

  private static KnowledgeGraphTool.FindResponse find(
      final KnowledgeGraphTool tool, final Map<String, Object> arguments) throws IOException {
    return (KnowledgeGraphTool.FindResponse) tool.execute(GRANTED, CALLER, arguments);
  }

  @Test
  void aFindCarriesTheCardsMemoriesTheMatchingNotesThePreferencesAndTheNoteOnce()
      throws IOException {
    final KnowledgeGraphTool.FindResponse found =
        find(tool(TABLES + MEMORIES), Map.of("operation", "find", "question", "orders revenue"));

    final NodeCard orders = found.nodes().getFirst();
    assertThat(orders.id()).isEqualTo(ORDERS);
    assertThat(orders.memories().shown())
        .extracting(MemoryEntry::title)
        .containsExactly("Test tickets");
    assertThat(found.nodes().get(1).memories()).isNull();
    assertThat(found.notes().shown()).extracting(MemoryEntry::title).containsExactly("Refunds");
    assertThat(found.preferences()).extracting(MemoryEntry::title).containsExactly("Euros");
    final String json = JsonUtils.pojoToJson(found);
    assertThat(json.split(Pattern.quote(MemoryNote.TEXT), -1)).hasSize(2);
  }

  @Test
  void onlyTheFirstCallOfARunCarriesThePreferences() throws IOException {
    final KnowledgeGraphTool tool = tool(TABLES + MEMORIES);
    final KnowledgeGraphTool.FindResponse first =
        find(tool, Map.of("operation", "find", "question", "orders"));

    final KnowledgeGraphTool.FindResponse later =
        find(
            tool, Map.of("operation", "find", "question", "orders", "run", first.run().toString()));
    final KnowledgeGraphTool.Response expanded =
        tool.execute(
            GRANTED,
            CALLER,
            Map.of(
                "operation",
                "expand",
                "nodes",
                List.of(ORDERS),
                "relationships",
                List.of("om:appliedTo")));

    assertThat(first.preferences()).isNotEmpty();
    assertThat(later.preferences()).isNull();
    assertThat(JsonUtils.pojoToJson(later)).doesNotContain("preferences");
    assertThat(JsonUtils.pojoToJson(expanded)).contains("\"preferences\"", MemoryNote.TEXT);
  }

  @Test
  void anAnswerWithoutMemoriesHasNoMemoryFieldsAtAll() throws IOException {
    final KnowledgeGraphTool.FindResponse found =
        find(tool(TABLES), Map.of("operation", "find", "question", "orders"));

    final String json = JsonUtils.pojoToJson(found);
    assertThat(json)
        .doesNotContain("memories")
        .doesNotContain("notes")
        .doesNotContain("preferences")
        .doesNotContain("aboutMemories");
  }

  /** The model keeps the whole answer as text; the host gets the card as the typed part. */
  @Test
  void theModelReadsTheWholeAnswerAndTheHostDrawsTheCard() throws IOException {
    final CardResult result =
        tool(TABLES + MEMORIES)
            .executeWithCard(GRANTED, CALLER, Map.of("operation", "find", "question", "orders"));

    final io.modelcontextprotocol.spec.McpSchema.CallToolResult sent =
        DefaultToolContext.buildSuccessResult(result, "knowledge_graph");

    final String text =
        ((io.modelcontextprotocol.spec.McpSchema.TextContent) sent.content().getFirst()).text();
    assertThat(text).contains("\"reachable\"", "\"memories\"", MemoryNote.TEXT);
    final Map<?, ?> card = (Map<?, ?>) sent.structuredContent();
    assertThat(card.get("operation")).isEqualTo("find");
    assertThat((List<?>) card.get("nodes")).hasSize(2);
  }

  /** Keeps steps serialized, as the SQL store does. */
  private static final class Traces implements RunTraceStore {
    private final Map<UUID, List<String>> steps = new HashMap<>();

    @Override
    public void append(final UUID run, final RunStep step) {
      steps.computeIfAbsent(run, ignored -> new ArrayList<>()).add(JsonUtils.pojoToJson(step));
    }

    @Override
    public List<RunStep> steps(final UUID run) {
      return steps.getOrDefault(run, List.of()).stream()
          .map(json -> JsonUtils.readValue(json, RunStep.class))
          .toList();
    }
  }
}
