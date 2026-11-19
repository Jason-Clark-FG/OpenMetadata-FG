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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.entity.context.ToolAccessLevel;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.aicontext.agents.ConnectionDefinition;
import org.openmetadata.service.aicontext.agents.ConnectionRegistry;
import org.openmetadata.service.aicontext.agents.ConnectionTool;
import org.openmetadata.service.aicontext.agents.ConnectionToolCall;
import org.openmetadata.service.aicontext.agents.ConnectionToolResult;
import org.openmetadata.service.rdf.explore.Operation;
import org.openmetadata.service.rdf.explore.RunContext;
import org.openmetadata.service.rdf.explore.RunStep;
import org.openmetadata.service.rdf.explore.RunTraceStore;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * A knowledge_graph delegate to an outside MCP server a distribution connected: the connection's
 * tools, one tool's schema, or the tool called as the caller. The server holds the connection and
 * its credential and makes the call; the caller's MCP Access rules decide what is listed and what
 * runs. Every answer is fitted to the response and recorded as a step of the run.
 */
@Slf4j
final class ConnectionDelegation {

  static final Duration WAIT = Duration.ofSeconds(90);

  /** Connections the first answer names at most. */
  static final int MAX_OFFERED = 25;

  private static final int SUMMARY_CHARS = 200;
  private static final int SHORT_DESCRIPTION_CHARS = 160;
  private static final int ARGUMENT_DESCRIPTION_CHARS = 120;
  private static final int ENUM_VALUES = 8;
  private static final int SHRINK_MARGIN = 64;

  static final String FLAGGED_LISTING =
      "Tools marked 'flagged' are flagged by the admin's MCP Access rules: say so to the person"
          + " when you use one, and weigh its result with care.";

  private static final String FLAGGED_TOOL =
      "The admin's MCP Access rules flag this tool (%s): say so to the person, and weigh its"
          + " result with care.";

  static final String BLOCKED_LISTING =
      "The tools under 'blocked' exist on this connection, but your organisation's MCP Access rules"
          + " block them. If the person asks for one, say it is blocked by their organisation's"
          + " rules, not that it does not exist.";

  private static final String NEXT =
      "Call delegate with this 'connection', 'tool' and 'arguments' (an object matching"
          + " inputSchema; {} when it takes none) to run it.";

  private static final ExecutorService EXECUTOR =
      Executors.newCachedThreadPool(
          task -> {
            final Thread thread = new Thread(task, "kg-connection");
            thread.setDaemon(true);
            return thread;
          });

  private final ConnectionRegistry registry;
  private final Duration wait;
  private final Clock clock;

  ConnectionDelegation(final ConnectionRegistry registry, final Duration wait, final Clock clock) {
    this.registry = Objects.requireNonNull(registry);
    this.wait = Objects.requireNonNull(wait);
    this.clock = Objects.requireNonNull(clock);
  }

  /** What a connection delegate answers, by the call's form. */
  sealed interface Answer permits ToolListing, ToolSchema, ToolCallAnswer {}

