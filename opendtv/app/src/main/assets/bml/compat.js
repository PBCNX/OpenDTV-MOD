/* Polyfills so bml.js also runs on old / un-updated Android System WebView (Chrome ~55+).
 * bml.js itself is transpiled to ES2016 syntax (see tools/build_bml.sh); this file adds the missing library APIs. */
(function () {
  "use strict";
  var g = typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : window);
  if (typeof globalThis === "undefined") { try { g.globalThis = g; } catch (e) {} }

  function def(obj, name, fn) {
    if (obj && !obj[name]) {
      try { Object.defineProperty(obj, name, { value: fn, writable: true, configurable: true }); } catch (e) {}
    }
  }
  function toInt(n) { n = Math.trunc ? Math.trunc(+n) : (+n < 0 ? Math.ceil(+n) : Math.floor(+n)); return n !== n ? 0 : n; }

  // .at()
  var at = function (i) { var n = this.length; i = toInt(i); if (i < 0) i += n; return (i < 0 || i >= n) ? undefined : this[i]; };
  def(Array.prototype, "at", at);
  def(String.prototype, "at", function (i) { var n = this.length; i = toInt(i); if (i < 0) i += n; return (i < 0 || i >= n) ? undefined : this.charAt(i); });
  ["Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array",
   "Float32Array", "Float64Array"].forEach(function (k) { if (g[k]) def(g[k].prototype, "at", at); });

  // flat / flatMap
  def(Array.prototype, "flat", function (depth) {
    depth = depth === undefined ? 1 : toInt(depth);
    var out = [];
    (function rec(a, d) {
      for (var i = 0; i < a.length; i++) {
        if (Array.isArray(a[i]) && d > 0) rec(a[i], d - 1); else if (i in a) out.push(a[i]);
      }
    })(this, depth);
    return out;
  });
  def(Array.prototype, "flatMap", function (fn, thisArg) { return Array.prototype.map.call(this, fn, thisArg).flat(1); });

  // Object helpers
  def(Object, "fromEntries", function (it) { var o = {}; for (var e of it) o[e[0]] = e[1]; return o; });
  def(Object, "hasOwn", function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); });
  def(Object, "entries", function (o) { return Object.keys(o).map(function (k) { return [k, o[k]]; }); });
  def(Object, "values", function (o) { return Object.keys(o).map(function (k) { return o[k]; }); });

  // String helpers
  def(String.prototype, "trimStart", String.prototype.trimLeft || function () { return this.replace(/^\s+/, ""); });
  def(String.prototype, "trimEnd", String.prototype.trimRight || function () { return this.replace(/\s+$/, ""); });
  def(String.prototype, "padStart", function (n, p) { var s = String(this); p = p === undefined ? " " : String(p); if (!p) return s; while (s.length < n) { s = p + s; } return s.length > n ? s.slice(s.length - n) : s; });
  def(String.prototype, "padEnd", function (n, p) { var s = String(this); p = p === undefined ? " " : String(p); if (!p) return s; while (s.length < n) { s = s + p; } return s.slice(0, n); });
  def(String.prototype, "replaceAll", function (pat, rep) {
    if (pat instanceof RegExp) {
      if (!pat.global) throw new TypeError("replaceAll must be called with a global RegExp");
      return this.replace(pat, rep);
    }
    var s = String(this), p = String(pat), out = "", i = 0, j;
    if (p === "") return s.replace(/(?:)/g, rep);
    while ((j = s.indexOf(p, i)) !== -1) {
      out += s.slice(i, j) + (typeof rep === "function" ? rep(p, j, s) : String(rep).replace(/\$&/g, p));
      i = j + p.length;
    }
    return out + s.slice(i);
  });
  def(String.prototype, "matchAll", function (re) {
    var s = String(this), r = new RegExp(re.source, re.flags.indexOf("g") < 0 ? re.flags + "g" : re.flags), out = [], m;
    while ((m = r.exec(s)) !== null) { out.push(m); if (m[0] === "") r.lastIndex++; }
    return out[Symbol.iterator]();
  });

  // Promise
  if (g.Promise) {
    def(Promise.prototype, "finally", function (f) {
      var C = this.constructor || Promise;
      return this.then(function (v) { return C.resolve(f()).then(function () { return v; }); },
                       function (e) { return C.resolve(f()).then(function () { throw e; }); });
    });
    def(Promise, "allSettled", function (a) {
      return Promise.all(Array.from(a, function (p) {
        return Promise.resolve(p).then(function (value) { return { status: "fulfilled", value: value }; },
                                       function (reason) { return { status: "rejected", reason: reason }; });
      }));
    });
  }
  def(g, "queueMicrotask", function (f) { Promise.resolve().then(f); });
  def(g, "structuredClone", function (v) { return JSON.parse(JSON.stringify(v)); });

  // DOM
  function nodesToFragment(nodes) {
    var f = document.createDocumentFragment();
    nodes.forEach(function (n) { f.appendChild(n instanceof Node ? n : document.createTextNode(String(n))); });
    return f;
  }
  if (typeof Element !== "undefined") {
    def(Element.prototype, "replaceChildren", function () {
      while (this.firstChild) this.removeChild(this.firstChild);
      if (arguments.length) this.appendChild(nodesToFragment(Array.prototype.slice.call(arguments)));
    });
    def(Element.prototype, "prepend", function () { this.insertBefore(nodesToFragment(Array.prototype.slice.call(arguments)), this.firstChild); });
    def(Element.prototype, "append", function () { this.appendChild(nodesToFragment(Array.prototype.slice.call(arguments))); });
    def(Element.prototype, "closest", function (sel) {
      var e = this;
      var m = e.matches || e.webkitMatchesSelector || e.msMatchesSelector;
      while (e && e.nodeType === 1) { if (m.call(e, sel)) return e; e = e.parentElement; }
      return null;
    });
  }
  if (typeof Document !== "undefined") {
    def(Document.prototype, "replaceChildren", Element.prototype.replaceChildren);
    def(Document.prototype, "prepend", Element.prototype.prepend);
    def(Document.prototype, "append", Element.prototype.append);
  }
  if (typeof DocumentFragment !== "undefined") {
    def(DocumentFragment.prototype, "replaceChildren", Element.prototype.replaceChildren);
    def(DocumentFragment.prototype, "append", Element.prototype.append);
    def(DocumentFragment.prototype, "prepend", Element.prototype.prepend);
  }

  // Audio: very old WebViews only have the prefixed constructor
  if (!g.AudioContext && g.webkitAudioContext) g.AudioContext = g.webkitAudioContext;

  // Report script errors to the app (shown in the status line) instead of failing silently
  g.addEventListener("error", function (e) {
    try { if (g.AndroidBml) g.AndroidBml.log("JS error: " + e.message + " @" + (e.lineno || "?")); } catch (x) {}
  });
})();
