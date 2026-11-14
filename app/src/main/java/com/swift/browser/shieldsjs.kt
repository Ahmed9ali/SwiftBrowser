package com.swift.browser

// Page-side shields. Kept deliberately light: one stylesheet for cosmetic hiding (no DOM rescans),
// tap reporting for the navigation guard, popup/dialog/history abuse limits, and the fetch/XHR shim.
// NOTE: Kotlin raw string — never write a bare dollar sign in here, use ${'$'}.
const val SHIELDS_JS = """
(function () {
  if (window.__swiftShields) return;
  window.__swiftShields = true;
  var H = (location.hostname || '').toLowerCase();
  var IS_YT = /(^|\.)youtube(-nocookie)?\.com${'$'}/.test(H);
  var NAV = window.SwiftNav;
  var TOP = (window === window.top);
  var HOSTS = null;
  var EXEMPT = false;
  try { EXEMPT = !!(window.SwiftFilter && window.SwiftFilter.exempt && window.SwiftFilter.exempt(H)); } catch (e) {}
  try { if (!EXEMPT && window.SwiftFilter && window.SwiftFilter.getBlockedHosts) HOSTS = JSON.parse(window.SwiftFilter.getBlockedHosts()); } catch (e) {}
  function adHostUrl(u) {
    if (!HOSTS) return false;
    try {
      var h = new URL(String(u), location.href).hostname.toLowerCase();
      while (h) {
        if (HOSTS[h]) return true;
        var dot = h.indexOf('.');
        if (dot < 0) return false;
        h = h.substring(dot + 1);
      }
    } catch (e) {}
    return false;
  }

  // ---------- 1. tap reporter (feeds the native navigation guard) ----------
  var lastTrusted = 0;
  function anchorOf(el) {
    while (el && el.nodeType === 1) {
      if (el.tagName === 'A' && el.href) return el;
      el = el.parentElement;
    }
    return null;
  }
  function reportTap(e) {
    if (!e.isTrusted) return;
    lastTrusted = Date.now();
    var href = '';
    try {
      var a = anchorOf(e.target);
      if (a) {
        var h = String(a.href);
        if (/^https?:/i.test(h) && h.split('#')[0] !== location.href.split('#')[0]) href = h;
      }
      if (NAV && NAV.tap) NAV.tap(href);
    } catch (err) {}
  }
  ['pointerdown', 'touchstart', 'click'].forEach(function (t) {
    try { window.addEventListener(t, reportTap, true); } catch (e) {}
  });

  // ---------- 2. invisible click-catcher overlays ----------
  // A transparent layer covering most of the screen that exists only to steal the first tap.
  function looksLikeCatcher(el) {
    if (!el || el === document.body || el === document.documentElement) return false;
    var cs = getComputedStyle(el);
    if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
    var r = el.getBoundingClientRect();
    if (r.width < innerWidth * 0.7 || r.height < innerHeight * 0.7) return false;
    if (el.querySelector('video,canvas,img,input,textarea,select,button')) return false;
    var invisible = parseFloat(cs.opacity) < 0.1 ||
      ((cs.backgroundColor === 'rgba(0, 0, 0, 0)' || cs.backgroundColor === 'transparent') &&
       cs.backgroundImage === 'none' && (el.textContent || '').trim() === '');
    return invisible;
  }
  function killCatcher(e) {
    if (!e.isTrusted) return;
    try {
      var x, y;
      if (e.touches && e.touches[0]) { x = e.touches[0].clientX; y = e.touches[0].clientY; }
      else { x = e.clientX; y = e.clientY; }
      var el = document.elementFromPoint(x, y);
      if (looksLikeCatcher(el)) {
        e.preventDefault(); e.stopPropagation();
        el.style.setProperty('display', 'none', 'important');
      }
    } catch (err) {}
  }
  try { window.addEventListener('touchstart', killCatcher, { capture: true, passive: false }); } catch (e) {}

  // ---------- 3. popup / dialog / history abuse limits ----------
  function stubWindow() {
    return { closed: false, focus: function () {}, blur: function () {}, close: function () {},
      postMessage: function () {}, location: { href: '' }, document: { write: function () {}, close: function () {} } };
  }
  try {
    var realOpen = window.open;
    window.open = function () {
      if (Date.now() - lastTrusted > 1500) return stubWindow();
      return realOpen.apply(window, arguments);
    };
  } catch (e) {}

  ['alert', 'confirm', 'prompt'].forEach(function (n) {
    try {
      var real = window[n], count = 0;
      window[n] = function () {
        var fresh = Date.now() - lastTrusted < 1500;
        if (!fresh && ++count > 1) return n === 'confirm' ? false : (n === 'prompt' ? null : undefined);
        return real.apply(window, arguments);
      };
    } catch (e) {}
  });

  try {
    var winStart = Date.now(), calls = 0;
    ['pushState', 'replaceState'].forEach(function (n) {
      var real = history[n];
      history[n] = function () {
        if (Date.now() - winStart > 10000) { winStart = Date.now(); calls = 0; }
        if (++calls > 20) return;
        return real.apply(this, arguments);
      };
    });
  } catch (e) {}

  // ---------- 4. anti-adblock stubs ----------
  try {
    if (!window.adsbygoogle) window.adsbygoogle = { push: function () {}, loaded: true };
    if (!window.canRunAds) window.canRunAds = true;
  } catch (e) {}

  // ---------- 5. cosmetic filters: ONE stylesheet, no rescans ----------
  var COSMETIC = [
    'ins.adsbygoogle', '.adsbygoogle', '.google-ad', '.google-ads', '.google-ad-slot',
    '[id^="google_ads_"]', '[id^="div-gpt-ad"]', '[id^="aswift_"]', 'iframe[id^="google_ads_iframe"]',
    'iframe[src*="googlesyndication.com"]', 'iframe[src*="doubleclick.net"]',
    'iframe[src*="adsterra"]', 'iframe[src*="exoclick"]', 'iframe[src*="propellerads"]',
    'iframe[src*="a-ads.com"]', 'iframe[src*="coinzilla"]', 'iframe[src*="mgid.com"]', 'iframe[src*="popads"]',
    'iframe[src*="nudus"]', 'iframe[src*="luckyday"]',
    '.ad-slot', '.ad-container', '.ad-banner', '.ad-wrapper', '.ad-unit', '.adbox', '.adsbox',
    '.ads-container', '.advertisement', '.advert-banner', '#ad-container', '#ad_banner', '#ad-banner',
    '#adbox', '.popup-ad', '.banner-ad', '.sticky-ad', '#sticky-ad', '.ad-placement', '.ads-banner',
    '[data-ad-slot]', '[data-google-query-id]', 'amp-ad', 'ins[data-ad-client]', '.google-auto-placed',
    '.adsbygoogle-noablate', '.taboola', '[id^="taboola-"]', '.OUTBRAIN', '.sponsored-post',
    '[id^="mgid_"]', '[class^="mgbox"]', '[id^="bsa-zone"]', '#carbonads', '.carbon-wrap',
    '.anti-adblock', '#anti-adblock', '.adblock-modal', '#adblock-modal',
    'a[href*="skillify"]', 'a[href*="nudus"]', 'img[src*="nudus"]', 'a[href*="brolackview"]', 'img[src*="brolackview"]'
  ];
  if (IS_YT) {
    COSMETIC = COSMETIC.concat([
      '#masthead-ad', '#player-ads', 'ytd-ad-slot-renderer', 'ytd-in-feed-ad-layout-renderer', 'ytd-display-ad-renderer',
      'ytd-promoted-sparkles-web-renderer', 'ytd-promoted-video-renderer', 'ytd-banner-promo-renderer',
      'ytd-statement-banner-renderer', 'ytd-companion-slot-renderer', 'ytd-action-companion-ad-renderer',
      'ytd-primetime-promo-renderer', '#companion', '.ytp-ad-module', '.ytp-ad-overlay-container',
      '.ytp-ad-overlay-slot', '.ytp-ad-image-overlay', '.video-ads', 'ytm-promoted-sparkles-web-renderer',
      'ytm-promoted-video-renderer', 'ytm-companion-ad-renderer', 'ytm-display-ad-renderer',
      'ytm-in-feed-ad-layout-renderer', 'ytm-statement-banner-renderer', 'ytm-banner-promo-renderer',
      'ytm-mealbar-promo-renderer', 'ytm-paid-content-overlay-renderer', 'ad-slot-renderer'
    ]);
  }
  if (EXEMPT) COSMETIC = [];
  var styleEl = null;
  function installStyle() {
    try {
      if (!COSMETIC.length) return;
      if (styleEl && styleEl.isConnected) return;
      var parent = document.head || document.documentElement;
      if (!parent) return;
      styleEl = document.createElement('style');
      styleEl.setAttribute('data-swift-cosmetic', '1');
      styleEl.textContent = COSMETIC.join(',\n') + '{display:none !important;}';
      parent.appendChild(styleEl);
    } catch (e) {}
  }
  // Selectors from the downloaded filter lists (per-site), applied rule by rule so one bad selector cannot void the rest.
  var nativeSheet = null, nativeSels = '';
  try { if (TOP && !EXEMPT && window.SwiftFilter && window.SwiftFilter.cosmeticStartup) nativeSels = window.SwiftFilter.cosmeticStartup(H) || ''; } catch (e) {}
  function installNative() {
    if (!nativeSels) return;
    try {
      if (nativeSheet && document.adoptedStyleSheets.indexOf(nativeSheet) >= 0) return;
      if (!nativeSheet) {
        nativeSheet = new CSSStyleSheet();
        // The CSS parser drops an invalid rule on its own, so one parse handles the whole list.
        nativeSheet.replaceSync(nativeSels.split('\n').join('{display:none !important}\n') + '{display:none !important}');
      }
      document.adoptedStyleSheets = document.adoptedStyleSheets.concat([nativeSheet]);
    } catch (e) {}
  }
  // Generic list rules are sent per page, only for the classes/ids that exist on it (keeps the stylesheet small and fast).
  var tokSheet = null, tokSels = [], seenTok = {};
  function sendTokens() {
    if (!TOP || EXEMPT || !window.SwiftFilter || !window.SwiftFilter.cosmeticFor) return;
    try {
      var toks = [], els = document.querySelectorAll('[class],[id]');
      for (var i = 0; i < els.length && toks.length < 4000; i++) {
        var e = els[i];
        if (e.id && typeof e.id === 'string') { var k = '#' + e.id; if (!seenTok[k]) { seenTok[k] = 1; toks.push(k); } }
        var cl = e.classList;
        if (cl) for (var j = 0; j < cl.length; j++) { var c = '.' + cl[j]; if (!seenTok[c]) { seenTok[c] = 1; toks.push(c); } }
      }
      if (!toks.length) return;
      var res = window.SwiftFilter.cosmeticFor(H, JSON.stringify(toks)) || '';
      if (!res) return;
      var add = res.split('\n');
      tokSels = tokSels.concat(add);
      if (!tokSheet) { tokSheet = new CSSStyleSheet(); document.adoptedStyleSheets = document.adoptedStyleSheets.concat([tokSheet]); }
      tokSheet.replaceSync(tokSels.join('{display:none !important}\n') + '{display:none !important}');
    } catch (e) {}
  }
  if (TOP) {
    try { document.addEventListener('DOMContentLoaded', sendTokens); } catch (e) {}
    try { window.addEventListener('load', function () { setTimeout(sendTokens, 700); setTimeout(sendTokens, 3500); }); } catch (e) {}
  }
  installNative();
  installStyle();
  try { document.addEventListener('readystatechange', function () { installStyle(); installNative(); }); } catch (e) {}
  try { document.addEventListener('DOMContentLoaded', installStyle); } catch (e) {}
  // Re-check occasionally in case a page wipes <head>; a single boolean read, no DOM scan.
  if (TOP) { try { setInterval(function () { installStyle(); installNative(); }, 2500); } catch (e) {} }

  // ---------- 5b. collapse the empty space left by hidden/blocked ads ----------
  function adHidden(e) { try { return getComputedStyle(e).display === 'none'; } catch (x) { return false; } }
  function hasVisibleContent(p) {
    try {
      if ((p.innerText || '').trim().length > 0) return true;
      var m = p.querySelectorAll('img,video,canvas,svg,input,button,select,textarea,picture,audio,iframe');
      for (var i = 0; i < m.length; i++) {
        if (m[i].offsetHeight > 2 && m[i].offsetWidth > 2 && !adHidden(m[i])) return true;
      }
    } catch (x) { return true; }
    return false;
  }
  function collapseAround(el) {
    var p = el.parentElement, n = 0;
    while (p && p !== document.body && p !== document.documentElement && n < 3) {
      if (hasVisibleContent(p)) break;
      try { p.style.setProperty('display', 'none', 'important'); } catch (x) {}
      p = p.parentElement; n++;
    }
  }
  var collapsePasses = 0;
  // One bad selector makes querySelectorAll throw for the whole group, so split and retry on failure.
  function queryGroup(sels, out) {
    if (!sels.length) return;
    try {
      var els = document.querySelectorAll(sels.join(','));
      for (var j = 0; j < els.length && out.length < 120; j++) out.push(els[j]);
    } catch (x) {
      if (sels.length === 1) return;
      var mid = sels.length >> 1;
      queryGroup(sels.slice(0, mid), out);
      queryGroup(sels.slice(mid), out);
    }
  }
  function collapsePass() {
    if (!TOP || EXEMPT || collapsePasses++ > 3) return;
    try {
      var sels = COSMETIC.slice();
      if (nativeSels) sels = sels.concat(nativeSels.split('\n').slice(0, 800));
      if (tokSels.length) sels = sels.concat(tokSels.slice(0, 1200));
      var found = [];
      for (var i = 0; i < sels.length; i += 150) queryGroup(sels.slice(i, i + 150), found);
      for (var k = 0; k < found.length; k++) collapseAround(found[k]);
    } catch (x) {}
  }
  if (TOP) {
    try { window.addEventListener('load', function () { setTimeout(collapsePass, 800); setTimeout(collapsePass, 3500); }); } catch (e) {}
  }

  // ---------- 6. YouTube ad data ----------
  if (IS_YT && location.pathname.indexOf('/shorts/') === -1) {
    var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
    var strip = function (o) {
      if (!o || typeof o !== 'object') return o;
      try {
        for (var i = 0; i < KEYS.length; i++) if (KEYS[i] in o) delete o[KEYS[i]];
        if (o.playerResponse) strip(o.playerResponse);
        if (o.response && o.response.playerResponse) strip(o.response.playerResponse);
      } catch (e) {}
      return o;
    };
    try {
      var jp = JSON.parse;
      JSON.parse = function () {
        var r = jp.apply(this, arguments);
        if (r && typeof r === 'object' && (r.adPlacements || r.playerAds || r.playerResponse)) strip(r);
        return r;
      };
    } catch (e) {}
    try {
      var rj = Response.prototype.json;
      Response.prototype.json = function () { return rj.apply(this, arguments).then(strip); };
    } catch (e) {}
  }

  // ---------- 6b. late-added ads: ad-host frames/links/images and click-stealing overlays ----------
  // Looks only at nodes that were just added (never rescans the page), in small time-boxed batches.
  (function () {
    if (!HOSTS) return;
    var queue = [], scheduled = false;
    function hide(e) { try { e.style.setProperty('display', 'none', 'important'); } catch (x) {} }
    function checkEl(e) {
      var u = e.src || e.href;
      if (u && adHostUrl(u)) { hide(e); collapseAround(e); }
    }
    var STRONG = /(clickadu|disclosure of advertising|close ad\b|advertisement\s*$)/i;
    function overlayCheck(n) {
      if (!TOP || n.nodeType !== 1 || !document.body) return;
      var cs = getComputedStyle(n);
      if (cs.position !== 'fixed' && cs.position !== 'absolute') return;
      var r = n.getBoundingClientRect();
      if (r.width < innerWidth * 0.6 || r.height < innerHeight * 0.5) return;
      if (n.querySelector('input,textarea,select,video')) return;
      var txt = '';
      try { txt = (n.innerText || '') + ' ' + (n.shadowRoot ? n.shadowRoot.textContent : ''); } catch (x) {}
      txt = txt.slice(0, 1500);
      var frames = n.querySelectorAll('iframe');
      var adFrame = false;
      for (var i = 0; i < frames.length; i++) { if (frames[i].src && adHostUrl(frames[i].src)) adFrame = true; }
      if (!(STRONG.test(txt) || adFrame)) return;
      try { n.remove(); } catch (x) { hide(n); }
      try {
        var b = document.body, d = document.documentElement;
        if (b && b.style.overflow === 'hidden') b.style.removeProperty('overflow');
        if (d && d.style.overflow === 'hidden') d.style.removeProperty('overflow');
      } catch (x) {}
    }
    function sweep(n) {
      if (n.nodeType !== 1) return;
      var t = n.tagName;
      if (t === 'IFRAME' || t === 'IMG' || t === 'A') checkEl(n);
      if (n.querySelectorAll) {
        var l = n.querySelectorAll('iframe[src],a[href],img[src]');
        for (var i = 0; i < l.length && i < 80; i++) checkEl(l[i]);
      }
      if (n.parentNode === document.body || n.parentNode === document.documentElement) overlayCheck(n);
      else if (n.firstElementChild && n.parentNode && (n.parentNode.parentNode === document.body)) overlayCheck(n);
    }
    function run() {
      scheduled = false;
      var t0 = Date.now();
      while (queue.length && Date.now() - t0 < 8) { try { sweep(queue.shift()); } catch (e) {} }
      if (queue.length) schedule();
    }
    function schedule() {
      if (scheduled) return;
      scheduled = true;
      try { (window.requestAnimationFrame || window.setTimeout)(run, 50); } catch (e) { setTimeout(run, 50); }
    }
    function start() {
      try {
        var root = document.documentElement;
        if (!root) { setTimeout(start, 20); return; }
        new MutationObserver(function (ms) {
          for (var i = 0; i < ms.length; i++) {
            var a = ms[i].addedNodes;
            for (var j = 0; j < a.length; j++) if (queue.length < 400) queue.push(a[j]);
          }
          schedule();
        }).observe(root, { childList: true, subtree: true });
        // The observer may start after the first nodes were parsed: look at what is already there once.
        queue.push(root); schedule();
      } catch (e) {}
    }
    start();
  })();

  // ---------- 7. fetch / XHR shim for known ad hosts ----------
  (function () {
    var hosts = HOSTS;
    if (!hosts) return;
    var SAFE = /(^|\.)(cloudflare\.com|hcaptcha\.com|google\.com|gstatic\.com|jsdelivr\.net)${'$'}/;
    function isAdUrl(u) {
      try {
        var s = String(u);
        var host = new URL(s, location.href).hostname.toLowerCase();
        if (!host || SAFE.test(host)) return false;
        var h = host;
        while (h) {
          if (hosts[h]) return true;
          var dot = h.indexOf('.');
          if (dot < 0) return false;
          h = h.substring(dot + 1);
        }
      } catch (e) {}
      return false;
    }
    try {
      var realFetch = window.fetch;
      window.fetch = function (input) {
        var url = (typeof input === 'string') ? input : (input && input.url) || '';
        if (url && isAdUrl(url)) return Promise.reject(new TypeError('Failed to fetch'));
        return realFetch.apply(this, arguments);
      };
    } catch (e) {}
    try {
      var XHR = window.XMLHttpRequest;
      var xOpen = XHR.prototype.open, xSend = XHR.prototype.send;
      XHR.prototype.open = function (m, url) {
        try { this.__swiftBlocked = isAdUrl(url); } catch (e) { this.__swiftBlocked = false; }
        return xOpen.apply(this, arguments);
      };
      XHR.prototype.send = function () {
        if (this.__swiftBlocked) {
          var x = this;
          setTimeout(function () {
            try {
              Object.defineProperty(x, 'readyState', { value: 4, configurable: true });
              Object.defineProperty(x, 'status', { value: 0, configurable: true });
              x.dispatchEvent(new Event('readystatechange'));
              x.dispatchEvent(new ProgressEvent('error'));
              x.dispatchEvent(new ProgressEvent('loadend'));
            } catch (e) {}
          }, 0);
          return;
        }
        return xSend.apply(this, arguments);
      };
    } catch (e) {}
  })();
})();
"""
