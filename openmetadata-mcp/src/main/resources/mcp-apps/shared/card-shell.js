/* The shared card shell and the MCP Apps host bridge (spec 2026-01-26).
   A variant script calls OM.mount(render); render(structuredContent, now) returns the card element,
   built from OM.card() and the block helpers. Every string from the catalog goes in as text. */
var OM = (function () {
  "use strict";
  var PROTOCOL = "2026-01-26";
  var pending = {};
  var nextId = 1;
  var lastHeight = 0;
  var rendered = false;
  var renderer = null;

  function send(message) { window.parent.postMessage(message, "*"); }
  function request(method, params) {
    return new Promise(function (resolve, reject) {
      var id = nextId++;
      pending[id] = { resolve: resolve, reject: reject };
      send({ jsonrpc: "2.0", id: id, method: method, params: params });
    });
  }
  function notify(method, params) { send({ jsonrpc: "2.0", method: method, params: params || {} }); }

  function applyHostContext(context) {
    if (context && (context.theme === "light" || context.theme === "dark")) {
      document.documentElement.setAttribute("data-theme", context.theme);
    }
  }

  function flatten(children, parent) {
    children.forEach(function (child) {
      if (Array.isArray(child)) {
        flatten(child, parent);
      } else if (child !== null && child !== undefined && child !== false) {
        parent.appendChild(typeof child === "object" ? child : document.createTextNode(String(child)));
      }
    });
  }
  function h(tag, className) {
    var node = document.createElement(tag);
    if (className) { node.className = className; }
    flatten(Array.prototype.slice.call(arguments, 2), node);
    return node;
  }

  function safeUrl(url) { return typeof url === "string" && /^https?:\/\//i.test(url) ? url : null; }
  function link(url, className) {
    var target = safeUrl(url);
    var children = Array.prototype.slice.call(arguments, 2);
    if (!target) { return h.apply(null, ["span", className].concat(children)); }
    var anchor = h.apply(null, ["a", className].concat(children));
    anchor.href = target;
    anchor.rel = "noopener noreferrer";
    anchor.addEventListener("click", function (event) {
      event.preventDefault();
      request("ui/open-link", { url: target }).catch(function () {});
    });
    return anchor;
  }

  function ago(at, now) {
    var minutes = Math.max(Math.floor((now - at) / 60000), 0);
    if (minutes < 60) { return minutes + "m ago"; }
    var hours = Math.floor(minutes / 60);
    return hours < 48 ? hours + "h ago" : Math.floor(hours / 24) + "d ago";
  }

  /** 48600000 becomes 48.6M, 1200 becomes 1.2k; below a thousand the number is kept whole. */
  function compact(value) {
    if (typeof value !== "number" || !isFinite(value)) { return String(value); }
    var abs = Math.abs(value);
    var units = [[1e12, "T"], [1e9, "B"], [1e6, "M"], [1e3, "k"]];
    for (var i = 0; i < units.length; i++) {
      if (abs >= units[i][0]) {
        var scaled = value / units[i][0];
        return (Math.abs(scaled) >= 100 ? Math.round(scaled) : Math.round(scaled * 10) / 10) + units[i][1];
      }
    }
    return String(Math.round(value * 100) / 100);
  }

  /** The time of day in the host's locale. */
  function clock(at) {
    return new Date(at).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  }

  function direction(value) { return value === "up" || value === "down" ? value : ""; }

  /** The headline number of a card with its change chip. */
  function valueRow(value, change, changeDirection) {
    return h("div", "om-value-row", h("div", "om-value", value),
      change ? h("div", "om-change " + direction(changeDirection), change) : null);
  }

  var SUFFIX = { k: 1e3, K: 1e3, m: 1e6, M: 1e6, b: 1e9, B: 1e9, t: 1e12, T: 1e12 };
  function magnitude(text) {
    var match = /(-?\d[\d,]*\.?\d*)\s*([kKmMbBtT])?/.exec(String(text));
    return match ? parseFloat(match[1].replace(/,/g, "")) * (SUFFIX[match[2]] || 1) : 0;
  }

  /** rows: [{label, value, change, changeDirection, share}]. Without a share, bars scale to the values. */
  function bars(rows) {
    if (!rows || !rows.length) { return null; }
    var largest = Math.max.apply(null, rows.map(function (row) { return Math.abs(magnitude(row.value)); }));
    return h("div", "om-bars", rows.map(function (row) {
      var share = typeof row.share === "number" ? row.share : (largest > 0 ? Math.abs(magnitude(row.value)) / largest : 0);
      var fill = h("div", "om-bar-fill");
      fill.style.width = Math.max(0, Math.min(1, share)) * 100 + "%";
      return h("div", "om-bar-row", h("div", "om-bar-label", row.label), h("div", "om-bar-track", fill),
        h("div", "om-bar-value", row.value), h("div", "om-bar-change " + direction(row.changeDirection), row.change));
    }));
  }

  function agoLong(at, now) {
    var minutes = Math.max(Math.floor((now - at) / 60000), 0);
    var count = minutes < 60 ? minutes : minutes < 2880 ? Math.floor(minutes / 60) : Math.floor(minutes / 1440);
    var unit = minutes < 60 ? "minute" : minutes < 2880 ? "hour" : "day";
    return count + " " + unit + (count === 1 ? "" : "s") + " ago";
  }

  function notices(items) {
    var rows = items.filter(Boolean).map(function (item) {
      return h("div", "om-notice" + (item.tone === "warn" || item.tone === "brand" ? " " + item.tone : ""),
        h("div", "om-notice-label" + (item.wide ? " wide" : ""), item.label), h("div", "om-notice-body" + (item.mono ? " om-joinpath" : ""), item.body));
    });
    return rows.length ? h("div", "om-notices", rows) : null;
  }
  function section(title, body) {
    return body && (body.length === undefined || body.length) ? h("div", "om-section", h("div", "om-section-title", title), body) : null;
  }
  function stats(items, wide) {
    var cells = items.filter(Boolean).map(function (item) {
      return h("div", "om-stat", h("div", "om-stat-label", item.label),
        h("div", "om-stat-value" + (item.tone ? " " + item.tone : ""), item.value));
    });
    return cells.length ? h("div", "om-stats" + (wide ? " cells" : ""), cells) : null;
  }
  function tag(text, tone) { return h("span", "om-tag" + (tone ? " " + tone : ""), text); }

  function fold(spec) {
    if (!spec) { return null; }
    var root = h("div", "om-fold");
    if (!spec.body) {
      root.appendChild(h("div", "om-fold-head static", h("span", "om-fold-title", spec.title),
        h("span", "om-fold-meta", spec.meta)));
      return root;
    }
    var toggle = spec.toggle ? h("span", "om-fold-toggle", "Show") : null;
    var head = h("button", "om-fold-head", h("span", "om-chevron", "›"), h("span", "om-fold-title", spec.title),
      h("span", "om-fold-meta", spec.meta), h("span", "om-spacer"), toggle);
    head.type = "button";
    head.setAttribute("aria-expanded", "false");
    head.addEventListener("click", function () {
      var open = root.classList.toggle("open");
      head.setAttribute("aria-expanded", String(open));
      if (toggle) { toggle.textContent = open ? "Hide" : "Show"; }
      reportSize();
    });
    root.appendChild(head);
    root.appendChild(h("div", "om-fold-body", spec.body));
    return root;
  }

  /* OpenMetadata entity types as people read them. A type missing here is spelled out from its
     camelCase name, so a new type still reads as a word rather than as "Asset". */
  var TYPE_LABELS = {
    table: "Table", metric: "Metric", glossaryTerm: "Glossary term", glossary: "Glossary",
    dashboard: "Dashboard", chart: "Chart", dashboardDataModel: "Dashboard data model",
    pipeline: "Pipeline", topic: "Topic", container: "Container", mlmodel: "ML model",
    storedProcedure: "Stored procedure", searchIndex: "Search index", database: "Database",
    databaseSchema: "Schema", apiEndpoint: "API endpoint", apiCollection: "API collection",
    query: "Query", testCase: "Test case", testSuite: "Test suite", dataProduct: "Data product",
    domain: "Domain", tag: "Tag", classification: "Classification", user: "User", team: "Team",
    persona: "Persona", directory: "Directory", file: "File", spreadsheet: "Spreadsheet",
    worksheet: "Worksheet", contextMemory: "Memory", column: "Column"
  };
  /* The narrow type chip in source and link rows keeps the design's short names. */
  var SHORT_LABELS = { glossaryTerm: "Term", dashboardDataModel: "Data model", storedProcedure: "Procedure",
    searchIndex: "Index", apiEndpoint: "Endpoint", apiCollection: "API" };
  var LOWER_KEYS = Object.keys(TYPE_LABELS).reduce(function (keys, key) { keys[key.toLowerCase()] = key; return keys; }, {});
  var URL_TYPES = ["table", "metric", "glossary", "dashboard", "chart", "dashboardDataModel", "pipeline", "topic",
    "container", "mlmodel", "storedProcedure", "searchIndex", "database", "databaseSchema", "apiEndpoint",
    "apiCollection", "testCase", "dataProduct", "domain", "tag", "classification"];

  function canonicalType(type) {
    var key = String(type || "");
    return TYPE_LABELS[key] ? key : LOWER_KEYS[key.toLowerCase()] || key;
  }

  function typeLabel(type) {
    var key = canonicalType(type);
    if (!key) { return null; }
    return TYPE_LABELS[key] || key.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase().replace(/^./, function (c) { return c.toUpperCase(); });
  }

  function typeShort(type) { return SHORT_LABELS[canonicalType(type)] || typeLabel(type); }

  function typeFromUrl(url, fqn) {
    var match = /^[a-z]+:\/\/[^/]+\/([A-Za-z]+)\//.exec(String(url || ""));
    var segment = match ? canonicalType(match[1]) : "";
    if (segment === "glossary") { return String(fqn || "").indexOf(".") > 0 ? "glossaryTerm" : "glossary"; }
    return URL_TYPES.indexOf(segment) >= 0 ? segment : null;
  }

  function typeFromShape(data) {
    if (data.columns || data.tableType || data.tableConstraints) { return "table"; }
    if (data.metricExpression || data.metricType) { return "metric"; }
    if (data.synonyms && data.glossary) { return "glossaryTerm"; }
    if (data.charts) { return "dashboard"; }
    if (data.tasks) { return "pipeline"; }
    if (data.messageSchema) { return "topic"; }
    return null;
  }

  /** The entity type a payload is about: stated, else read from its link, else from its fields. */
  function typeOf(data) {
    var stated = data && (data.entityType || (TYPE_LABELS[canonicalType(data.kind)] ? data.kind : null));
    return stated ? canonicalType(stated)
      : data ? typeFromUrl(data.openMetadataUrl || data.url, data.fullyQualifiedName || data.fqn) || typeFromShape(data) : null;
  }

  /** spec: {kind, pill: {text, tone}, blocks: [node], fold: {title, meta, body}, footer: {left, links}} */
  var LOGO_SVG = '<svg viewBox="0 0 53 64" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">'
    + '<path d="M0.000834935 13L0 50.6583C0.113913 58.0366 11.9352 64 26.5 64C41.0648 64 52.8852 58.0366 52.9991 50.6583L53 13C52.8861 20.3784 41.0648 26.3418 26.5 26.3418C11.9352 26.3418 0.114748 20.3784 0.000834935 13Z" fill="url(#om-logo-fill)"/>'
    + '<path fill-rule="evenodd" clip-rule="evenodd" d="M26.5 27C41.1355 27 53 20.9558 53 13.4999C53 6.04415 41.1355 0 26.5 0C11.8645 0 0 6.04415 0 13.4999C0 20.9558 11.8645 27 26.5 27ZM26.6059 20.412C33.9237 20.412 39.856 17.3899 39.856 13.6619C39.856 9.93407 33.9237 6.91199 26.6059 6.91199C19.2882 6.91199 13.356 9.93407 13.356 13.6619C13.356 17.3899 19.2882 20.412 26.6059 20.412Z" fill="#8D6AF1"/>'
    + '<path d="M18.1319 45.7052V54.3835C18.1319 55.1513 17.3025 55.6326 16.6358 55.2517L9.67578 51.2745C9.36418 51.0965 9.17188 50.7652 9.17188 50.4063V30.1506C9.17188 29.3709 10.0246 28.8912 10.6911 29.2959L26.0457 38.6218C26.3614 38.8135 26.7571 38.8156 27.0748 38.6273L42.862 29.2684C43.5286 28.8732 44.372 29.3537 44.372 30.1286V50.8082C44.372 51.1802 44.1655 51.5215 43.836 51.6941L36.876 55.3398C36.2101 55.6885 35.4119 55.2056 35.4119 54.4539V45.6621C35.4119 44.896 34.5858 44.4144 33.9192 44.7919L27.0638 48.6739C26.7514 48.8508 26.3682 48.8467 26.0597 48.6632L19.6432 44.8458C18.9767 44.4492 18.1319 44.9295 18.1319 45.7052Z" fill="white"/>'
    + '<defs><linearGradient id="om-logo-fill" x1="26.5" y1="18.8101" x2="26.5" y2="64" gradientUnits="userSpaceOnUse">'
    + '<stop stop-color="#35198E"/><stop offset="1" stop-color="#8D6AF1"/></linearGradient></defs></svg>';

  function logo() {
    var mark = h("span", "om-logo");
    mark.innerHTML = LOGO_SVG;
    return mark;
  }

  function card(spec) {
    var header = h("div", "om-header", logo(), h("span", "om-brand-name", "OpenMetadata"),
      h("span", null, "·"), h("span", null, spec.kind), h("div", "om-spacer"),
      spec.pill ? h("span", "om-pill " + (spec.pill.tone || ""), spec.pill.text) : null);
    var footer = spec.footer && ((spec.footer.left || []).length || (spec.footer.links || []).length)
      ? h("div", "om-footer", spec.footer.left, h("div", "om-spacer"), spec.footer.links) : null;
    return h("div", "om-card", header, spec.blocks, fold(spec.fold), footer);
  }

  function reportSize() {
    var height = Math.ceil(document.documentElement.getBoundingClientRect().height);
    if (height !== lastHeight) {
      lastHeight = height;
      notify("ui/notifications/size-changed", { width: document.documentElement.scrollWidth, height: height });
    }
  }

  /* Type chips keep the design's fixed width so the names beside them line up. A label too long
     for it widens every chip in its list together, rather than pushing one row out of line. */
  function fitKindChips(root) {
    var lists = [];
    Array.prototype.forEach.call(root.querySelectorAll(".om-kind"), function (chip) {
      var list = chip.parentElement && chip.parentElement.parentElement;
      if (lists.indexOf(list) < 0) { lists.push(list); }
    });
    lists.forEach(function (list) {
      var chips = Array.prototype.filter.call(list.querySelectorAll(".om-kind"), function (chip) {
        return chip.parentElement.parentElement === list;
      });
      var widest = Math.max.apply(null, chips.map(function (chip) { return chip.scrollWidth - chip.clientWidth; }));
      if (widest > 0) {
        chips.forEach(function (chip) { chip.style.width = (chip.offsetWidth + widest) + "px"; });
      }
    });
  }

  function render(data, now) {
    var root = document.getElementById("root");
    root.textContent = "";
    root.appendChild(renderer(data, typeof now === "number" ? now : Date.now()));
    fitKindChips(root);
    rendered = true;
    reportSize();
  }

  function mount(renderFn) {
    renderer = renderFn;
    window.__renderCard = render;
    window.addEventListener("message", function (event) {
      if (event.source !== window.parent) { return; }
      var message = event.data;
      if (!message || message.jsonrpc !== "2.0") { return; }
      if (message.method === "ui/notifications/tool-result") {
        var data = message.params && message.params.structuredContent;
        if (data && typeof data === "object") { render(data); }
        else if (!rendered) { document.getElementById("root").textContent = "No data to show"; }
      } else if (message.method === "ui/notifications/host-context-changed") {
        applyHostContext(message.params);
      } else if (message.id !== undefined && pending[message.id]) {
        var call = pending[message.id];
        delete pending[message.id];
        if (message.error) { call.reject(message.error); } else { call.resolve(message.result); }
      }
    });
    if (typeof ResizeObserver === "function") { new ResizeObserver(reportSize).observe(document.body); }
    if (window.parent !== window) {
      request("ui/initialize", {
        protocolVersion: PROTOCOL,
        appCapabilities: {},
        clientInfo: { name: "openmetadata-context-card", version: "1.0.0" }
      }).then(function (result) {
        applyHostContext(result && result.hostContext);
        notify("ui/notifications/initialized");
      }).catch(function () { notify("ui/notifications/initialized"); });
    }
  }

  return { h: h, link: link, ago: ago, agoLong: agoLong, tag: tag, notices: notices, section: section, stats: stats,
    compact: compact, clock: clock, valueRow: valueRow, bars: bars, card: card, mount: mount,
    typeOf: typeOf, typeLabel: typeLabel, typeShort: typeShort };
}());
