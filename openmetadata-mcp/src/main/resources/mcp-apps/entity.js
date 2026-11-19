/* get_entity_details: renders the entity the tool returned (its structuredContent is the same map
   as its text). The server adds entityType, which picks the variant: table is the data asset card,
   metric and glossaryTerm are the concept cards, anything else a plain card, never labelled Table. */
(function () {
  "use strict";
  var h = OM.h;
  var MAX_COLUMNS = 6;
  var STALE_AFTER_MS = 48 * 3600 * 1000;

  function name(item) { return item.displayName || item.name || ""; }
  function labels(entity) { return (entity.tags || []).map(function (tag) { return tag.tagFQN || ""; }); }

  function tier(entity) {
    var found = labels(entity).filter(function (fqn) { return /^Tier\.Tier\d+$/.test(fqn); })[0];
    return found ? found.replace(/^Tier\.Tier(\d+)$/, "Tier $1") : null;
  }
  function certified(entity) {
    var value = entity.certification;
    return typeof value === "string" && /^Certification\./.test(value) && !/EXPIRED|check get_entity_details/.test(value);
  }
  function kindLabel(entity) {
    var type = entity.entityType;
    if (type === "table") { return "Data asset"; }
    return type ? type.replace(/([A-Z])/g, " $1").toLowerCase().replace(/^./, function (c) { return c.toUpperCase(); }) : "Asset";
  }
  function container(entity) {
    var fqn = entity.fullyQualifiedName || "";
    var leaf = entity.name || "";
    var parent = leaf && fqn.slice(-leaf.length - 1) === "." + leaf ? fqn.slice(0, -leaf.length - 1) : "";
    var type = entity.entityType || "asset";
    return (type.charAt(0).toUpperCase() + type.slice(1)) + (parent ? " · " + parent : "");
  }

  function statCells(entity, now) {
    var profile = entity.profile;
    var profiled = profile && typeof profile.timestamp === "number";
    var summary = entity.quality && entity.quality.summary;
    var tested = summary && typeof summary.total === "number" && summary.total > 0;
    var passing = tested ? (summary.success || 0) : 0;
    var month = entity.usageSummary && entity.usageSummary.monthlyStats;
    return [
      profiled
        ? { label: "Freshness", value: OM.ago(profile.timestamp, now), tone: now - profile.timestamp > STALE_AFTER_MS ? "warn" : "ok" }
        : { label: "Freshness", value: "Not profiled" },
      profile && typeof profile.rowCount === "number"
        ? { label: "Rows", value: OM.compact(profile.rowCount) } : { label: "Rows", value: "Not profiled" },
      tested
        ? { label: "Quality", value: passing + " / " + summary.total + " passing", tone: passing === summary.total ? "ok" : "warn" }
        : { label: "Quality", value: "No tests" },
      month && typeof month.count === "number"
        ? { label: "Usage", value: OM.compact(month.count) + " queries / 30d" } : { label: "Usage", value: "No usage data" }
    ];
  }

  function ownLabels(column) {
    // A column reports the tags it inherits from its table as Derived; they belong to the table.
    return (column.tags || []).filter(function (tag) { return tag.labelType !== "Derived"; })
      .map(function (tag) { return tag.tagFQN || ""; });
  }

  function columnRow(column) {
    var tags = ownLabels(column);
    var notes = [];
    if (column.description) { notes.push(h("span", null, column.description.split("\n")[0].slice(0, 90))); }
    if (column.constraint === "PRIMARY_KEY") { notes.push(OM.tag("Primary key", "brand")); }
    if (column.dqFailing) { notes.push(OM.tag("DQ failing", "warn")); }
    tags.forEach(function (fqn) {
      if (/^PII\./.test(fqn)) { notes.push(OM.tag("PII", "err")); }
      else if (/^Tier\./.test(fqn) || !fqn) { return; }
      else if ((column.tags || []).some(function (tag) { return tag.tagFQN === fqn && tag.source === "Glossary" && tag.labelType !== "Derived"; })) {
        notes.push(OM.tag(fqn.split(".").pop(), "ok"));
      }
    });
    return h("div", "om-column", h("span", "om-column-name", column.name || ""),
      h("span", "om-column-type", String(column.dataType || "").toLowerCase()), h("div", "om-column-notes", notes));
  }

  function keyColumns(entity) {
    var columns = entity.columns || [];
    var notable = columns.filter(function (column) { return column.constraint === "PRIMARY_KEY" || column.dqFailing || ownLabels(column).length; });
    var rest = columns.filter(function (column) { return notable.indexOf(column) < 0; });
    return notable.concat(rest).slice(0, MAX_COLUMNS).map(columnRow);
  }

  function modelNotes(entity, owners) {
    var model = entity.dataModel;
    var notes = [owners.length ? "Owner " + owners.join(", ") : null,
      model ? (/^dbt$/i.test(model.modelType || "") ? "dbt model" : "data model") : null].filter(Boolean);
    return notes.length ? [h("span", null, notes.join(" · "))] : [];
  }

  function generic(entity, now) {
    var owners = (entity.owners || []).map(name).filter(Boolean);
    return OM.card({
      kind: kindLabel(entity),
      pill: null,
      blocks: [h("div", "om-intro",
        h("div", "om-label", container(entity)),
        h("div", "om-title", name(entity)),
        entity.description ? h("div", "om-text", entity.description.split("\n")[0].slice(0, 400)) : null)],
      footer: {
        left: [h("span", null, owners.length ? "Owner " + owners.join(", ") : "No owner")],
        links: entity.openMetadataUrl ? [OM.link(entity.openMetadataUrl, null, "Open in OpenMetadata ↗")] : []
      }
    });
  }

  function renderOne(entity, now) {
    var type = entity.entityType;
    if (entity.kind === "metric") { return OMConcept.metric(entity, now); }
    if (entity.kind === "glossaryTerm") { return OMConcept.term(entity, now); }
    if (type === "metric") { return OMConcept.metric(OMConcept.fromEntity(entity), now); }
    if (type === "glossaryTerm") { return OMConcept.term(OMConcept.fromEntity(entity), now); }
    if (type !== "table") { return generic(entity, now); }
    var owners = (entity.owners || []).map(name).filter(Boolean);
    var tierText = tier(entity);
    var isCertified = certified(entity);
    var pillText = [tierText, isCertified ? "Certified" : null].filter(Boolean).join(" · ");
    var columns = keyColumns(entity);
    var sql = entity.dataModel && entity.dataModel.sql;
    var top = entity.dataModel && entity.dataModel.topQuery;
    return OM.card({
      kind: kindLabel(entity),
      pill: pillText ? { text: pillText, tone: isCertified ? "ok" : "" } : null,
      blocks: [
        h("div", "om-intro",
          h("div", "om-label", container(entity)),
          h("div", "om-title om-mono", entity.name || ""),
          entity.description ? h("div", "om-body-text", entity.description.split("\n")[0].slice(0, 400)) : null),
        OM.notices([entity.deprecated ? { label: "Deprecated", tone: "warn", body: "This asset is deprecated. Prefer a maintained alternative." } : null]),
        OM.stats(statCells(entity, now)),
        OM.section("Key columns", columns.length ? h("div", "om-columns", columns) : null)
      ],
      fold: top ? { title: "Most-used query", meta: "run " + top.runs + " times in " + top.days + " days", body: h("pre", "om-code", h("code", null, top.sql)) }
        : sql ? { title: "SQL definition", meta: "dbt model", body: h("pre", "om-code", h("code", null, sql.trim())) } : null,
      footer: {
        left: modelNotes(entity, owners),
        links: entity.openMetadataUrl ? [OM.link(entity.openMetadataUrl, null, "Open in OpenMetadata ↗")] : []
      }
    });
  }

  function render(data, now) {
    // find_context with no single clear concept: a list of hits, which has no card.
    if (Array.isArray(data.hits)) { return h("div", null); }
    var entities = Array.isArray(data.entities) ? data.entities.filter(function (entity) { return entity && entity.name; }) : [data];
    if (!entities.length || !entities[0].name) { return h("div", "om-text", "No asset to show"); }
    var wrapper = h("div", null);
    wrapper.style.display = "flex";
    wrapper.style.flexDirection = "column";
    wrapper.style.gap = "12px";
    entities.forEach(function (entity) { wrapper.appendChild(renderOne(entity, now)); });
    return wrapper;
  }

  OM.mount(render);
}());
