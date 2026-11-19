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
package org.openmetadata.service.aicontext;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.openmetadata.schema.api.context.LineageCard;
import org.openmetadata.schema.api.context.LineageCardColumn;
import org.openmetadata.schema.api.context.LineageCardImpact;
import org.openmetadata.schema.api.context.LineageCardImpactGroup;
import org.openmetadata.schema.api.context.LineageCardNode;
import org.openmetadata.schema.type.ColumnLineage;
import org.openmetadata.schema.type.Edge;
import org.openmetadata.schema.type.EntityLineage;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.answer.EntityLinks;
import org.openmetadata.service.util.FullyQualifiedName;

/**
 * Shapes a lineage graph the caller may view into the card an MCP Apps host shows: the assets
 * within {@value #CARD_HOPS} hops each way, what depends on the asset, and its column mappings. The
 * graph it takes was already filtered by the caller's permissions; this only reads from it.
 */
public final class LineageCardBuilder {
  static final int CARD_HOPS = 2;
  static final int MAX_PER_SIDE = 6;
  static final int MAX_FACT_NODES = 40;
  static final int MAX_COLUMN_MAPPINGS = 12;
  private static final List<String> IMPACT_TYPES = List.of(Entity.DASHBOARD, Entity.MLMODEL);

  /** What the card says about one asset beyond what the graph holds. */
  public record NodeFacts(
      String tier, String certification, String detail, List<String> teams, String failingLabel) {
    static final NodeFacts NONE = new NodeFacts(null, null, null, List.of(), null);
  }

  /** Reads {@link NodeFacts} for a bounded set of nodes; only {@code checked} ones are tested. */
  public interface Facts {
    Map<UUID, NodeFacts> read(List<EntityReference> nodes, Set<UUID> checked);
  }

  private record Reach(Map<UUID, Integer> hops, Map<UUID, UUID> parents) {}

  private final EntityLinks links;
  private final Facts facts;

  public LineageCardBuilder(final EntityLinks links, final Facts facts) {
    this.links = links;
    this.facts = facts;
  }

  public LineageCard build(final EntityLineage lineage, final int hiddenNodes) {
    final EntityReference root = lineage.getEntity();
    final Map<UUID, EntityReference> index = new HashMap<>();
    listOrEmpty(lineage.getNodes()).forEach(node -> index.put(node.getId(), node));
    index.put(root.getId(), root);
    final Reach upstream = reach(lineage.getUpstreamEdges(), root.getId(), true);
    final Reach downstream = reach(lineage.getDownstreamEdges(), root.getId(), false);
    final List<EntityReference> upstreamShown = shown(upstream, index);
    final List<EntityReference> downstreamShown = shown(downstream, index);
    final Map<UUID, NodeFacts> read =
        facts.read(
            factNodes(root, upstreamShown, downstreamShown, downstream, index),
            tables(upstreamShown, downstreamShown));
    return new LineageCard()
        .withRoot(node(root, 0, read))
        .withUpstream(nodes(upstreamShown, upstream, read))
        .withDownstream(nodes(downstreamShown, downstream, read))
        .withUpstreamTotal(within(upstream))
        .withDownstreamTotal(within(downstream))
        .withUpstreamHops(maxHop(upstream))
        .withDownstreamHops(maxHop(downstream))
        .withHiddenNodes(hiddenNodes)
        .withImpact(impact(downstream, upstreamShown, index, read))
        .withColumns(columns(lineage, root.getId()))
        .withColumnsTotal(columnMappings(lineage, root.getId()).size());
  }

  private static Reach reach(final List<Edge> edges, final UUID root, final boolean upstream) {
    final Map<UUID, List<UUID>> next = new HashMap<>();
    for (final Edge edge : listOrEmpty(edges)) {
      final UUID from = upstream ? edge.getToEntity() : edge.getFromEntity();
      final UUID to = upstream ? edge.getFromEntity() : edge.getToEntity();
      next.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
    }
    final Map<UUID, Integer> hops = new LinkedHashMap<>();
    final Map<UUID, UUID> parents = new HashMap<>();
    final Deque<UUID> queue = new ArrayDeque<>(List.of(root));
    hops.put(root, 0);
    while (!queue.isEmpty()) {
      final UUID current = queue.poll();
      for (final UUID neighbour : next.getOrDefault(current, List.of())) {
        if (!hops.containsKey(neighbour)) {
          hops.put(neighbour, hops.get(current) + 1);
          parents.put(neighbour, current);
          queue.add(neighbour);
        }
      }
    }
    hops.remove(root);
    return new Reach(hops, parents);
  }

  private static int within(final Reach reach) {
    return (int) reach.hops().values().stream().filter(hop -> hop <= CARD_HOPS).count();
  }

  private static int maxHop(final Reach reach) {
    return reach.hops().values().stream().mapToInt(Integer::intValue).max().orElse(0);
  }

  private static List<EntityReference> shown(
      final Reach reach, final Map<UUID, EntityReference> index) {
    return reach.hops().entrySet().stream()
        .filter(entry -> entry.getValue() <= CARD_HOPS && index.containsKey(entry.getKey()))
        .sorted(
            Map.Entry.<UUID, Integer>comparingByValue()
                .thenComparing(entry -> label(index.get(entry.getKey()))))
        .limit(MAX_PER_SIDE)
        .map(entry -> index.get(entry.getKey()))
        .toList();
  }

