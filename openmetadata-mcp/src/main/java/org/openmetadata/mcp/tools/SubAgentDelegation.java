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

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.service.aicontext.agents.SubAgentDefinition;
import org.openmetadata.service.aicontext.agents.SubAgentNode;
import org.openmetadata.service.aicontext.agents.SubAgentProvider;
import org.openmetadata.service.aicontext.agents.SubAgentRegistry;
import org.openmetadata.service.aicontext.agents.SubAgentRequest;
import org.openmetadata.service.aicontext.agents.SubAgentResult;
import org.openmetadata.service.rdf.explore.Operation;
import org.openmetadata.service.rdf.explore.RunContext;
import org.openmetadata.service.rdf.explore.RunStep;
import org.openmetadata.service.rdf.explore.RunTraceStore;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Hands a knowledge_graph task to a registered sub-agent as the caller, waits a bounded time, and
 * keeps a task that outlives the wait under a handle the caller polls with.
 */
@Slf4j
final class SubAgentDelegation {

  static final Duration WAIT = Duration.ofSeconds(240);

  private static final Duration KEPT = Duration.ofHours(1);

  private static final ExecutorService EXECUTOR =
      Executors.newCachedThreadPool(
          task -> {
            final Thread thread = new Thread(task, "kg-sub-agent");
            thread.setDaemon(true);
            return thread;
          });

  private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

  private record Pending(String agent, String principal, long since, Future<SubAgentResult> task) {}

  private final SubAgentRegistry registry;
  private final Duration wait;
  private final Clock clock;

  SubAgentDelegation(final SubAgentRegistry registry, final Duration wait, final Clock clock) {
    this.registry = Objects.requireNonNull(registry);
    this.wait = Objects.requireNonNull(wait);
    this.clock = Objects.requireNonNull(clock);
  }

  SubAgentResult delegate(
      final CatalogSecurityContext caller,
      final KnowledgeGraphHarness harness,
      final RunContext run,
      final RunTraceStore traces,
      final KnowledgeGraphCall.Delegate call) {
    final SubAgentProvider provider = providerFor(harness, call.agent());
    purgeExpired();
    final SubAgentResult result =
        call.handle() == null
            ? start(caller, provider, run, traces, call)
            : poll(caller, provider, run, call);
    traces.append(
        run.run(),
        new RunStep(
            Operation.DELEGATE,
            clock.millis(),
            run.principal(),
            RunStep.Input.delegate(call.agent(), call.task(), call.nodes()),
            List.of(),
            List.of()));
    return result;
  }

  private SubAgentProvider providerFor(final KnowledgeGraphHarness harness, final String agent) {
    if (registry.agent(agent).isEmpty()) {
      throw new IllegalArgumentException(
          "No sub-agent '%s' is registered. Registered: %s."
              .formatted(agent, names(registry.agents())));
    }
    if (harness.agent(agent).isEmpty()) {
      throw new IllegalArgumentException(
          "Your agent harness does not enable the sub-agent '%s'. Enabled for you: %s."
              .formatted(agent, names(harness.agents())));
    }
    return registry.providerOf(agent).orElseThrow();
  }

  private SubAgentResult start(
      final CatalogSecurityContext caller,
      final SubAgentProvider provider,
      final RunContext run,
      final RunTraceStore traces,
      final KnowledgeGraphCall.Delegate call) {
    final SubAgentRequest request =
        new SubAgentRequest(
            call.agent(),
            call.task(),
            nodes(traces.steps(run.run()), call.nodes()),
            run.run(),
            wait);
    final Future<SubAgentResult> task = EXECUTOR.submit(() -> provider.run(caller, request));
    return awaited(call.agent(), run.principal(), task);
  }

  private SubAgentResult awaited(
      final String agent, final String principal, final Future<SubAgentResult> task) {
    try {
      return task.get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException stillRunning) {
      final String handle = UUID.randomUUID().toString();
      PENDING.put(handle, new Pending(agent, principal, clock.millis(), task));
      return SubAgentResult.running(agent, handle);
    } catch (ExecutionException failure) {
      LOG.warn("[KnowledgeGraph] sub-agent {} failed", agent, failure.getCause());
      return SubAgentResult.failed(agent, String.valueOf(failure.getCause().getMessage()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return SubAgentResult.failed(agent, "the wait for the sub-agent was interrupted.");
    }
  }

  private SubAgentResult poll(
      final CatalogSecurityContext caller,
      final SubAgentProvider provider,
      final RunContext run,
      final KnowledgeGraphCall.Delegate call) {
    final Pending pending = PENDING.get(call.handle());
    final boolean ours =
        pending != null
            && pending.agent().equals(call.agent())
            && Objects.equals(pending.principal(), run.principal());
    return ours ? pending(call.handle(), pending) : provided(caller, provider, call);
  }

  private SubAgentResult pending(final String handle, final Pending pending) {
    final boolean done = pending.task().isDone();
    if (done) {
      PENDING.remove(handle);
    }
    return done
        ? awaited(pending.agent(), pending.principal(), pending.task())
        : SubAgentResult.running(pending.agent(), handle);
  }

  private static SubAgentResult provided(
      final CatalogSecurityContext caller,
      final SubAgentProvider provider,
      final KnowledgeGraphCall.Delegate call) {
    return provider
        .result(caller, call.handle())
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No task of '%s' has the handle '%s'; pass the handle its delegate answer returned."
                        .formatted(call.agent(), call.handle())));
  }

  private void purgeExpired() {
    final long oldest = clock.millis() - KEPT.toMillis();
    PENDING
        .entrySet()
        .removeIf(
            entry -> {
              final boolean expired = entry.getValue().since() < oldest;
              if (expired) {
                entry.getValue().task().cancel(true);
              }
              return expired;
            });
  }

  /** The nodes passed, described as the run's cards showed them. */
  private static List<SubAgentNode> nodes(final List<RunStep> steps, final List<String> ids) {
    final Map<String, RunStep.Node> seen = new LinkedHashMap<>();
    steps.forEach(step -> step.nodes().forEach(node -> seen.putIfAbsent(node.id(), node)));
    return ids.stream()
        .map(
            id ->
                Optional.ofNullable(seen.get(id))
                    .map(
                        node ->
                            new SubAgentNode(
                                id, node.label(), node.entityType(), node.fullyQualifiedName()))
                    .orElseGet(() -> new SubAgentNode(id, null, null, null)))
        .toList();
  }

  private static String names(final List<SubAgentDefinition> agents) {
    return agents.isEmpty()
        ? "none"
        : String.join(", ", agents.stream().map(SubAgentDefinition::name).toList());
  }
}
