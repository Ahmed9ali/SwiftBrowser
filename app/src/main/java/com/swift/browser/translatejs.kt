package com.swift.browser

// Injected into the page when the user taps Translate. It finds the visible text,
// sends it in small batches to Kotlin (SwiftTranslator.translateBatch), writes the
// results back into the same nodes, keeps watching for new content (infinite scroll,
// React re-renders) and can restore the original text without reloading the page.
//
// NOTE: this is a Kotlin raw string, so a literal dollar sign in the JS must be written
// as ${'$'} (it is used once, for the end of a regex).
const val TRANSLATE_JS = """
(function () {
  if (window.__swiftTr && window.__swiftTr.ready) return;

  var BLOCK = 'script,style,noscript,code,pre,textarea,svg,canvas,iframe,kbd,samp,math,[translate="no"],.notranslate,[contenteditable="true"],[contenteditable=""]';
  var ATTRS = ['placeholder', 'title', 'alt'];
  var BATCH_ITEMS = 30, BATCH_CHARS = 2500, MAX_PER_FLUSH = 600;
  var HAS_LETTER = /\p{L}/u;

  var T = { ready: true };
  var src = '', active = false, obs = null, nextId = 1, firstDone = false, timer = 0;
  var pending = {}, roots = [];
  var mineText = new WeakMap(), mineAttr = new WeakMap();
  var saved = [], savedSet = new WeakSet();

  function blocked(el) {
    try { return !!(el && el.closest && el.closest(BLOCK)); } catch (e) { return false; }
  }

  function okText(n) {
    var v = n.nodeValue;
    if (!v || !HAS_LETTER.test(v)) return false;
    if (mineText.get(n) === v) return false;
    return true;
  }

  function valueOf(it) {
    return it.k === 't' ? it.n.nodeValue : it.n.getAttribute(it.a);
  }

  // Collect translatable text nodes (and a few attributes) under a root.
  function collect(root, out) {
    if (!root) return;
    if (root.nodeType === 3) {
      if (okText(root) && !blocked(root.parentElement)) out.push({ k: 't', n: root });
      return;
    }
    if (root.nodeType !== 1 || blocked(root)) return;

    var w = document.createTreeWalker(root, 5, {
      acceptNode: function (x) {
        if (x.nodeType === 1) return x.matches(BLOCK) ? 2 : 3; // reject subtree : keep walking
        return okText(x) ? 1 : 3;
      }
    });
    var x;
    while ((x = w.nextNode())) out.push({ k: 't', n: x });

    var els = [root].concat(Array.prototype.slice.call(root.querySelectorAll('[placeholder],[title],[alt]')));
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (!el.getAttribute || blocked(el)) continue;
      for (var j = 0; j < ATTRS.length; j++) {
        var a = ATTRS[j], v = el.getAttribute(a);
        if (!v || !HAS_LETTER.test(v)) continue;
        var m = mineAttr.get(el);
        if (m && m[a] === v) continue;
        out.push({ k: 'a', n: el, a: a });
      }
    }
  }

  function nearViewport(it) {
    var el = it.k === 't' ? it.n.parentElement : it.n;
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect(), h = window.innerHeight || 800;
    return r.bottom > -h && r.top < h * 2;
  }

  // Send items to Kotlin in small batches, text on screen first.
  function send(items) {
    var near = [], far = [], i;
    for (i = 0; i < items.length; i++) (nearViewport(items[i]) ? near : far).push(items[i]);
    items = near.concat(far);

    var batch = [], chars = 0;
    function flush() {
      if (!batch.length) return;
      var id = nextId++, texts = [], meta = [];
      for (var k = 0; k < batch.length; k++) {
        var raw = valueOf(batch[k]) || '';
        var m = /^(\s*)([\s\S]*?)(\s*)${'$'}/.exec(raw);
        meta.push({ it: batch[k], lead: m[1], trail: m[3], raw: raw });
        texts.push(m[2]);
      }
      pending[id] = meta;
      batch = []; chars = 0;
      try { window.SwiftTranslator.translateBatch(id, JSON.stringify(texts), src); } catch (e) {}
    }
    for (i = 0; i < items.length; i++) {
      var len = (valueOf(items[i]) || '').length;
      if (batch.length >= BATCH_ITEMS || (batch.length && chars + len > BATCH_CHARS)) flush();
      batch.push(items[i]);
      chars += len;
    }
    flush();
  }

  // Called by Kotlin with the translated strings of one batch.
  T.apply = function (id, arr) {
    var meta = pending[id];
    delete pending[id];
    if (!meta || !active || !arr) return;
    for (var i = 0; i < meta.length; i++) {
      var t = arr[i], m = meta[i], it = m.it;
      if (typeof t !== 'string' || !t || t === m.raw.trim()) continue;
      var out = m.lead + t + m.trail;
      if (it.k === 't') {
        var n = it.n;
        if (!n.isConnected || n.nodeValue !== m.raw) continue; // the page changed it meanwhile
        if (!savedSet.has(n)) { savedSet.add(n); saved.push({ k: 't', n: n, v: m.raw }); }
        n.nodeValue = out;
        mineText.set(n, out);
      } else {
        var el = it.n;
        if (!el.isConnected || el.getAttribute(it.a) !== m.raw) continue;
        var mm = mineAttr.get(el) || {};
        if (!mm[it.a]) saved.push({ k: 'a', n: el, a: it.a, v: m.raw });
        el.setAttribute(it.a, out);
        mm[it.a] = out;
        mineAttr.set(el, mm);
      }
    }
    if (!firstDone) {
      firstDone = true;
      try { window.SwiftTranslator.onFirstDone(src); } catch (e) {}
    }
  };

  // New or changed content is collected after a short pause and translated too.
  function schedule(list) {
    for (var i = 0; i < list.length; i++) roots.push(list[i]);
    if (timer) return;
    timer = setTimeout(function () {
      timer = 0;
      var rs = roots.splice(0, roots.length), out = [], seen = new Set();
      for (var i = 0; i < rs.length && out.length < MAX_PER_FLUSH; i++) {
        var r = rs[i];
        if (!r.isConnected || seen.has(r)) continue;
        seen.add(r);
        collect(r, out);
      }
      if (active && out.length) send(out);
    }, 350);
  }

  function startObserver() {
    obs = new MutationObserver(function (muts) {
      var list = [];
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'characterData') {
          if (mineText.get(m.target) !== m.target.nodeValue) list.push(m.target);
        } else {
          for (var j = 0; j < m.addedNodes.length; j++) list.push(m.addedNodes[j]);
        }
      }
      if (list.length) schedule(list);
    });
    obs.observe(document.body, { childList: true, subtree: true, characterData: true });
  }

  T.start = function (lang) {
    if (active || !document.body) return;
    src = lang;
    active = true;
    firstDone = false;
    var out = [];
    collect(document.body, out);
    send(out);
    startObserver();
  };

  // Put the original text back (the Undo button). No page reload needed.
  T.restore = function () {
    active = false;
    if (obs) { obs.disconnect(); obs = null; }
    if (timer) { clearTimeout(timer); timer = 0; }
    roots = [];
    pending = {};
    for (var i = saved.length - 1; i >= 0; i--) {
      var s = saved[i];
      try {
        if (s.k === 't') s.n.nodeValue = s.v; else s.n.setAttribute(s.a, s.v);
      } catch (e) {}
    }
    saved = [];
    savedSet = new WeakSet();
    mineText = new WeakMap();
    mineAttr = new WeakMap();
  };

  window.__swiftTr = T;
})();
"""
