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

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.utils.JsonUtils;

/** How a query answer too large for its response keeps the rows that fit. */
class QueryRowsTest {

  private static final String BODY =
      """
      {"head":{"vars":["s"]},
       "results":{"distinct":false,"bindings":[
         {"s":{"type":"uri","value":"https://example.org/1"}},
         {"s":{"type":"uri","value":"https://example.org/2"}},
         {"s":{"type":"uri","value":"https://example.org/3"}}]},
       "metadata":{"completeness":{"status":"complete"}}}
      """;

  private static SparqlQueryTool.Result result() {
    return new SparqlQueryTool.Result("json", "SELECT", BODY, false, BODY.length(), null, null);
  }

  @Test
  void aCutAnswerKeepsTheLeadingRowsAndEveryOtherPartOfTheBody() {
    final QueryRows.Fitted fitted =
        QueryRows.fit(result(), candidate -> candidate.result().body(), size -> size < 230)
            .orElseThrow();

    final JsonNode body = JsonUtils.readTree(fitted.result().body());
    assertThat(fitted.rows().returned()).isPositive().isLessThan(3);
    assertThat(fitted.rows().total()).isEqualTo(3);
    assertThat(body.path("head").path("vars").toString()).isEqualTo("[\"s\"]");
    assertThat(body.path("results").path("distinct").asBoolean(true)).isFalse();
    assertThat(body.path("results").path("bindings")).hasSize(fitted.rows().returned());
    assertThat(body.path("results").path("bindings").get(0).path("s").path("value").asText())
        .isEqualTo("https://example.org/1");
    assertThat(body.path("metadata").path("completeness").path("status").asText())
        .isEqualTo("complete");
    assertThat(result().body()).isEqualTo(BODY);
  }

  @Test
  void anAnswerThatFitsIsSentAsItCame() {
    final QueryRows.Fitted fitted =
        QueryRows.fit(result(), candidate -> candidate.result().body(), size -> true).orElseThrow();

    assertThat(fitted.result()).isEqualTo(result());
    assertThat(fitted.rows()).isEqualTo(new QueryRows(3, 3, null));
  }
}
