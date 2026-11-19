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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongPredicate;
import org.openmetadata.schema.utils.JsonUtils;

/**
 * How many of a query's rows an answer carries: all of them, or the leading ones that fit the
 * answer's size, with the total and how to read the rest. {@code rest} is set only when rows were
 * left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryRows(int returned, int total, String rest) {

  private static final String RESULTS = "results";
  private static final String BINDINGS = "bindings";
  private static final String REST =
      "%d of %d rows are here. Run the query again with ORDER BY and OFFSET %d to read the next"
          + " ones, or select fewer variables or rows.";

  boolean isEmpty() {
    return total == 0;
  }

  /** A SELECT body's rows cut to the most that keep {@code fits} true, or the body as it came. */
  static Optional<Fitted> fit(
      final SparqlQueryTool.Result result,
      final Function<Fitted, Object> answer,
      final LongPredicate fits) {
    return bindings(result.body()).map(rows -> fitted(result, rows, answer, fits));
  }

  /** The result to send and the rows it carries. */
  record Fitted(SparqlQueryTool.Result result, QueryRows rows) {}

  private static Fitted fitted(
      final SparqlQueryTool.Result result,
      final ParsedRows parsed,
      final Function<Fitted, Object> answer,
      final LongPredicate fits) {
    final int total = parsed.bindings().size();
    final Fitted whole = new Fitted(result, new QueryRows(total, total, null));
    if (fits.test(sizeOf(answer.apply(whole)))) {
      return whole;
    }
    int low = 0;
    int high = total - 1;
    while (low < high) {
      final int middle = (low + high + 1) / 2;
      if (fits.test(sizeOf(answer.apply(cut(result, parsed, middle))))) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return cut(result, parsed, low);
  }

  /**
   * The body with only the leading {@code returned} rows: every other field the store sent, in its
   * order, shares the parsed nodes, so no step of the search copies the whole answer.
   */
  private static Fitted cut(
      final SparqlQueryTool.Result result, final ParsedRows parsed, final int returned) {
    final ArrayNode kept = JsonNodeFactory.instance.arrayNode();
    for (int index = 0; index < returned; index++) {
      kept.add(parsed.bindings().get(index));
    }
    final ObjectNode body =
        replacing(parsed.root(), RESULTS, replacing(parsed.results(), BINDINGS, kept));
    final int total = parsed.bindings().size();
    return new Fitted(
        new SparqlQueryTool.Result(
            result.format(),
            result.queryType(),
            JsonUtils.pojoToJson(body),
            result.truncated(),
            result.byteCount(),
            result.warning(),
            result.completeness()),
        new QueryRows(returned, total, REST.formatted(returned, total, returned)));
  }

  private static ObjectNode replacing(
      final ObjectNode node, final String name, final JsonNode replacement) {
    final ObjectNode copy = JsonNodeFactory.instance.objectNode();
    node.fields()
        .forEachRemaining(
            field ->
                copy.set(
                    field.getKey(), name.equals(field.getKey()) ? replacement : field.getValue()));
    return copy;
  }

  private static long sizeOf(final Object answer) {
    return JsonUtils.pojoToJson(answer).length();
  }

  private record ParsedRows(ObjectNode root, ObjectNode results, ArrayNode bindings) {}

  private static Optional<ParsedRows> bindings(final String body) {
    try {
      final JsonNode root = JsonUtils.readTree(body);
      return root instanceof ObjectNode object
              && object.path(RESULTS) instanceof ObjectNode results
              && results.path(BINDINGS) instanceof ArrayNode rows
          ? Optional.of(new ParsedRows(object, results, rows))
          : Optional.empty();
    } catch (RuntimeException unreadable) {
      return Optional.empty();
    }
  }
}