  /**
   * The asset, what is shown, then the rest of what depends on it, up to a ceiling: the teams that
   * own downstream assets are read from them.
   */
  private static List<EntityReference> factNodes(
      final EntityReference root,
      final List<EntityReference> upstreamShown,
      final List<EntityReference> downstreamShown,
      final Reach downstream,
      final Map<UUID, EntityReference> index) {
    final Map<UUID, EntityReference> wanted = new LinkedHashMap<>();
    wanted.put(root.getId(), root);
    upstreamShown.forEach(node -> wanted.put(node.getId(), node));
    downstreamShown.forEach(node -> wanted.put(node.getId(), node));
    for (final UUID id : downstream.hops().keySet()) {
      if (wanted.size() >= MAX_FACT_NODES) {
        break;
      }
      if (index.containsKey(id)) {
        wanted.putIfAbsent(id, index.get(id));
      }
    }
    return new ArrayList<>(wanted.values());
  }

  private static Set<UUID> tables(
      final List<EntityReference> upstreamShown, final List<EntityReference> downstreamShown) {
    final Set<UUID> tables = new LinkedHashSet<>();
    Stream.concat(upstreamShown.stream(), downstreamShown.stream())
        .filter(node -> Entity.TABLE.equals(node.getType()))
        .forEach(node -> tables.add(node.getId()));
    return tables;
  }

  private List<LineageCardNode> nodes(
      final List<EntityReference> shown, final Reach reach, final Map<UUID, NodeFacts> read) {
    return shown.stream().map(node -> node(node, reach.hops().get(node.getId()), read)).toList();
  }

  private LineageCardNode node(
      final EntityReference reference, final int hop, final Map<UUID, NodeFacts> read) {
    final NodeFacts facts = read.getOrDefault(reference.getId(), NodeFacts.NONE);
    final LineageCardNode node =
        new LineageCardNode()
            .withEntityType(reference.getType())
            .withName(reference.getName())
            .withDisplayName(reference.getDisplayName())
            .withFqn(reference.getFullyQualifiedName())
            .withUrl(links.ofFqn(reference.getType(), reference.getFullyQualifiedName()))
            .withTier(facts.tier())
            .withCertification(facts.certification())
            .withDetail(facts.detail())
            .withHasFailingCheck(facts.failingLabel() != null)
            .withFailingLabel(facts.failingLabel());
    return hop > 0 ? node.withHop(hop) : node;
  }

  private static LineageCardImpact impact(
      final Reach downstream,
      final List<EntityReference> upstreamShown,
      final Map<UUID, EntityReference> index,
      final Map<UUID, NodeFacts> read) {
    final Map<String, Integer> counts = new HashMap<>();
    final Set<String> teams = new HashSet<>();
    for (final UUID id : downstream.hops().keySet()) {
      final EntityReference node = index.get(id);
      if (node != null) {
        counts.merge(node.getType(), 1, Integer::sum);
        teams.addAll(read.getOrDefault(id, NodeFacts.NONE).teams());
      }
    }
    final List<LineageCardImpactGroup> groups =
        IMPACT_TYPES.stream()
            .filter(counts::containsKey)
            .map(
                type ->
                    new LineageCardImpactGroup().withEntityType(type).withCount(counts.get(type)))
            .toList();
    final List<String> failing =
        upstreamShown.stream()
            .filter(node -> read.getOrDefault(node.getId(), NodeFacts.NONE).failingLabel() != null)
            .map(LineageCardBuilder::shortName)
            .toList();
    return new LineageCardImpact()
        .withDownstream(groups)
        .withTeams(teams.size())
        .withFailingUpstream(failing);
  }

  private static List<LineageCardColumn> columns(final EntityLineage lineage, final UUID root) {
    return columnMappings(lineage, root).stream().limit(MAX_COLUMN_MAPPINGS).toList();
  }

  /** Mappings on the edges that touch the asset itself, each named as table.column. */
  private static List<LineageCardColumn> columnMappings(
      final EntityLineage lineage, final UUID root) {
    final Set<LineageCardColumn> mappings = new LinkedHashSet<>();
    final List<Edge> touching = new ArrayList<>();
    listOrEmpty(lineage.getUpstreamEdges()).stream()
        .filter(edge -> root.equals(edge.getToEntity()))
        .forEach(touching::add);
    listOrEmpty(lineage.getDownstreamEdges()).stream()
        .filter(edge -> root.equals(edge.getFromEntity()))
        .forEach(touching::add);
    for (final Edge edge : touching) {
      if (edge.getLineageDetails() == null) {
        continue;
      }
      for (final ColumnLineage column : listOrEmpty(edge.getLineageDetails().getColumnsLineage())) {
        for (final String from : listOrEmpty(column.getFromColumns())) {
          mappings.add(
              new LineageCardColumn()
                  .withFrom(columnName(from))
                  .withTo(columnName(column.getToColumn())));
        }
      }
    }
    return new ArrayList<>(mappings);
  }

  private static String columnName(final String columnFqn) {
    final String[] parts = FullyQualifiedName.split(columnFqn);
    final int from = Math.max(parts.length - 2, 0);
    final List<String> tail = new ArrayList<>();
    for (int index = from; index < parts.length; index++) {
      tail.add(FullyQualifiedName.unquoteName(parts[index]));
    }
    return String.join(".", tail);
  }

  /** A table reads as schema.table, anything else by its name. */
  static String shortName(final EntityReference node) {
    final String name;
    if (Entity.TABLE.equals(node.getType()) && !nullOrEmpty(node.getFullyQualifiedName())) {
      final String[] parts = FullyQualifiedName.split(node.getFullyQualifiedName());
      final int from = Math.max(parts.length - 2, 0);
      final List<String> tail = new ArrayList<>();
      for (int index = from; index < parts.length; index++) {
        tail.add(FullyQualifiedName.unquoteName(parts[index]));
      }
      name = String.join(".", tail);
    } else {
      name = label(node);
    }
    return name;
  }

  private static String label(final EntityReference node) {
    return nullOrEmpty(node.getDisplayName()) ? node.getName() : node.getDisplayName();
  }
}
