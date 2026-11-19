/* get_entity_lineage: renders a LineageCard (structuredContent) as upstream, this asset and
   downstream columns, with an impact line and a fold for column lineage. */
(function () {
  "use strict";
  var h = OM.h;
  var MAX_PER_SIDE = 5;
  var MAX_COLUMN_MAPPINGS = 12;
  var KIND_LABELS = { mlmodel: "ML model", storedprocedure: "Stored procedure", searchindex: "Search index",
    databaseschema: "Schema", apiendpoint: "API endpoint", dashboarddatamodel: "Dashboard data model" };

  function kindLabel(type) {
    var key = String(type || "").toLowerCase();
    if (KIND_LABELS[key]) { return KIND_LABELS[key]; }
    return key ? key.charAt(0).toUpperCase() + key.slice(1) : "Asset";
  }
  function tierLabel(tier) { return tier ? String(tier).replace(/^Tier(\d+)$/, "Tier $1") : null; }
  function plural(count, word) { return count + " " + word + (count === 1 ? "" : "s"); }

  function shortName(node) {
    if (node.entityType === "table" && node.fqn) {
      return node.fqn.split(".").slice(-2).join(".");
    }
    return node.displayName || node.name || node.fqn || "";
  }

  function nodeCard(node, isRoot) {
    var kind = [kindLabel(node.entityType), tierLabel(node.tier)].filter(Boolean).join(" · ");
    var flagged = !isRoot && node.hasFailingCheck;
    var body = [
      h("span", "om-node-kind", kind),
      h("span", "om-node-name" + (node.entityType === "table" ? " mono" : ""), shortName(node)),
      flagged ? h("span", "om-node-flag", node.failingLabel || "Check failing") : null,
      isRoot && node.detail ? h("span", "om-node-kind", node.detail) : null
    ];
    var className = "om-node" + (isRoot ? " root" : "") + (flagged ? " warn" : "");
    return isRoot ? h.apply(null, ["div", className].concat(body)) : OM.link.apply(null, [node.url, className].concat(body));
  }

  function column(title, nodes, root) {
    var shown = (nodes || []).slice(0, MAX_PER_SIDE);
    var rest = (nodes || []).length - shown.length;
    return h("div", "om-lineage-col", h("div", "om-lineage-heading", title),
      root ? nodeCard(root, true) : shown.map(function (node) { return nodeCard(node, false); }),
      rest > 0 ? h("div", "om-node-more", "+" + rest + " more") : null);
  }

  function impactText(card) {
    var impact = card.impact || {};
    var parts = [];
    (impact.downstream || []).forEach(function (group) {
      parts.push(plural(group.count, kindLabel(group.entityType).toLowerCase().replace(/^ml model$/, "ML model")));
    });
    var failing = (impact.failingUpstream || []);
    var lead = failing.length
      ? failing.join(", ") + (failing.length === 1 ? " has" : " have") + " a failing check. "
      : "";
    var effects = parts.length ? "Changing " + (card.root && card.root.name || "this asset") + " affects " + parts.join(" and ")
      + (impact.teams ? ", owned by " + plural(impact.teams, "team") : "") + "." : "";
    return lead + effects;
  }

  function pill(card) {
    var issues = ((card.impact || {}).failingUpstream || []).length;
    return issues ? { text: issues + " upstream issue" + (issues === 1 ? "" : "s"), tone: "warn" } : null;
  }

  function columnFold(card) {
    var mappings = (card.columns || []).slice(0, MAX_COLUMN_MAPPINGS);
    if (!mappings.length) { return null; }
    var rows = mappings.map(function (mapping) {
      return h("div", "om-colmap", h("span", null, mapping.from), h("span", "om-arrow", "→"), h("span", null, mapping.to));
    });
    var more = (card.columnsTotal || mappings.length) - mappings.length;
    var names = mappings.map(function (mapping) { return mapping.to.split(".").pop(); })
      .filter(function (name, index, all) { return all.indexOf(name) === index; }).slice(0, 3).join(", ");
    return { title: "Column lineage", meta: names, body: [rows, more > 0 ? h("div", "om-node-more", "+" + more + " more") : null] };
  }

  function render(card) {
    var root = card.root || {};
    var hops = card.upstreamHops || card.downstreamHops
      ? [plural(card.upstreamHops || 0, "hop") + " up", plural(card.downstreamHops || 0, "hop") + " down"].join(", ") : null;
    var impact = impactText(card);
    var hidden = card.hiddenNodes ? "Some assets are not shown because you cannot view them." : null;
    return OM.card({
      kind: "Lineage",
      pill: pill(card),
      blocks: [
        h("div", "om-intro",
          h("div", "om-title om-mono", root.fqn ? root.fqn.split(".").slice(-2).join(".") : root.name || ""),
          h("div", "om-text", ["Where it comes from and what depends on it", hops ? " · " + hops : ""].join(""))),
        h("div", "om-lineage",
          column("Upstream", card.upstream),
          h("div", "om-arrow", "→"),
          column("This asset", null, root),
          h("div", "om-arrow", "→"),
          column("Downstream", card.downstream)),
        OM.notices([impact ? { label: "Impact", tone: (card.impact && (card.impact.failingUpstream || []).length) ? "warn" : null, body: impact } : null,
          hidden ? { label: "Note", body: hidden } : null])
      ],
      fold: columnFold(card),
      footer: {
        left: [h("span", null, "get_entity_lineage · depth " + Math.max(card.upstreamHops || 0, card.downstreamHops || 0))],
        links: root.url ? [OM.link(root.url, null, "Open in OpenMetadata ↗")] : []
      }
    });
  }

  OM.mount(render);
}());
