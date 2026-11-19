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
package org.openmetadata.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.mcp.tools.DeliveryLedger.Caller;
import org.openmetadata.mcp.tools.RememberTool;

class McpServerRememberClientTest {
  private static final McpSchema.Tool REMEMBER =
      McpSchema.Tool.builder("remember", Map.of("type", "object")).build();

  private static Map<String, Object> argumentsFrom(final String client) {
    final McpSchema.CallToolRequest request =
        new McpSchema.CallToolRequest(
            "remember", Map.of("fact", "ARR excludes trials", RememberTool.CLIENT_PARAM, "Forged"));
    return McpServer.withConversation(REMEMBER, new Caller("ana", client, null, null), request)
        .arguments();
  }

  @Test
  void aNamedAppIsRecordedAsTheClient() {
    assertThat(argumentsFrom("Claude Desktop"))
        .containsEntry(RememberTool.CLIENT_PARAM, "Claude Desktop");
  }

  @Test
  void aBridgeRuntimeNameIsLeftOutRatherThanShownAsTheApp() {
    assertThat(argumentsFrom("Node")).doesNotContainKey(RememberTool.CLIENT_PARAM);
  }
}
