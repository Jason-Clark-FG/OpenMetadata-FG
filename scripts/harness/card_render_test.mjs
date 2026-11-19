#!/usr/bin/env node
// Renders the lineage, concept and ontology cards from the sample content of card_fixtures.json
// in jsdom and checks what a reader sees: the text of each block, that every link goes through
// ui/open-link, and that a malicious name stays text. Run: node scripts/harness/card_render_test.mjs
// (jsdom is resolved like playwright in card_parity.mjs: --jsdom DIR or JSDOM_HOME).
import { createRequire } from "node:module";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const APPS = path.join(root, "openmetadata-mcp/src/main/resources/mcp-apps");
const homes = [process.env.JSDOM_HOME, process.env.PLAYWRIGHT_HOME, path.join(root, "openmetadata-ui/src/main/resources/ui"), root].filter(Boolean);
const { JSDOM } = createRequire(import.meta.url)(createRequire(import.meta.url).resolve("jsdom", { paths: homes }));
const fixtures = JSON.parse(fs.readFileSync(path.join(here, "card_fixtures.json"), "utf8"));

const inline = (page) => page
  .replace(/<link rel="stylesheet" href="([^"]+)">/g, (_, file) => `<style>${fs.readFileSync(path.join(APPS, file), "utf8")}</style>`)
  .replace(/<script src="([^"]+)"><\/script>/g, (_, file) => `<script>${fs.readFileSync(path.join(APPS, file), "utf8")}</script>`);

function open(view, data) {
  const dom = new JSDOM(inline(fs.readFileSync(path.join(APPS, view), "utf8")), { runScripts: "dangerously" });
  const opened = [];
  dom.window.parent.postMessage = () => {};
  dom.window.__renderCard(data, 1800000000000);
  const text = (selector) => [...dom.window.document.querySelectorAll(selector)].map((node) => node.textContent.replace(/\s+/g, " ").trim());
  const click = (anchor) => {
    const event = new dom.window.MouseEvent("click", { bubbles: true, cancelable: true });
    anchor.dispatchEvent(event);
    return event.defaultPrevented;
  };
  return { document: dom.window.document, text, click, opened };
}

let passed = 0;
function check(name, body) {
  body();
  passed++;
  console.log(`ok  ${name}`);
}

check("lineage card lays out upstream, this asset and downstream with the impact line", () => {
  const card = open("lineage-v1.html", fixtures.lineage);
  assert.deepEqual(card.text(".om-lineage-heading"), ["Upstream", "This asset", "Downstream"]);
  assert.deepEqual(card.text(".om-lineage-col:nth-child(1) .om-node-name"), ["raw.orders", "raw.refunds", "stg_fx_rates"]);
  assert.deepEqual(card.text(".om-node.root .om-node-kind"), ["Table · Tier 1", "dbt · hourly"]);
  assert.deepEqual(card.text(".om-lineage-col:nth-child(5) .om-node-kind"), ["Dashboard", "Dashboard", "ML model"]);
  assert.deepEqual(card.text(".om-node.warn .om-node-flag"), ["Freshness failing"]);
  assert.equal(card.text(".om-pill")[0], "1 upstream issue");
  assert.equal(card.text(".om-notice-body")[0],
    "raw.refunds has a failing freshness test. Changing fct_revenue affects 2 dashboards and 1 ML model, owned by 3 teams.");
  assert.match(card.text(".om-intro .om-text")[0], /2 hops up, 1 hop down/);
  assert.deepEqual(card.text(".om-footer span:first-child"), ["get_entity_lineage · depth 2"]);
  assert.deepEqual(card.text(".om-colmap"), ["raw.orders.amount→fct_revenue.net_amount"]);
});

check("every lineage node opens its page through ui/open-link", () => {
  const card = open("lineage-v1.html", fixtures.lineage);
  const anchors = [...card.document.querySelectorAll("a.om-node")];
  assert.equal(anchors.length, 6);
  assert.equal(anchors[0].href, "https://acme.getcollate.io/table/raw.orders");
  assert.equal(card.click(anchors[0]), true);
});

check("a node named with markup stays text", () => {
  const hostile = structuredClone(fixtures.lineage);
  hostile.downstream[0].name = "<img src=x onerror=alert(1)>";
  const card = open("lineage-v1.html", hostile);
  assert.equal(card.document.querySelectorAll("img").length, 0);
  assert.ok(card.text(".om-lineage-col:nth-child(5) .om-node-name")[0].includes("<img src=x"));
});

check("a lineage with nothing around the asset still renders the asset", () => {
  const card = open("lineage-v1.html", { root: fixtures.lineage.root });
  assert.deepEqual(card.text(".om-node.root .om-node-name"), ["fct_revenue"]);
  assert.equal(card.text(".om-notice").length, 0);
});

