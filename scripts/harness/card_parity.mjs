#!/usr/bin/env node
// Pixel parity of the MCP Apps cards against the design prototype ("Agent Context Cards 2").
//
//   node scripts/harness/card_parity.mjs [--prototype http://127.0.0.1:8794/cards.html]
//        [--out DIR] [--threshold 0.5] [--only 2b,2c] [--playwright PATH]
//
// For each variant it screenshots the prototype's card element and ours, rendered from the same
// sample content (scripts/harness/card_fixtures.json) at the prototype's card width, theme and
// deviceScaleFactor 2, and diffs them. Our cards are served from this checkout by a throwaway
// static server, and the fonts come from the same WOFF2 files the server's /mcp-assets/fonts route
// serves (the prototype uses the Google Fonts files of the same version). Exits 1 when a card exceeds the threshold, or when a card
// overflows horizontally at 360 px or 1280 px. Not part of CI: it needs a running prototype.
import { createRequire } from "node:module";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, arg, index, all) => (arg.startsWith("--") ? [...pairs, [arg.slice(2), all[index + 1]]] : pairs), []),
);
const prototypeUrl = args.prototype ?? "http://127.0.0.1:8794/cards.html";
const outDir = args.out ?? path.join(root, ".audit/card-parity");
const threshold = Number(args.threshold ?? 0.5);
const only = args.only ? args.only.split(",") : null;
const APPS = "openmetadata-mcp/src/main/resources/mcp-apps";
const NOW = 1800000000000;

// Directories whose node_modules may hold playwright; --playwright adds one.
const candidates = [args.playwright, path.join(root, "openmetadata-ui/src/main/resources/ui"), root, process.env.PLAYWRIGHT_HOME].filter(Boolean);
const { chromium } = createRequire(import.meta.url)(createRequire(import.meta.url).resolve("playwright", { paths: candidates }));

const fixtures = JSON.parse(fs.readFileSync(path.join(here, "card_fixtures.json"), "utf8"));
const VARIANTS = {
  "1a": { page: "answer-citation-v1.html", fixture: "answer", theme: "light", width: 640, protoSelector: '#\\31 a .sc-host > div' },
  "1b": { page: "answer-citation-v1.html", fixture: "answer", theme: "dark", width: 640, open: true, protoSelector: '#\\31 b .sc-host > div' },
  "2b": { page: "entity-v1.html", fixture: "term", theme: "light", width: 640, protoSelector: "#\\32 b .sc-host > div" },
  "2c": { page: "entity-v1.html", fixture: "metric", theme: "light", width: 640, protoSelector: "#\\32 c .sc-host > div" },
  "2e": { page: "entity-v1.html", fixture: "table", theme: "dark", width: 640, protoSelector: "#\\32 e .sc-host > div" },
};

