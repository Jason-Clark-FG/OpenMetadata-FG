/* remember: renders what the tool returned (its structuredContent): the saved fact, the assets it is
   linked to, who can read it and whether it needs review. A restatement that was merged and a
   harness with capture turned off have their own states. */
(function () {
  "use strict";
  var h = OM.h;
  function kindOf(type) { return OM.typeShort(type) || "Asset"; }

  function visibilityOf(data) { return String(data.visibility || "").toLowerCase(); }

  function statusPill(data) {
    var status = String(data.status || "").toLowerCase();
    if (status === "approved") { return { text: "Approved", tone: "ok" }; }
    if (status === "rejected") { return { text: "Rejected", tone: "err" }; }
    if (status === "in review" || status === "draft" || status === "unprocessed") { return { text: "Pending review", tone: "warn" }; }
    return visibilityOf(data) === "private" ? { text: "Private" } : { text: "Saved", tone: "ok" };
  }

  function visibility(data) {
    if (visibilityOf(data) === "public") { return "Everyone"; }
    if (visibilityOf(data) === "shared") {
      var names = (data.sharedWith || []).map(function (name) { return name + " persona"; });
      return names.length ? names.join(", ") : "The people it is shared with";
    }
    return "Only you";
  }

  function linkRow(asset) {
    return h("div", "om-row", h("span", "om-kind", kindOf(asset.type)),
      OM.link(asset.url, "om-name om-mono", asset.fqn || ""));
  }

  function savedLine(data, now) {
    if (typeof data.savedAt !== "number") { return null; }
    var minutes = Math.floor((now - data.savedAt) / 60000);
    var when = minutes < 1 ? "just now" : OM.agoLong(data.savedAt, now);
    return "Saved " + when + (data.client ? " · " + data.client : "");
  }

  function notSaved(data) {
    return OM.card({
      kind: "Memory",
      pill: { text: "Capture off", tone: "warn" },
      blocks: [h("div", "om-intro",
        h("div", "om-label", "Not saved"),
        h("div", "om-body-text", data.reason || "Memory capture is turned off for your agent harness, so nothing was saved."))]
    });
  }

  function render(data, now) {
    if (data.captured === false && !data.deduplicatedInto) { return notSaved(data); }
    var linked = data.linkedAssets || [];
    var skipped = data.skipped || [];
    var merged = Boolean(data.deduplicatedInto);
    return OM.card({
      kind: "Memory",
      pill: statusPill(data),
      blocks: [
        h("div", "om-intro",
          h("div", "om-label", merged ? "Already remembered" : "Remembered"),
          h("div", "om-body-text", data.fact || data.title || "")),
        OM.section("Linked to", linked.length || skipped.length
          ? h("div", "om-rows tight", linked.map(linkRow),
            skipped.length ? h("div", "om-meta", "Not linked, not found or not visible to you: " + skipped.join(", ")) : null)
          : null),
        OM.notices([
          merged ? { label: "Merged", tone: "brand", body: "Merged into an existing memory, so nothing new was saved." } : null,
          { label: "Visible to", body: visibility(data) },
          data.note ? { label: "Note", tone: "warn", body: data.note } : null
        ])
      ],
      footer: {
        left: [h("span", null, savedLine(data, now))],
        links: data.url ? [OM.link(data.url, null, "Open in OpenMetadata ↗")] : []
      }
    });
  }

  OM.mount(render);
}());
