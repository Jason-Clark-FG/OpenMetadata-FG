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
    if (minutes < 60) { return minutes + " min ago"; }
    var hours = Math.floor(minutes / 60);
    return hours < 48 ? hours + " h ago" : Math.floor(hours / 24) + " d ago";
  }

  function notices(items) {
    var rows = items.filter(Boolean).map(function (item) {
      return h("div", "om-notice" + (item.tone === "warn" ? " warn" : ""),
        h("div", "om-notice-label", item.label), h("div", "om-notice-body", item.body));
    });
    return rows.length ? h("div", "om-notices", rows) : null;
  }
  function section(title, body) {
    return body && (body.length === undefined || body.length) ? h("div", "om-section", h("div", "om-section-title", title), body) : null;
  }
  function stats(items) {
    var cells = items.filter(Boolean).map(function (item) {
      return h("div", "om-stat", h("div", "om-stat-label", item.label),
        h("div", "om-stat-value" + (item.tone ? " " + item.tone : ""), item.value));
    });
    return cells.length ? h("div", "om-stats", cells) : null;
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
    var toggle = h("span", "om-fold-toggle", "Show");
    var head = h("button", "om-fold-head", h("span", "om-chevron", "›"), h("span", "om-fold-title", spec.title),
      h("span", "om-fold-meta", spec.meta), h("span", "om-spacer"), toggle);
    head.type = "button";
    head.setAttribute("aria-expanded", "false");
    head.addEventListener("click", function () {
      var open = root.classList.toggle("open");
      head.setAttribute("aria-expanded", String(open));
      toggle.textContent = open ? "Hide" : "Show";
      reportSize();
    });
    root.appendChild(head);
    root.appendChild(h("div", "om-fold-body", spec.body));
    return root;
  }

  /** spec: {kind, pill: {text, tone}, blocks: [node], fold: {title, meta, body}, footer: {left, links}} */
  function card(spec) {
    var header = h("div", "om-header", h("div", "om-logo", "O"), h("span", "om-brand-name", "OpenMetadata"),
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

  function render(data, now) {
    var root = document.getElementById("root");
    root.textContent = "";
    root.appendChild(renderer(data, typeof now === "number" ? now : Date.now()));
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

  return { h: h, link: link, ago: ago, tag: tag, notices: notices, section: section, stats: stats,
    card: card, mount: mount };
}());