  /** A connection's tools the caller may use, with their arguments in short form. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ToolListing(
      String connection,
      String displayName,
      String description,
      List<ListedTool> tools,
      String warning,
      List<String> blocked,
      String aboutBlocked,
      String rest)
      implements Answer {}

  /** One listed tool; {@code arguments} is absent when it was left out to fit. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ListedTool(String name, String description, Boolean flagged, List<Argument> arguments) {
    ListedTool withoutArguments() {
      return new ListedTool(name, description, flagged, null);
    }

    ListedTool shortened() {
      return new ListedTool(name, cut(description, SHORT_DESCRIPTION_CHARS), flagged, arguments);
    }
  }

  /** One argument of a tool in short form. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Argument(String name, String type, boolean required, String description) {}

  /** One tool's full input schema; nothing was called. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ToolSchema(
      String connection,
      String tool,
      String description,
      Map<String, Object> inputSchema,
      String inputSchemaText,
      String warning,
      String next,
      String rest)
      implements Answer {}

  /** What the called tool answered, cut to the response. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ToolCallAnswer(
      String connection,
      String tool,
      ConnectionToolResult.Status status,
      String content,
      Object structured,
      String error,
      String warning,
      String rest)
      implements Answer {}

  /** A connection as the first answer names it: the count is of the tools offered to the caller. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Summary(String name, String displayName, String description, int tools) {}

  /** The connections offered to the caller now, as the first answer of a run names them. */
  List<Summary> offered(final ConnectionAccess access) {
    return access.offered(registry.connections()).stream()
        .limit(MAX_OFFERED)
        .map(
            connection ->
                new Summary(
                    connection.name(),
                    connection.displayName(),
                    cut(connection.description(), SUMMARY_CHARS),
                    access.offeredTools(connection).size()))
        .toList();
  }

  Answer delegate(
      final CatalogSecurityContext caller,
      final ConnectionAccess access,
      final RunContext run,
      final RunTraceStore traces,
      final KnowledgeGraphCall.ConnectionCall call,
      final long budget) {
    final ConnectionRegistry.Registered registered = registered(access, call.connection());
    final ConnectionDefinition connection = registered.definition();
    final List<ConnectionTool> offered = access.offeredTools(connection);
    final Answer answer;
    String status = null;
    if (call.tool() == null) {
      answer = listing(connection, offered, access, budget);
    } else {
      final ConnectionTool tool = permitted(connection, offered, access, call.tool());
      if (call.arguments() == null) {
        answer = schema(connection, tool, access, budget);
      } else {
        final ToolCallAnswer result = fitted(called(caller, registered, run, access, call), budget);
        status = result.status().name();
        answer = result;
      }
    }
    traces.append(
        run.run(),
        new RunStep(
            Operation.DELEGATE,
            clock.millis(),
            run.principal(),
            RunStep.Input.connection(call.connection(), call.tool(), call.arguments(), status),
            List.of(),
            List.of()));
    return answer;
  }

  private ConnectionRegistry.Registered registered(
      final ConnectionAccess access, final String name) {
    final Optional<ConnectionRegistry.Registered> registered = registry.find(name);
    if (registered.isEmpty()) {
      throw new IllegalArgumentException(
          "No connection '%s' is registered. Offered to you: %s."
              .formatted(name, offeredNames(access)));
    }
    if (access.readable() && !access.enables(name)) {
      throw new IllegalArgumentException(
          ("Your agent harness does not enable the connection '%s'. Enabled for you: %s. Answer"
                  + " without it, and do not reach it another way.")
              .formatted(name, offeredNames(access)));
    }
    final ConnectionDefinition connection = registered.get().definition();
    if (access.offeredTools(connection).isEmpty()) {
      final String firstTool =
          connection.tools().isEmpty() ? null : connection.tools().getFirst().name();
      throw new IllegalArgumentException(
          ("The connection '%s' is not offered to you: %s. Offered to you: %s. Answer without it,"
                  + " and do not reach it another way.")
              .formatted(
                  name,
                  connection.tools().isEmpty()
                      ? "it has no tools"
                      : "your organisation's MCP Access rules block every tool of it ("
                          + access.rule(name, firstTool)
                          + ")",
                  offeredNames(access)));
    }
    return registered.get();
  }

