/* Glossary term and metric variants of the entity card. Both accept one normalized concept: either
   the typed ConceptCard a tool returns, or a raw entity from get_entity_details run through
   fromEntity. Sections with no data are left out; the applied-to section says so when it is empty. */
var OMConcept = (function () {
  "use strict";
  var h = OM.h;
  var MAX_APPLIED = 6;

  function label(item) { return item.displayName || item.name || item.fqn || ""; }
  function names(items) { return (items || []).map(label).filter(Boolean); }

  function relationNote(relation) {
    return /confus|disambig|distinct/i.test(relation.relationType || "");
  }

  function fromEntity(entity) {
    var isMetric = entity.entityType === "metric";
    var related = (entity.relatedTerms || []).map(function (relation) {
      var term = relation.term || relation;
      return { relationType: relation.relationType, name: label(term), fqn: term.fullyQualifiedName || term.fqn, url: term.url };
    });
    var expression = entity.metricExpression || {};
    var unit = entity.unitOfMeasurement === "OTHER" ? entity.customUnitOfMeasurement : entity.unitOfMeasurement;
    return {
      kind: isMetric ? "metric" : "glossaryTerm",
      name: entity.name, displayName: entity.displayName, fqn: entity.fullyQualifiedName,
      description: entity.description, url: entity.openMetadataUrl,
      container: isMetric
        ? { label: "Metric", name: label((entity.domains || [])[0] || {}) }
        : { label: "Glossary", name: label(entity.glossary || {}), url: entity.glossary && entity.glossary.url },
      status: entity.entityStatus,
      certified: typeof entity.certification === "string" && /^Certification\./.test(entity.certification),
      owners: names(entity.owners), updatedAt: entity.updatedAt,
      synonyms: entity.synonyms || [],
      confusedWith: related.filter(relationNote),
      related: related.filter(function (relation) { return !relationNote(relation); }),
      metric: isMetric ? {
        expression: expression.code, language: expression.language, granularity: entity.granularity,
        unit: unit ? String(unit).toLowerCase().replace(/^./, function (c) { return c.toUpperCase(); }) : null,
        metricType: entity.metricType, relatedMetrics: names(entity.relatedMetrics)
      } : null
    };
  }

  function footer(concept, now) {
    var left = [concept.owners && concept.owners.length ? "Owner " + concept.owners.join(", ") : "No owner"];
    if (concept.footerNote) { left.push(concept.footerNote); }
    else if (typeof concept.updatedAt === "number") { left.push("reviewed " + OM.agoLong(concept.updatedAt, now)); }
    return {
      left: [h("span", null, left.join(" · "))],
      links: concept.url ? [OM.link(concept.url, null, "Open in OpenMetadata ↗")] : []
    };
  }

  function pill(concept) {
    if (concept.kind === "metric" && concept.certified) { return { text: "Certified", tone: "ok" }; }
    if (!concept.status) { return null; }
    return { text: concept.status, tone: /^approved$/i.test(concept.status) ? "ok" : /draft|rejected|deprecated/i.test(concept.status) ? "warn" : "" };
  }

  function container(concept) {
    var place = concept.container || {};
    var name = place.name ? (place.url ? OM.link(place.url, null, place.name) : place.name) : null;
    return h("div", "om-label", [place.label || (concept.kind === "metric" ? "Metric" : "Glossary"), name ? " · " : "", name]);
  }

  function confused(concept) {
    var rows = (concept.confusedWith || []).map(function (other) {
      return { label: "Not to be confused with", wide: true, tone: "warn",
        body: [OM.link(other.url, null, other.name), other.note ? " " + other.note : ""] };
    });
    return rows.length ? OM.notices(rows) : null;
  }

  function appliedRow(item) {
    return h("div", "om-link-row", h("span", "om-kind", item.kind),
      OM.link(item.url, "om-name", item.name), item.detail ? h("span", "om-meta", item.detail) : null);
  }

  function applied(concept) {
    var data = concept.appliedTo;
    if (!data) { return null; }
    var total = typeof data.total === "number" ? data.total : (data.items || []).length;
    var items = (data.items || []).slice(0, MAX_APPLIED);
    return h("div", "om-section",
      h("div", "om-section-title", total === 0 ? "Applied to 0 assets" : "Applied to " + total + (total === 1 ? " asset" : " assets")),
      total === 0 ? h("div", "om-text", "Not applied to any asset yet") : h("div", "om-rows", items.map(appliedRow)),
      null);
  }

  function history(values) {
    if (!values || !values.length) { return null; }
    return h("div", "om-spark", values.map(function (value, index) {
      var bar = h("div", index === values.length - 1 ? "om-spark-bar last" : "om-spark-bar");
      bar.style.height = Math.max(0, Math.min(100, value)) + "%";
      return bar;
    }));
  }

  function term(concept, now) {
    return OM.card({
      kind: "Glossary term", pill: pill(concept),
      blocks: [
        h("div", "om-intro lg", container(concept), h("div", "om-title-lg", label(concept)),
          concept.description ? h("div", "om-body-text", concept.description) : h("div", "om-text", "No definition yet"),
          concept.synonyms && concept.synonyms.length
            ? h("div", "om-chips", "Synonyms", concept.synonyms.map(function (synonym) { return h("span", "om-chip", synonym); })) : null),
        confused(concept),
        applied(concept)
      ],
      footer: footer(concept, now)
    });
  }

  function metric(concept, now) {
    var details = concept.metric || {};
    var cells = [
      { label: "Granularity", value: details.granularity ? details.granularity.charAt(0) + details.granularity.slice(1).toLowerCase() : "Not set" },
      { label: "Unit", value: details.unit || "Not set" },
      details.target ? { label: "Target", value: details.target } : null,
      { label: "Related", value: details.relatedMetrics && details.relatedMetrics.length ? details.relatedMetrics.join(", ") : "None" }
    ];
    return OM.card({
      kind: "Metric", pill: pill(concept),
      blocks: [
        h("div", "om-metric-head",
          h("div", "om-metric-title", container(concept), h("div", "om-title-lg", label(concept)),
            concept.value ? OM.valueRow(concept.value, concept.change, concept.changeDirection) : null,
            concept.period ? h("div", "om-label", concept.period) : null),
          history(concept.history)),
        h("div", "om-metric-body",
          concept.description ? h("div", "om-body-text", concept.description) : h("div", "om-text", "No definition yet"),
          details.expression ? h("div", "om-formula", details.expression) : null),
        OM.stats(cells, true),
        applied(concept)
      ],
      fold: details.sql ? { title: "SQL definition", meta: details.source ? "from " + details.source : null, body: h("pre", "om-code", h("code", null, details.sql)) } : null,
      footer: footer(concept, now)
    });
  }

  return { fromEntity: fromEntity, term: term, metric: metric };
}());