check("ontology card lists each relation with its table, and the join path", () => {
  const card = open("ontology-v1.html", fixtures.ontology);
  assert.equal(card.text(".om-pill")[0], "5 relations");
  assert.deepEqual(card.text(".om-relation-predicate"), ["is a", "places", "has", "belongs to", "generates"]);
  assert.match(card.text(".om-intro .om-text")[0], /which certified tables represent each one\./);
  assert.deepEqual(card.text(".om-relation-concept"), ["Party", "Order", "Subscription", "Account", "Revenue"]);
  assert.deepEqual(card.text(".om-relation-table"), ["dim_party", "analytics.fct_orders", "analytics.fct_subscriptions", "analytics.dim_account", "analytics.fct_revenue"]);
  assert.equal(card.text(".om-joinpath")[0], "dim_customer.customer_id → fct_orders.customer_id → fct_revenue.order_id");
  const concept = card.document.querySelector("a.om-relation-concept");
  assert.equal(card.click(concept), true);
});

check("ontology card says when no key joins the two terms", () => {
  const card = open("ontology-v1.html", { ...fixtures.ontology, joinPath: [] });
  assert.equal(card.text(".om-notice-body")[0], "No declared foreign key joins Customer to Revenue.");
});

check("ontology card without a target shows no join row, and a term with no table says so", () => {
  const data = structuredClone(fixtures.ontology);
  delete data.target;
  data.joinPath = [];
  delete data.relations[0].table;
  const card = open("ontology-v1.html", data);
  assert.equal(card.text(".om-notice").length, 0);
  assert.equal(card.text(".om-relation-table")[0], "No table yet");
});

check("concept card shows the term, its not-to-be-confused-with notice and what it is applied to", () => {
  const card = open("entity-v1.html", fixtures.term);
  assert.equal(card.text(".om-pill")[0], "Approved");
  assert.equal(card.text(".om-title-lg")[0], "Active customer");
  assert.deepEqual(card.text(".om-chip"), ["Active account", "Paying customer"]);
  assert.match(card.text(".om-notice-body")[0], /Finance › Customer/);
  assert.equal(card.text(".om-section-title")[0], "Applied to 42 assets");
});

check("a find_context result with no clear concept draws no card", () => {
  const card = open("entity-v1.html", { hits: [{ entityType: "glossaryTerm", name: "Customer", fqn: "G.Customer" }] });
  assert.equal(card.document.querySelectorAll(".om-card").length, 0);
});

check("memory card shows the fact, what it is linked to, who reads it, and links to the memory", () => {
  const card = open("memory-v1.html", fixtures.memory);
  assert.equal(card.text(".om-pill")[0], "Approved");
  assert.equal(card.text(".om-body-text")[0], fixtures.memory.fact);
  assert.deepEqual(card.text(".om-kind"), ["Metric", "Table"]);
  assert.deepEqual(card.text(".om-row .om-name"), ["Finance.ARR", "analytics.finance.public.arr_daily"]);
  assert.deepEqual(card.text(".om-notice-label"), ["Visible to"]);
  assert.deepEqual(card.text(".om-notice-body"), ["FinanceAnalyst persona"]);
  assert.equal(card.text(".om-footer")[0], "Saved 2 minutes ago · Claude DesktopOpen in OpenMetadata ↗");
  const open_ = card.document.querySelector(".om-footer a");
  assert.equal(open_.href, fixtures.memory.url);
  assert.equal(card.click(open_), true);
});

check("memory card says when a restatement was merged, and who sees a private memory awaiting review", () => {
  const data = { ...structuredClone(fixtures.memory), captured: false, deduplicatedInto: fixtures.memory.id,
    status: "In Review", visibility: "Private", sharedWith: [], note: "No persona applies to you." };
  const card = open("memory-v1.html", data);
  assert.equal(card.text(".om-pill")[0], "Pending review");
  assert.equal(card.text(".om-label")[0], "Already remembered");
  assert.deepEqual(card.text(".om-notice-label"), ["Merged", "Visible to", "Note"]);
  assert.deepEqual(card.text(".om-notice-body"), [
    "Merged into an existing memory, so nothing new was saved.", "Only you", "No persona applies to you."
  ]);
});

check("memory card with capture off says nothing was saved and offers no link", () => {
  const card = open("memory-v1.html", { captured: false, reason: "Memory capture is turned off for your agent harness, so nothing was saved." });
  assert.equal(card.text(".om-pill")[0], "Capture off");
  assert.equal(card.text(".om-label")[0], "Not saved");
  assert.match(card.text(".om-body-text")[0], /nothing was saved/);
  assert.equal(card.document.querySelectorAll("a").length, 0);
});

check("a memory fact or asset named with markup stays text", () => {
  const data = structuredClone(fixtures.memory);
  data.fact = "<img src=x onerror=alert(1)>";
  data.linkedAssets[0].fqn = "<b>x</b>";
  const card = open("memory-v1.html", data);
  assert.equal(card.document.querySelectorAll("img, b").length, 0);
  assert.equal(card.text(".om-body-text")[0], "<img src=x onerror=alert(1)>");
});

console.log(`${passed} checks passed`);