  private static ConnectionTool permitted(
      final ConnectionDefinition connection,
      final List<ConnectionTool> offered,
      final ConnectionAccess access,
      final String name) {
    final ConnectionTool tool =
        connection
            .tool(name)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        ("The connection '%s' has no tool '%s'. Its tools: %s. Call delegate with"
                                + " 'connection' alone to see their arguments.")
                            .formatted(connection.name(), name, toolNames(offered))));
    if (access.level(connection.name(), name) == ToolAccessLevel.BLOCK) {
      throw new IllegalArgumentException(
          ("Your organisation's MCP Access rules block the tool '%s' of the connection '%s': %s."
                  + " Answer without it, say so if the person needs it, and do not reach it"
                  + " another way.")
              .formatted(name, connection.name(), access.rule(connection.name(), name)));
    }
    return tool;
  }

  private ToolCallAnswer called(
      final CatalogSecurityContext caller,
      final ConnectionRegistry.Registered registered,
      final RunContext run,
      final ConnectionAccess access,
      final KnowledgeGraphCall.ConnectionCall call) {
    final ConnectionToolResult result =
        awaited(
            caller,
            registered,
            new ConnectionToolCall(call.connection(), call.tool(), call.arguments(), run.run()));
    return new ToolCallAnswer(
        call.connection(),
        call.tool(),
        result.status() == null ? ConnectionToolResult.Status.FAILED : result.status(),
        result.content(),
        result.structured(),
        result.error(),
        warning(access, call.connection(), call.tool()),
        null);
  }

  /** The provider's answer, bounded; a timeout or a throw is a failed result, not an exception. */
  private ConnectionToolResult awaited(
      final CatalogSecurityContext caller,
      final ConnectionRegistry.Registered registered,
      final ConnectionToolCall call) {
    final Future<ConnectionToolResult> task =
        EXECUTOR.submit(() -> registered.provider().call(caller, call));
    try {
      return Optional.ofNullable(task.get(wait.toMillis(), TimeUnit.MILLISECONDS))
          .orElse(ConnectionToolResult.failed("the connection answered nothing."));
    } catch (TimeoutException late) {
      task.cancel(true);
      return ConnectionToolResult.failed(
          "the connection '%s' did not answer within %d seconds."
              .formatted(call.connection(), wait.toSeconds()));
    } catch (ExecutionException failure) {
      LOG.warn("[KnowledgeGraph] connection {} failed", call.connection(), failure.getCause());
      return ConnectionToolResult.failed(String.valueOf(failure.getCause().getMessage()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return ConnectionToolResult.failed("the wait for the connection was interrupted.");
    }
  }

  private static String warning(
      final ConnectionAccess access, final String connection, final String tool) {
    return access.level(connection, tool) == ToolAccessLevel.WARN
        ? FLAGGED_TOOL.formatted(access.rule(connection, tool))
        : null;
  }

  // ---------------------------------------------------------------- listing

  static ToolListing listing(
      final ConnectionDefinition connection,
      final List<ConnectionTool> offered,
      final ConnectionAccess access,
      final long budget) {
    final List<ListedTool> tools = new ArrayList<>();
    offered.forEach(tool -> tools.add(listed(connection, tool, access)));
    final String warning =
        tools.stream().anyMatch(tool -> tool.flagged() != null) ? FLAGGED_LISTING : null;
    final List<String> blocked = access.blockedTools(connection);
    final Hidden hidden = blocked.isEmpty() ? Hidden.NONE : new Hidden(blocked, BLOCKED_LISTING);
    ToolListing candidate = listingOf(connection, tools, warning, hidden, null);
    int dropped = 0;
    while (!fits(candidate, budget) && dropped < tools.size()) {
      dropped++;
      final int last = tools.size() - dropped;
      tools.set(last, tools.get(last).withoutArguments());
      candidate = listingOf(connection, tools, warning, hidden, argumentsLeftOut(dropped));
    }
    if (!fits(candidate, budget)) {
      tools.replaceAll(ListedTool::shortened);
      candidate = listingOf(connection, tools, warning, hidden, argumentsLeftOut(dropped));
    }
    int removed = 0;
    while (!fits(candidate, budget) && tools.size() > 1) {
      tools.removeLast();
      removed++;
      candidate =
          listingOf(
              connection,
              tools,
              warning,
              hidden,
              argumentsLeftOut(dropped)
                  + " The last %d tools were left out too; ask for one by its 'tool' name if you know it."
                      .formatted(removed));
    }
    return candidate;
  }

  /** The tools the rules hide from a listing and the sentence that says so; both null if none. */
  private record Hidden(List<String> names, String about) {
    static final Hidden NONE = new Hidden(null, null);
  }

  private static ToolListing listingOf(
      final ConnectionDefinition connection,
      final List<ListedTool> tools,
      final String warning,
      final Hidden hidden,
      final String rest) {
    return new ToolListing(
        connection.name(),
        connection.displayName(),
        cut(connection.description(), SUMMARY_CHARS),
        List.copyOf(tools),
        warning,
        hidden.names(),
        hidden.about(),
        rest);
  }

  private static String argumentsLeftOut(final int dropped) {
    return dropped == 0
        ? null
        : ("The arguments of the last %d tools were left out to fit the response: call delegate"
                + " with 'connection' and one 'tool' to get its full schema.")
            .formatted(dropped);
  }

  private static ListedTool listed(
      final ConnectionDefinition connection,
      final ConnectionTool tool,
      final ConnectionAccess access) {
    final boolean flagged = access.level(connection.name(), tool.name()) == ToolAccessLevel.WARN;
    return new ListedTool(
        tool.name(),
        tool.description(),
        flagged ? Boolean.TRUE : null,
        arguments(tool.inputSchema()));
  }

  /** The schema's properties in short form: name, type, whether required, a short description. */
  static List<Argument> arguments(final Map<String, Object> schema) {
    final Map<?, ?> properties =
        schema != null && schema.get("properties") instanceof Map<?, ?> map ? map : Map.of();
    final List<?> required =
        schema != null && schema.get("required") instanceof List<?> list ? list : List.of();
    return properties.entrySet().stream()
        .map(
            entry -> {
              final Map<?, ?> property = entry.getValue() instanceof Map<?, ?> map ? map : Map.of();
              return new Argument(
                  String.valueOf(entry.getKey()),
                  typeOf(property),
                  required.contains(entry.getKey()),
                  cut(stringOf(property.get("description")), ARGUMENT_DESCRIPTION_CHARS));
            })
        .toList();
  }

  private static String typeOf(final Map<?, ?> property) {
    final Object type = property.get("type");
    String name;
    if (type instanceof List<?> types) {
      name = String.join("|", types.stream().map(String::valueOf).toList());
    } else if (type != null) {
      name = String.valueOf(type);
    } else {
      name =
          property.containsKey("anyOf") || property.containsKey("oneOf") ? "one of several" : "any";
    }
    if ("array".equals(name) && property.get("items") instanceof Map<?, ?> items) {
      name = "array of " + typeOf(items);
    }
    if (property.get("enum") instanceof List<?> values && !values.isEmpty()) {
      name +=
          " (one of "
              + String.join(", ", values.stream().limit(ENUM_VALUES).map(String::valueOf).toList())
              + (values.size() > ENUM_VALUES ? ", …" : "")
              + ")";
    }
    return name;
  }

  // ---------------------------------------------------------------- schema

  static ToolSchema schema(
      final ConnectionDefinition connection,
      final ConnectionTool tool,
      final ConnectionAccess access,
      final long budget) {
    final String warning = warning(access, connection.name(), tool.name());
    final ToolSchema whole =
        new ToolSchema(
            connection.name(),
            tool.name(),
            tool.description(),
            tool.inputSchema(),
            null,
            warning,
            NEXT,
            null);
    if (fits(whole, budget)) {
      return whole;
    }
    final String text = JsonUtils.pojoToJson(tool.inputSchema());
    final String rest =
        ("The schema is %d characters, more than the response holds, so inputSchemaText is its"
                + " first part as JSON text; build the arguments from what it shows.")
            .formatted(text.length());
    return shrunk(
        text,
        kept ->
            new ToolSchema(
                connection.name(),
                tool.name(),
                cut(tool.description(), SHORT_DESCRIPTION_CHARS),
                null,
                kept,
                warning,
                NEXT,
                rest),
        budget);
  }

  // ---------------------------------------------------------------- call result

  /**
   * The answer cut to {@code budget}: the structured result goes first (as JSON text when there is
   * no other text), then the text is cut to its first part.
   */
  static ToolCallAnswer fitted(final ToolCallAnswer answer, final long budget) {
    if (fits(answer, budget)) {
      return answer;
    }
    final List<String> cuts = new ArrayList<>();
    String content = answer.content();
    final boolean asText =
        answer.structured() != null && answer.content() == null && answer.error() == null;
    if (answer.structured() != null) {
      final String json = JsonUtils.pojoToJson(answer.structured());
      cuts.add(
          asText
              ? "the structured result (%d characters) is given as JSON text in 'content'"
                  .formatted(json.length())
              : "the structured result (%d characters) was left out".formatted(json.length()));
      content = asText ? json : content;
    }
    final String finalContent = content;
    ToolCallAnswer fitted = withText(answer, finalContent, answer.error(), rest(cuts));
    final String text = finalContent != null ? finalContent : answer.error();
    if (!fits(fitted, budget) && text != null) {
      cuts.add("the text was cut to its first part, of %d characters".formatted(text.length()));
      final String cutRest = rest(cuts);
      fitted =
          shrunk(
              text,
              kept ->
                  finalContent != null
                      ? withText(answer, kept, answer.error(), cutRest)
                      : withText(answer, null, kept, cutRest),
              budget);
    }
    return fitted;
  }

  /** The answer without its structured result, with this text and error. */
  private static ToolCallAnswer withText(
      final ToolCallAnswer answer, final String content, final String error, final String rest) {
    return new ToolCallAnswer(
        answer.connection(),
        answer.tool(),
        answer.status(),
        content,
        null,
        error,
        answer.warning(),
        rest);
  }

  /** The answer {@code build} makes from the longest first part of {@code text} that fits. */
  private static <T> T shrunk(
      final String text, final Function<String, T> build, final long budget) {
    long keep = text.length();
    T candidate = build.apply(text);
    for (int attempt = 0; attempt < 10 && !fits(candidate, budget) && keep > 0; attempt++) {
      keep = Math.max(0, keep - (chars(candidate) - budget) - SHRINK_MARGIN);
      candidate = build.apply(cutTo(text, keep));
    }
    return candidate;
  }

  private static String rest(final List<String> cuts) {
    return "Cut to fit the response: "
        + String.join("; ", cuts)
        + ". Call the tool again with narrower arguments (a filter, a smaller page) to see what was"
        + " cut.";
  }

  // ---------------------------------------------------------------- helpers

  private String offeredNames(final ConnectionAccess access) {
    final List<String> names =
        access.offered(registry.connections()).stream().map(ConnectionDefinition::name).toList();
    return names.isEmpty() ? "none" : String.join(", ", names);
  }

  private static String toolNames(final List<ConnectionTool> tools) {
    return tools.isEmpty()
        ? "none"
        : String.join(", ", tools.stream().map(ConnectionTool::name).toList());
  }

  private static boolean fits(final Object answer, final long budget) {
    return chars(answer) <= budget;
  }

  private static long chars(final Object answer) {
    return JsonUtils.pojoToJson(answer).length();
  }

  private static String stringOf(final Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /** {@code text} at most {@code max} characters, ending in an ellipsis when cut. */
  private static String cut(final String text, final int max) {
    return text == null || text.length() <= max
        ? text
        : text.substring(0, max - 1).stripTrailing() + "…";
  }

  private static String cutTo(final String text, final long max) {
    return text.length() <= max ? text : text.substring(0, (int) max) + "…";
  }
}
