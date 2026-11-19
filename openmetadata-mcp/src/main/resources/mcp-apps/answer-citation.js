/* finish_answer: renders an AnswerCitation (structuredContent) in the shared card shell. Sections
   with no data are left out. Extension point for the other variants: copy this file's shape, a
   function from the tool's structuredContent to OM.card(...), and register it with OM.mount. */
(function () {
  "use strict";
  var h = OM.h;

  function owners(names) {
    return names && names.length ? (names.length === 1 ? "owner " : "owners ") + names.join(", ") : "no owner";
  }
  function tier(value) { return String(value).replace(/^Tier(\d+)$/, "Tier $1"); }
  function join(parts) { return parts.filter(Boolean).join(" · "); }

  function windowLine(window_) {
    var dates = window_.start && window_.end
      ? (window_.start === window_.end ? window_.start : window_.start + " to " + window_.end)
      : "no usable dates";
    if (window_.resolvedBy !== "server") {
      return [dates, " ", OM.tag("dates from the agent, not checked against the calendar", "warn")];
    }
    var calendar = [window_.timeZone];
    if (window_.fiscalYearStart && window_.fiscalYearStart !== "01-01") {
      calendar.push("fiscal year starts " + window_.fiscalYearStart);
    }
    return [(window_.label ? window_.label + ", " : "") + dates + " (" + calendar.filter(Boolean).join(", ") + ")"];
  }

  function conceptRow(concept) {
    var kind = concept.entityType === "metric" ? "Metric" : "Term";
    if (concept.unresolved === "notVisible") {
      return h("div", "om-row", h("span", "om-kind", kind), h("span", "om-name plain", "Not visible to you"),
        h("span", "om-meta", "“" + concept.phrase + "”"));
    }
    if (concept.unresolved === "notFound") {
      return h("div", "om-row", h("span", "om-kind", kind), h("span", "om-name plain om-mono", concept.fqn),
        h("span", "om-meta", "Not found in OpenMetadata"));
    }
    return h("div", "om-row", h("span", "om-kind", kind),
      OM.link(concept.url, "om-name", concept.displayName || concept.fqn),
      h("span", "om-meta", join([concept.certified ? "Certified" : (concept.status || "").toLowerCase(),
        owners(concept.owners), typeof concept.version === "number" ? "v" + concept.version : null])));
  }

  function freshnessText(table, now) {
    var freshness = table.freshness;
    if (!freshness || typeof freshness.at !== "number") { return "freshness unknown"; }
    return (freshness.source === "test" ? "tested " : "profiled ") + OM.ago(freshness.at, now) +
      (freshness.stale ? " (stale)" : "");
  }

  function tableRow(table, now) {
    if (table.unresolved === "notVisible") {
      return h("div", "om-row", h("span", "om-kind", "Table"), h("span", "om-name plain", "Not visible to you"));
    }
    if (table.unresolved === "notFound") {
      return h("div", "om-row", h("span", "om-kind", "Table"), h("span", "om-name plain om-mono", table.fqn),
        h("span", "om-meta", "Not found in OpenMetadata"));
    }
    return h("div", "om-row", h("span", "om-kind", "Table"),
      OM.link(table.url, "om-name om-mono", table.fqn),
      h("span", "om-meta", join([table.tier ? tier(table.tier) : null, table.certified ? "certified" : "not certified",
        freshnessText(table, now)])));
  }

  function noticeRows(citation) {
    var rows = [];
    (citation.tables || []).forEach(function (table) {
      if (table.unresolved) { return; }
      (table.openIncidents || []).forEach(function (incident) {
        rows.push({ label: "Incident", tone: "warn", body: [
          h("span", "om-mono", table.fqn), " has an open incident",
          [incident.status, incident.severity].filter(Boolean).length
            ? " (" + [incident.status, incident.severity].filter(Boolean).join(", ") + ")" : "",
          incident.testCase ? [": ", OM.link(incident.url, null, incident.testCase)] : "",
          " ", OM.link(incident.url, null, incident.id)] });
      });
      if (table.deprecated) {
        rows.push({ label: "Deprecated", tone: "warn", body: [h("span", "om-mono", table.fqn), " is deprecated",
          table.replacedBy ? [", replaced by ", h("span", "om-mono", table.replacedBy)] : "", "."] });
      }
      if (table.freshness && table.freshness.stale) {
        rows.push({ label: "Stale", tone: "warn", body: [h("span", "om-mono", table.fqn), " is out of date: ",
          freshnessText(table, Date.now()).replace(" (stale)", ""), "."] });
      }
    });
    (citation.warnings || []).forEach(function (warning) { rows.push({ label: "Note", tone: "warn", body: warning }); });
    return rows;
  }

  function qualityRow(citation) {
    var checked = citation.trustChecked || {};
    var tables = (citation.tables || []).filter(function (table) { return !table.unresolved; });
    if (!tables.length || checked.failingTests === false) { return null; }
    var failing = [];
    tables.forEach(function (table) {
      (table.failingTests || []).forEach(function (test) {
        failing.push([failing.length ? ", " : "", OM.link(test.url, null, test.name), " on ", h("span", "om-mono", table.fqn)]);
      });
    });
    return { label: "Quality", body: failing.length ? ["Failing: ", failing] : "No failing tests on the tables used." };
  }

  function pill(citation) {
    var sources = (citation.concepts || []).concat(citation.tables || []);
    if (!sources.length) { return null; }
    var unresolved = sources.some(function (source) { return source.unresolved; });
    var uncertified = sources.some(function (source) { return !source.unresolved && !source.certified; });
    if (unresolved) { return { text: "Source not found or not visible", tone: "warn" }; }
    return uncertified ? { text: "Some sources not certified", tone: "warn" } : { text: "Certified sources", tone: "ok" };
  }

  function executionMeta(citation) {
    var execution = citation.execution || {};
    if (execution.executor === "none") {
      return "Not run: " + (execution.reason || "the query was not run in this chat").replace(/\.+$/, "") + ".";
    }
    var rows = typeof execution.rowCount === "number" ? " · " + execution.rowCount + (execution.rowCount === 1 ? " row" : " rows") : "";
    return "Ran through your " + (execution.connector || "warehouse") + " connector" + rows + ". Self-reported: OpenMetadata did not run it.";
  }

  function firstUrl(citation) {
    var all = (citation.tables || []).concat(citation.concepts || []);
    for (var i = 0; i < all.length; i++) {
      if (!all[i].unresolved && all[i].url) { return all[i].url; }
    }
    return null;
  }

  function render(citation, now) {
    var tables = (citation.tables || []).filter(function (table) { return !table.unresolved; });
    var ownerNames = tables.length ? tables[0].owners : [];
    var notices = noticeRows(citation);
    var quality = qualityRow(citation);
    var sources = (citation.concepts || []).map(conceptRow).concat((citation.tables || []).map(function (table) { return tableRow(table, now); }));
    var notRun = citation.execution && citation.execution.executor === "none";
    var showSql = citation.sql && (notRun || citation.showSql !== false);
    var open = citation.traceUrl || firstUrl(citation);
    var result = citation.result;
    return OM.card({
      kind: citation.harnessName ? citation.harnessName + " harness" : "Answer",
      pill: pill(citation),
      blocks: [
        result ? resultIntro(citation, result) : h("div", "om-intro",
          citation.question ? h("div", "om-label", citation.question) : null,
          h("div", "om-title", citation.headline || citation.question || ""),
          citation.timeWindow ? h("div", "om-text", windowLine(citation.timeWindow)) : null),
        result ? OM.bars(result.breakdown) : null,
        OM.notices(notices.concat([quality]).filter(Boolean).map(function (row) {
          return row.tone ? row : { label: row.label, body: row.body };
        })),
        OM.section("Sources", sources.length ? h("div", "om-rows", sources) : null)
      ],
      fold: { title: showSql ? "SQL" : "Execution", meta: executionMeta(citation),
        body: showSql ? h("pre", "om-code", h("code", null, citation.sql.trim())) : null },
      footer: {
        left: footerLeft(citation, ownerNames),
        links: open ? [OM.link(open, null, "Open in OpenMetadata ↗")] : []
      }
    });
  }

  function resultIntro(citation, result) {
    return h("div", "om-intro",
      h("div", "om-label", result.label || citation.question || "", " ", OM.tag("self-reported")),
      OM.valueRow(result.value, result.change, result.changeDirection),
      citation.headline ? h("div", "om-text", citation.headline) : null,
      result.note ? h("div", "om-text", result.note) : null,
      citation.timeWindow ? h("div", "om-text", windowLine(citation.timeWindow)) : null);
  }

  function footerLeft(citation, ownerNames) {
    var parts = [];
    if (typeof citation.answeredAt === "number") { parts.push("Answered " + OM.clock(citation.answeredAt)); }
    if (typeof citation.harnessVersion === "number") { parts.push("harness v" + citation.harnessVersion.toFixed(1)); }
    if (parts.length) { return [h("span", null, parts.join(" · "))]; }
    return ownerNames && ownerNames.length ? [h("span", null, "Owner " + ownerNames.join(", "))] : [];
  }

  OM.mount(render);
}());
