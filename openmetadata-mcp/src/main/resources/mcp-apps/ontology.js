/* get_concept_relations: renders a ConceptRelations (structuredContent) as the term, the terms it
   relates to with the table that stands for each, and the join path when a target was asked for. */
(function () {
  "use strict";
  var h = OM.h;

  function tableLabel(table) {
    var parts = (table.fqn || table.name || "").split(".");
    return parts.slice(-2).join(".");
  }

  function relationRow(relation) {
    return h("div", "om-relation",
      h("span", "om-relation-predicate", relation.relation),
      OM.link(relation.term.url, "om-relation-concept", relation.term.name),
      relation.table
        ? OM.link(relation.table.url, "om-relation-table", tableLabel(relation.table))
        : h("span", "om-relation-table", "No table yet"));
  }

  function joinNotice(data) {
    var steps = data.joinPath;
    if (steps && steps.length) {
      return { label: "Join path", tone: "brand",
        body: h("div", "om-joinpath", steps.map(function (step) { return step.table + "." + step.column; }).join(" → ")) };
    }
    if (data.target) {
      return { label: "Join path", body: "No declared foreign key joins " + data.concept.name + " to " + data.target.name + "." };
    }
    return null;
  }

  function render(data) {
    var concept = data.concept || {};
    var relations = data.relations || [];
    var mapped = relations.filter(function (relation) { return relation.table; });
    var certified = mapped.length > 0 && mapped.every(function (relation) { return relation.table.certified; });
    var total = typeof data.totalRelations === "number" ? data.totalRelations : relations.length;
    var rest = total - relations.length;
    return OM.card({
      kind: "Ontology",
      pill: { text: total + (total === 1 ? " relation" : " relations"), tone: "brand" },
      blocks: [
        h("div", "om-intro",
          h("div", "om-label", ["Concept", data.glossary ? " · " + data.glossary + " glossary" : ""].join("")),
          h("div", "om-title-lg", concept.name || ""),
          h("div", "om-text", "How " + (concept.name || "this concept") + " relates to other business concepts, and which " + (certified ? "certified " : "") + "tables represent each one.")),
        relations.length
          ? h("div", "om-relations", relations.map(relationRow), rest > 0 ? h("div", "om-node-more", "+" + rest + " more") : null)
          : h("div", "om-intro", h("div", "om-text", "No related terms yet")),
        OM.notices([joinNotice(data)])
      ],
      footer: {
        left: [h("span", null, data.glossary ? data.glossary + " glossary" : "")],
        links: data.url ? [OM.link(data.url, null, "Open in OpenMetadata ↗")] : []
      }
    });
  }

  OM.mount(render);
}());
