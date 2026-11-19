/* knowledge_graph: renders what the tool returned (its structuredContent). find, expand and query
   show what the call served, each node with its trust at a glance; cite and trace draw the run's
   graph, cited emphasised, explored plain, offered faint, with trust notices, memories and steps. */
(function () {
  "use strict";
  var h = OM.h;
  var SVG = "http://www.w3.org/2000/svg";
  var DRAWN = 12;
  var KINDS = { table: "Table", column: "Column", glossaryTerm: "Term", metric: "Metric", dashboard: "Dash",
    pipeline: "Pipe", topic: "Topic", testCase: "Test", ontologyConcept: "Concept", ontologyProperty: "Prop",
    contextMemory: "Memory", user: "User", team: "Team", databaseSchema: "Schema", database: "DB" };

  function kindOf(type) {
    var key = String(type || "").replace(/^om:/, "");
    key = key.charAt(0).toLowerCase() + key.slice(1);
    return KINDS[key] || (key ? key.slice(0, 6) : "Node");
  }

  function trustTags(node) {
    return [
      node.failingTests ? OM.tag(node.failingTests + " failing", "err") : null,
      node.openIncidents ? OM.tag(node.openIncidents + " incident" + (node.openIncidents > 1 ? "s" : ""), "warn") : null,
      node.status && node.status !== "Active" ? OM.tag(node.status, "warn") : null,
      node.certification ? OM.tag(String(node.certification).replace(/^Certification\./, ""), "ok") : null,
      node.tier ? OM.tag(String(node.tier).replace(/^Tier\./, "")) : null
    ];
  }

  function nodeRow(node) {
    return h("div", "om-row kg-row " + (node.state || ""), h("span", "om-kind", kindOf(node.type)),
      h("span", "om-name plain om-mono", node.name || node.id), h("span", "om-spacer"), trustTags(node));
  }

  function rows(nodes) { return nodes.length ? h("div", "om-rows tight", nodes.map(nodeRow)) : null; }

  function trouble(nodes) {
    return nodes.filter(function (node) { return node.failingTests || node.openIncidents || (node.status && node.status !== "Active"); });
  }

  function pillOf(nodes, fallback) {
    var bad = trouble(nodes);
    return bad.length ? { text: bad.length + " need" + (bad.length > 1 ? "" : "s") + " attention", tone: "warn" } : fallback;
  }

  function memoryRows(memories) {
    return memories.length ? h("div", "om-rows tight", memories.map(function (memory) {
      var origin = memory.origin === "saved" ? "Saved" : memory.origin === "preference" ? "Pref" : "Note";
      return h("div", "om-row kg-memory", h("span", "om-kind", origin), h("span", "om-body-text", memory.title || memory.id),
        h("span", "om-spacer"), memory.awaitingReview ? OM.tag("awaiting review", "warn") : OM.tag(memory.stage || "Saved", "ok"));
    })) : null;
  }

  function more(count, what) { return count ? h("div", "om-meta", count + " more " + what + " not shown.") : null; }

  function byId(nodes) {
    var map = {};
    nodes.forEach(function (node) { map[node.id] = node; });
    return map;
  }

  function groupSections(data) {
    var nodes = byId(data.nodes || []);
    return (data.groups || []).map(function (group) {
      var members = group.nodes.map(function (id) { return nodes[id] || { id: id, name: id.split("/").pop() }; });
      var from = nodes[group.node] ? nodes[group.node].name : group.node.split("/").pop();
      return OM.section(from + " · " + group.relationship + " (" + group.direction + ") · " + group.total, rows(members));
    });
  }

  function compactView(data) {
    var nodes = data.nodes || [];
    var counts = data.counts || {};
    var query = data.operation === "query";
    var line = query ? data.rest : (counts.explored || 0) + " card" + (counts.explored === 1 ? "" : "s")
      + (counts.offered ? ", " + counts.offered + " listed" : "");
    return OM.card({
      kind: "Knowledge graph",
      pill: pillOf(nodes, { text: data.operation }),
      blocks: [
        h("div", "om-intro tight", h("div", "om-label", data.operation), h("div", "om-text", line)),
        query ? h("div", "om-section", h("div", "om-formula om-mono", data.query || "")) : null,
        data.groups && data.groups.length ? groupSections(data) : OM.section("Served", rows(nodes)),
        more(data.moreNodes, "nodes"),
        OM.section("Memories", memoryRows(data.memories || []))
      ],
      footer: { left: [h("span", null, "knowledge_graph · " + data.operation + " · run " + String(data.run || "").slice(0, 8))] }
    });
  }

  function svg(tag, attributes) {
    var node = document.createElementNS(SVG, tag);
    Object.keys(attributes || {}).forEach(function (name) { node.setAttribute(name, attributes[name]); });
    return node;
  }

  function label(text) { return text.length > 22 ? text.slice(0, 21) + "…" : text; }

  /** The drawn nodes on an ellipse, cited first; the rest go to a list under the drawing. */
  function graph(data) {
    var nodes = (data.nodes || []).slice(0, DRAWN);
    if (!nodes.length) { return null; }
    var width = 608, height = Math.max(180, 70 + nodes.length * 14), cx = width / 2, cy = height / 2;
    var place = {};
    nodes.forEach(function (node, index) {
      var angle = -Math.PI / 2 + index * 2 * Math.PI / nodes.length;
      place[node.id] = { x: cx + Math.cos(angle) * (width / 2 - 165), y: cy + Math.sin(angle) * (height / 2 - 28), node: node };
    });
    var drawing = svg("svg", { viewBox: "0 0 " + width + " " + height, role: "img", "aria-label": "The graph this run walked" });
    (data.edges || []).forEach(function (edge) {
      var from = place[edge.from], to = place[edge.to];
      if (!from || !to) { return; }
      var line = svg("line", { x1: from.x, y1: from.y, x2: to.x, y2: to.y, "class": "kg-edge " + edge.state });
      var title = svg("title");
      title.appendChild(document.createTextNode(edge.relationship));
      line.appendChild(title);
      drawing.appendChild(line);
    });
    nodes.forEach(function (node) {
      var at = place[node.id];
      var group = svg("g", { "class": "kg-node " + node.state + (trouble([node]).length ? " trouble" : "") });
      group.appendChild(svg("circle", { cx: at.x, cy: at.y, r: node.state === "cited" ? 8 : 6 }));
      var left = at.x < cx;
      var text = svg("text", { x: at.x + (left ? -12 : 12), y: at.y + 4, "text-anchor": left ? "end" : "start" });
      text.appendChild(document.createTextNode(label(node.name || node.id)));
      group.appendChild(text);
      drawing.appendChild(group);
    });
    return h("div", "kg-graph", drawing, h("div", "kg-legend",
      h("span", "kg-key cited", "cited"), h("span", "kg-key explored", "explored"), h("span", "kg-key offered", "offered")));
  }

  function trustNotices(data) {
    return OM.notices(trouble((data.nodes || []).filter(function (node) { return node.state === "cited"; })).map(function (node) {
      var parts = [];
      if (node.failingTests) { parts.push(node.failingTests + " failing test" + (node.failingTests > 1 ? "s" : "")); }
      if (node.openIncidents) { parts.push(node.openIncidents + " open incident" + (node.openIncidents > 1 ? "s" : "")); }
      if (node.status && node.status !== "Active") { parts.push(node.status); }
      return { label: "Trust", tone: "warn", body: (node.name || node.id) + ": " + parts.join(", ") + "." };
    }));
  }

  function runView(data) {
    var counts = data.counts || {};
    var nodes = data.nodes || [];
    var steps = data.steps || [];
    return OM.card({
      kind: "Knowledge graph",
      pill: { text: (counts.cited || 0) + " cited", tone: "brand" },
      blocks: [
        h("div", "om-intro tight", h("div", "om-label", "How I got this"),
          h("div", "om-text", (counts.calls || 0) + " calls · " + (counts.cited || 0) + " cited · " + (counts.explored || 0)
            + " explored · " + (counts.offered || 0) + " offered")),
        graph(data),
        nodes.length > DRAWN ? OM.section("Also reached", rows(nodes.slice(DRAWN))) : null,
        more(data.moreNodes, "nodes"),
        trustNotices(data),
        OM.section("Memories", memoryRows(data.memories || []))
      ],
      fold: steps.length ? {
        title: "Steps", meta: steps.length + (data.moreSteps ? " + " + data.moreSteps : ""),
        body: h("div", "om-rows tight", steps.map(function (step) {
          return h("div", "om-row", h("span", "om-kind", step.sequence + " " + step.operation), h("span", "om-meta", step.line));
        }))
      } : null,
      footer: { left: [h("span", null, "knowledge_graph · " + data.operation + " · run " + String(data.run || "").slice(0, 8))] }
    });
  }

  function render(data) {
    var wholeRun = data.operation === "cite" || data.operation === "trace" || data.operation === "delegate";
    return wholeRun ? runView(data) : compactView(data);
  }

  OM.mount(render);
}());