const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".woff2": "font/woff2" };
const server = http.createServer((request, response) => {
  const file = path.join(root, decodeURIComponent(new URL(request.url, "http://x").pathname));
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { response.writeHead(404).end(); return; }
  // The card's font stylesheet carries the server's font URL as a placeholder; McpAppsResources fills it in
  // at runtime, here it points at the same font files on this throwaway server.
  let body = fs.readFileSync(file);
  if (path.extname(file) === ".css") {
    body = Buffer.from(body.toString("utf8").replaceAll("__FONT_BASE__", `http://${request.headers.host}/${APPS}/fonts`));
  }
  response.writeHead(200, { "content-type": types[path.extname(file)] ?? "application/octet-stream", "access-control-allow-origin": "*" }).end(body);
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const base = `http://127.0.0.1:${server.address().port}`;
fs.mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({ deviceScaleFactor: 2, timezoneId: "UTC", locale: "en-US", viewport: { width: 760, height: 1200 } });

const proto = await context.newPage();
await proto.goto(prototypeUrl, { waitUntil: "networkidle" });
await proto.evaluate(() => document.fonts.ready);

async function ourCard(id, spec, width) {
  const page = await context.newPage();
  await page.setViewportSize({ width, height: 1200 });
  await page.goto(`${base}/${APPS}/${spec.page}`);
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(([theme, data, now]) => {
    document.documentElement.setAttribute("data-theme", theme);
    window.__renderCard(data, now);
  }, [spec.theme, fixtures[spec.fixture], NOW]);
  if (process.env.NUDGE) { await page.evaluate((n) => { document.querySelector(".om-card").style.transform = `translateY(${n}px)`; }, process.env.NUDGE); }
  if (spec.open) { await page.locator("button.om-fold-head").first().click(); }
  await page.evaluate(() => document.fonts.ready);
  return page;
}

async function diff(a, b) {
  const page = await context.newPage();
  const result = await page.evaluate(async ([left, right, tolerance]) => {
    const load = (data) => new Promise((resolve) => { const image = new Image(); image.onload = () => resolve(image); image.src = "data:image/png;base64," + data; });
    const [one, two] = await Promise.all([load(left), load(right)]);
    const width = Math.max(one.width, two.width);
    const height = Math.max(one.height, two.height);
    const draw = (image) => { const c = document.createElement("canvas"); c.width = width; c.height = height; const g = c.getContext("2d"); g.fillStyle = "#ff00ff"; g.fillRect(0, 0, width, height); g.drawImage(image, 0, 0); return g.getImageData(0, 0, width, height); };
    const x = draw(one), y = draw(two);
    const out = document.createElement("canvas"); out.width = width; out.height = height;
    const g = out.getContext("2d"); const o = g.createImageData(width, height);
    const max = 35215 * tolerance * tolerance;
    let bad = 0;
    for (let i = 0; i < x.data.length; i += 4) {
      const dr = x.data[i] - y.data[i], dg = x.data[i + 1] - y.data[i + 1], db = x.data[i + 2] - y.data[i + 2];
      const dy = 0.299 * dr + 0.587 * dg + 0.114 * db, di = 0.596 * dr - 0.274 * dg - 0.322 * db, dq = 0.211 * dr - 0.523 * dg + 0.312 * db;
      const delta = 0.5053 * dy * dy + 0.299 * di * di + 0.1957 * dq * dq;
      const mismatch = delta > max;
      if (mismatch) { bad++; }
      o.data[i] = mismatch ? 255 : 255 - (255 - x.data[i]) * 0.15; o.data[i + 1] = mismatch ? 0 : 255 - (255 - x.data[i + 1]) * 0.15; o.data[i + 2] = mismatch ? 0 : 255 - (255 - x.data[i + 2]) * 0.15; o.data[i + 3] = 255;
    }
    g.putImageData(o, 0, 0);
    return { percent: (100 * bad) / (width * height), size: [one.width, one.height, two.width, two.height], diff: out.toDataURL("image/png").split(",")[1] };
  }, [a.toString("base64"), b.toString("base64"), 0.1]);
  await page.close();
  return result;
}

// The prototype's answer card shows text the AnswerCitation schema cannot carry yet (the applied
// memory line, the "Correct this" button, test counts, the executing user, a second incident
// sentence). The card renders what the schema provides, so those strings are swapped or hidden in
// the prototype before the screenshot; layout, type and colour are still compared.
const PROTOTYPE_TEXT = [
  [" EMEA may be understated by up to 3%.", ""],
  ["41 of 42 tests passing on fct_revenue. Failing: ", "Failing: "],
  [" (12 rows).", " on analytics.fct_revenue"],
  ["Glossary · Sales territories", "Certified · owner Sales territories"],
  ["Ran as priya@acme.com · Snowflake · 1.2s · 4 rows", "Ran through your Snowflake connector · 1.2s · 4 rows · self-reported"],
];
async function adjustPrototype(card) {
  await card.evaluate((root, replacements) => {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      for (const [from, to] of replacements) { if (node.data.includes(from)) { node.data = node.data.replace(from, to); } }
      if (node.data.startsWith("Applied memory:")) { node.parentElement.style.display = "none"; }
      if (node.data === "Correct this") { node.parentElement.style.display = "none"; }
    }
  }, PROTOTYPE_TEXT);
}

const rows = [];
let failed = false;
for (const [id, spec] of Object.entries(VARIANTS)) {
  if (only && !only.includes(id)) { continue; }
  const card = proto.locator(spec.protoSelector).first();
  // The prototype lays cards out at fractional offsets, which shifts glyphs by a pixel in a clipped capture.
  await card.scrollIntoViewIfNeeded();
  if (spec.page.startsWith("answer")) { await adjustPrototype(card); }
  await card.evaluate((element) => {
    // Moved by layout (margin), not transform: a transformed card gets different glyph rasterisation.
    element.style.margin = "";
    const box = element.getBoundingClientRect();
    const fx = (box.left + window.scrollX) % 1, fy = (box.top + window.scrollY) % 1;
    if (fx || fy) { element.style.margin = `${-fy}px 0 0 ${-fx}px`; }
  });
  const theirs = await card.screenshot();
  const page = await ourCard(id, spec, spec.width + 20);
  const ours = await page.locator(".om-card").first().screenshot();
  await page.close();
  const result = await diff(theirs, ours);
  fs.writeFileSync(path.join(outDir, `${id}-prototype.png`), theirs);
  fs.writeFileSync(path.join(outDir, `${id}-ours.png`), ours);
  fs.writeFileSync(path.join(outDir, `${id}-diff.png`), Buffer.from(result.diff, "base64"));
  const [pw, ph, ow, oh] = result.size;
  rows.push({ id, percent: result.percent.toFixed(2), prototype: `${pw / 2}x${ph / 2}`, ours: `${ow / 2}x${oh / 2}` });
  if (result.percent > threshold) { failed = true; }
}

const overflow = [];
for (const [id, spec] of Object.entries(VARIANTS)) {
  if (only && !only.includes(id)) { continue; }
  for (const width of [360, 1280]) {
    const page = await ourCard(id, spec, width);
    const scroll = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }));
    await page.close();
    if (scroll.scroll > scroll.client) { overflow.push(`${id}@${width}: scrollWidth ${scroll.scroll} > ${scroll.client}`); failed = true; }
  }
}

console.table(rows);
console.log(overflow.length ? `Horizontal overflow:\n  ${overflow.join("\n  ")}` : "No horizontal overflow at 360 px or 1280 px.");
console.log(`Images: ${outDir}  (threshold ${threshold}%)`);
await browser.close();
server.close();
process.exit(failed ? 1 : 0);
