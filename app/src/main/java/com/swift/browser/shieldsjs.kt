package com.swift.browser

// Page-side shields. Kept deliberately light: one stylesheet for cosmetic hiding (no DOM rescans),
// tap reporting for the navigation guard, popup/dialog/history abuse limits, and the fetch/XHR shim.
// NOTE: Kotlin raw string — never write a bare dollar sign in here, use ${'$'}.
const val SHIELDS_JS = """
(function () {
  // Captcha / bot-check frames inspect the browser's own functions for tampering: leave them completely untouched.
  try {
    var HN = (location.hostname || '').toLowerCase();
    if (/(^|\.)(challenges\.cloudflare\.com|hcaptcha\.com|recaptcha\.net|turnstile\.com)${'$'}/.test(HN) ||
        (HN === 'www.google.com' && location.pathname.indexOf('/recaptcha') === 0)) return;
  } catch (e) {}
  if (window.__swiftShields) return;
  window.__swiftShields = true;
  // Our patches must not be visible to bot checks (Cloudflare, Turnstile ...): every function we replace keeps the
  // original's name/length and prints as "[native code]". Natives are recorded here, masked once all patches exist.
  var NATIVEMAP = new WeakMap(), NATIVELIST = [];
  try {
    var origFnToString = Function.prototype.toString;
    var fakeFnToString = function toString() { var o = NATIVEMAP.get(this); return origFnToString.call(o || this); };
    NATIVEMAP.set(fakeFnToString, origFnToString);
    Function.prototype.toString = fakeFnToString;
  } catch (e) {}
  function snap(obj, names) {
    names.forEach(function (n) {
      try {
        var d = Object.getOwnPropertyDescriptor(obj, n);
        if (!d) return;
        if (d.get) NATIVELIST.push([obj, n, d.get, 'g']);
        else if (typeof d.value === 'function') NATIVELIST.push([obj, n, d.value, 'v']);
      } catch (e) {}
    });
  }
  function maskOne(fake, real) {
    try {
      if (typeof fake !== 'function' || fake === real) return;
      NATIVEMAP.set(fake, real);
      Object.defineProperty(fake, 'name', { value: real.name, configurable: true });
      Object.defineProperty(fake, 'length', { value: real.length, configurable: true });
    } catch (e) {}
  }
  function maskAll() {
    NATIVELIST.forEach(function (r) {
      try {
        var d = Object.getOwnPropertyDescriptor(r[0], r[1]);
        if (d) maskOne(r[3] === 'g' ? d.get : d.value, r[2]);
      } catch (e) {}
    });
    try { maskOne(performance.now, Performance.prototype.now); } catch (e) {}
  }
  try {
    snap(window, ['open', 'alert', 'confirm', 'prompt', 'fetch', 'setTimeout', 'setInterval', 'clearTimeout', 'clearInterval', 'getComputedStyle']);
    snap(History.prototype, ['pushState', 'replaceState']);
    snap(XMLHttpRequest.prototype, ['open', 'send']);
    snap(Element.prototype, ['getBoundingClientRect', 'clientHeight', 'clientWidth']);
    snap(HTMLElement.prototype, ['offsetHeight', 'offsetWidth', 'offsetParent']);
    snap(Date, ['now']);
    snap(JSON, ['parse']);
    snap(Response.prototype, ['json']);
  } catch (e) {}
  // Privacy surface (what Brave reports): opt-out signal on, no battery API, no Private State Tokens.
  try { Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', { get: function () { return true; }, configurable: true }); } catch (e) {}
  try { if ('getBattery' in Navigator.prototype) delete Navigator.prototype.getBattery; } catch (e) {}
  try { if ('hasPrivateToken' in Document.prototype) delete Document.prototype.hasPrivateToken; } catch (e) {}
  var H = (location.hostname || '').toLowerCase();
  var IS_YT = /(^|\.)youtube(-nocookie)?\.com${'$'}/.test(H);
  var NAV = window.SwiftNav;
  var TOP = (window === window.top);
  var HOSTS = null;
  var EXEMPT = false;
  var SENS = /(^|[\/._?&=-])(checkout|payments?|pay|order|orders|cart|basket|billing|bank|netbanking|wallet|upi|login|signin|sign-in|auth|oauth|account|secure|3ds|otp|verify|transaction|invoice|subscribe)(${'$'}|[\/._?&=-])/i;
  function sensitive() { try { return SENS.test(location.pathname + location.search) || SENS.test(location.hostname.split('.')[0] + '/'); } catch (e) { return false; } }
  try { EXEMPT = !!(window.SwiftFilter && window.SwiftFilter.exempt && window.SwiftFilter.exempt(H)); } catch (e) {}
  // Checkout / payment pages: no page-side blocking at all (orders must not fail because of a blocked script).
  try { if (/(^|[\/?&=_.-])(checkout|payment|payments|pay|billing|cart|order|orders|upi|transaction|wallet|purchase)([\/?&=_.#-]|${'$'})/i.test(location.pathname + '?' + location.search)) EXEMPT = true; } catch (e) {}
  try { if (!EXEMPT && window.SwiftFilter && window.SwiftFilter.getBlockedHosts) HOSTS = JSON.parse(window.SwiftFilter.getBlockedHosts()); } catch (e) {}
  function adHostUrl(u) {
    if (!HOSTS || sensitive()) return false;
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
  function kindOf(el) {
    var n = 0;
    while (el && el.nodeType === 1 && n++ < 6) {
      var t = el.tagName;
      if (t === 'BUTTON' || t === 'INPUT' || t === 'SELECT' || t === 'LABEL' || t === 'SUMMARY') return true;
      try {
        var r = el.getAttribute('role');
        if (r === 'button' || r === 'link' || r === 'menuitem' || r === 'tab') return true;
        if (el.hasAttribute('onclick') || el.hasAttribute('data-href')) return true;
        var tx = (el.innerText || '').trim();
        if (tx.length > 0 && tx.length < 60 && getComputedStyle(el).cursor === 'pointer') return true;
      } catch (e) {}
      el = el.parentElement;
    }
    return false;
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
      if (NAV && NAV.tap) NAV.tap(href, kindOf(e.target));
    } catch (err) {}
  }
  ['pointerdown', 'touchstart', 'click'].forEach(function (t) {
    try { window.addEventListener(t, reportTap, true); } catch (e) {}
  });

  // Tell the native pull-to-refresh whether the finger is inside an inner scroller that is not at its top
  // (modal, side panel): scrolling back up there must not trigger a page refresh.
  function innerScrolledUp(el) {
    var n = 0;
    while (el && el.nodeType === 1 && el !== document.body && el !== document.documentElement && n++ < 25) {
      try {
        if (el.scrollTop > 0 && el.scrollHeight > el.clientHeight + 1) {
          var oy = getComputedStyle(el).overflowY;
          if (oy === 'auto' || oy === 'scroll' || oy === 'overlay') return true;
        }
      } catch (e) {}
      el = el.parentElement;
    }
    return false;
  }
  try {
    window.addEventListener('touchstart', function (e) {
      try { if (NAV && NAV.scrollState) NAV.scrollState(innerScrolledUp(e.target)); } catch (x) {}
    }, { capture: true, passive: true });
  } catch (e) {}

  // ---------- 2. invisible click-catcher overlays ----------
  // A transparent layer covering most of the screen that exists only to steal the first tap.
  function looksLikeCatcher(el) {
    if (!el || el === document.body || el === document.documentElement || IS_YT) return false;
    // Only empty div/a/span layers or ad-host iframes can be click-catchers. Never images/media: lazy-loaded
    // thumbnails are also absolute-positioned and fully transparent until they fade in.
    var tn = el.tagName;
    if (tn === 'IFRAME') { if (!adHostUrl(el.src)) return false; }
    else if (tn !== 'DIV' && tn !== 'A' && tn !== 'SPAN') return false;
    else if (el.childElementCount > 0) return false;
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

  function reportRoute() { try { if (TOP && NAV && NAV.route) NAV.route(location.href); } catch (e) {} }
  try {
    var winStart = Date.now(), calls = 0;
    ['pushState', 'replaceState'].forEach(function (n) {
      var real = history[n];
      history[n] = function () {
        if (Date.now() - winStart > 10000) { winStart = Date.now(); calls = 0; }
        if (++calls > 20) return;
        var r = real.apply(this, arguments);
        reportRoute();
        return r;
      };
    });
    window.addEventListener('popstate', reportRoute);
    window.addEventListener('hashchange', reportRoute);
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
  // Class names that only ever mean "this is an ad slot" (exact class tokens, so nothing else matches).
  COSMETIC = COSMETIC.concat([
    'ins.adsbygoogle', '.adsbygoogle', '[class~="ad-slot"]', '[class~="ad-unit"]', '[class~="adunit"]',
    '[class~="ad-banner"]', '[class~="ad-container"]', '[class~="ad-wrapper"]', '[class~="ad-placement"]',
    '[class~="advertisement"]', '[class~="sticky-ad"]', '[class~="ad-sticky"]', '[class~="anchor-ad"]',
    '[class~="ad-anchor"]', '[class~="ad-preroll"]', '[class~="video-ad"]', '[class~="text-ad"]',
    '[class~="textad"]', '[class~="google-ad"]', '[id^="google_ads_"]', '[id^="div-gpt-ad"]',
    '[data-ad-slot]', '[data-ad-client]'
  ]);
  if (IS_YT) {
    COSMETIC = COSMETIC.concat([
      '.ytp-ad-player-overlay', '.ytp-ad-player-overlay-layout', '.ytp-ad-persistent-progress-bar-container',
      'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]'
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
    if (!TOP || EXEMPT || IS_YT || !window.SwiftFilter || !window.SwiftFilter.cosmeticFor) return;
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
    // The watch page ships its first player data inline (not through JSON.parse), so strip it on assignment.
    try {
      var held;
      Object.defineProperty(window, 'ytInitialPlayerResponse', {
        configurable: true,
        get: function () { return held; },
        set: function (v) { held = strip(v); }
      });
    } catch (e) {}
    // Whatever still gets through (ads stitched into the video itself): skip to the end and press Skip.
    (function () {
      var wasMuted = null;
      function tick() {
        try {
          var p = document.querySelector('.html5-video-player');
          if (!p) return;
          var v = p.querySelector('video.html5-main-video') || p.querySelector('video');
          var ad = p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting');
          if (ad && v) {
            if (wasMuted === null) wasMuted = !!v.muted;
            v.muted = true;
            if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.2) v.currentTime = v.duration - 0.05;
            var b = p.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-slot button');
            if (b) b.click();
          } else if (wasMuted !== null && v) {
            v.muted = wasMuted;
            wasMuted = null;
          }
        } catch (e) {}
      }
      setInterval(tick, 350);
    })();
  }

  // ---------- 6b. late-added ads: ad-host frames/links/images and click-stealing overlays ----------
  // Looks only at nodes that were just added (never rescans the page), in small time-boxed batches.
  (function () {
    if (!HOSTS) return;
    var queue = [], scheduled = false, tokRuns = 0, tokTimer = 0;
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
      if (IS_YT) return; // YouTube mutates its DOM constantly and is covered by its own rules
      try {
        var root = document.documentElement;
        if (!root) { setTimeout(start, 20); return; }
        new MutationObserver(function (ms) {
          for (var i = 0; i < ms.length; i++) {
            var a = ms[i].addedNodes;
            for (var j = 0; j < a.length; j++) if (queue.length < 400) queue.push(a[j]);
          }
          schedule();
          if (TOP && tokRuns < 12 && !tokTimer) { tokTimer = setTimeout(function () { tokTimer = 0; tokRuns++; sendTokens(); }, 1500); }
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
        if (!host || SAFE.test(host) || sensitive()) return false;
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
  // ---------- 8. anti-adblock countermeasures ----------
  // Sites detect a blocker by (a) planting an empty "bait" element and checking whether it got hidden, and
  // (b) showing a "please disable AdBlock" wall. Bait elements are answered as if they were visible (they stay hidden
  // on screen), and walls are removed. Only empty, ad-named elements and ad-blocker-worded overlays are touched.
  (function () {
    if (EXEMPT) return;
    var BAITCLS = /^(ads?|adsbox|adbox|ad[-_]?banner|banner[-_]?ads?|adsbygoogle|ad[-_]?slot|ad[-_]?unit|ad[-_]?placement|ad[-_]?container|ad[-_]?wrapper|ad[-_]?frame|ad[-_]?box|ad[-_]?test|adblock[-_]?test|text[-_]?ads?|text[-_]?ad[-_]?links?|textad|pub[-_]?\d+x\d+m?|advert(isement)?s?|google[-_]?ads?|banner[-_]?ad|sponsor(ed)?[-_]?ads?|ad[-_]?top|ad[-_]?bottom|ad[-_]?header|ad[-_]?footer|ad[-_]?sidebar|ad[-_]?leaderboard|ad[-_]?right|ad[-_]?left|bait)${'$'}/i;
    function isBait(el) {
      try {
        if (!el || el.nodeType !== 1 || el.childElementCount > 0) return false;
        if ((el.textContent || '').length > 0) return false;
        if (el.id && BAITCLS.test(el.id)) return true;
        var cl = el.classList;
        if (cl) for (var i = 0; i < cl.length; i++) if (BAITCLS.test(cl[i])) return true;
      } catch (e) {}
      return false;
    }
    function declared(el, prop) { try { return Math.max(1, parseFloat(el.style[prop]) || 0); } catch (e) { return 1; } }
    function wrapGetter(proto, name, fake) {
      try {
        var d = Object.getOwnPropertyDescriptor(proto, name);
        if (!d || !d.get) return;
        var g = d.get;
        Object.defineProperty(proto, name, {
          configurable: true, enumerable: d.enumerable,
          get: function () {
            var v = g.call(this);
            if ((v === 0 || v === null) && isBait(this)) return fake(this);
            return v;
          }
        });
      } catch (e) {}
    }
    wrapGetter(HTMLElement.prototype, 'offsetHeight', function (el) { return declared(el, 'height'); });
    wrapGetter(HTMLElement.prototype, 'offsetWidth', function (el) { return declared(el, 'width'); });
    wrapGetter(Element.prototype, 'clientHeight', function (el) { return declared(el, 'height'); });
    wrapGetter(Element.prototype, 'clientWidth', function (el) { return declared(el, 'width'); });
    wrapGetter(HTMLElement.prototype, 'offsetParent', function () { return document.body; });
    try {
      var realRect = Element.prototype.getBoundingClientRect;
      Element.prototype.getBoundingClientRect = function () {
        var r = realRect.call(this);
        if ((r.width === 0 || r.height === 0) && isBait(this)) return new DOMRect(r.x, r.y, r.width || declared(this, 'width'), r.height || declared(this, 'height'));
        return r;
      };
    } catch (e) {}
    try {
      var realGCS = window.getComputedStyle;
      window.getComputedStyle = function (el) {
        var cs = realGCS.apply(window, arguments);
        try {
          if (el && el.nodeType === 1 && (cs.display === 'none' || cs.visibility === 'hidden') && isBait(el)) {
            return new Proxy(cs, {
              get: function (t, k) {
                if (k === 'display') return 'block';
                if (k === 'visibility') return 'visible';
                if (k === 'getPropertyValue') {
                  return function (n) {
                    n = String(n).toLowerCase();
                    if (n === 'display') return 'block';
                    if (n === 'visibility') return 'visible';
                    return t.getPropertyValue(n);
                  };
                }
                var v = t[k];
                return typeof v === 'function' ? v.bind(t) : v;
              }
            });
          }
        } catch (e) {}
        return cs;
      };
    } catch (e) {}

    // "Please disable your ad blocker" walls.
    if (!TOP || IS_YT) return;
    var STRONGWALL = /ad[\s-]?block|ads?[\s-]?blocker|anti[\s-]?adblock/i;
    var WEAKWALL = /white-?list/i, ADWORD = /\bads?\b|advertis/i;
    function wallText(n) {
      var t = '';
      try { t = (n.innerText || '') + ' ' + (n.shadowRoot ? n.shadowRoot.textContent : ''); } catch (x) {}
      return t.slice(0, 1600);
    }
    function isWall(n) {
      try {
        if (!n || n.nodeType !== 1 || n === document.body || n === document.documentElement) return false;
        var cs = getComputedStyle(n);
        if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
        if (cs.position === 'absolute' && !(parseInt(cs.zIndex, 10) > 50)) return false;
        var r = n.getBoundingClientRect();
        if (r.width < innerWidth * 0.5 || r.height < innerHeight * 0.2) return false;
        if (n.scrollHeight > n.clientHeight * 1.6 + 40) return false;
        if (n.querySelector('input,textarea,select,video,canvas,article,main,form,nav,header')) return false;
        var t = wallText(n);
        if (t.length < 15 || t.length > 900) return false;
        return STRONGWALL.test(t) || (WEAKWALL.test(t) && ADWORD.test(t));
      } catch (e) { return false; }
    }
    function coversScreen(n) {
      try {
        var cs = getComputedStyle(n);
        if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
        var r = n.getBoundingClientRect();
        return r.width >= innerWidth * 0.9 && r.height >= innerHeight * 0.9;
      } catch (e) { return false; }
    }
    function unlock() {
      try {
        [document.documentElement, document.body].forEach(function (el) {
          if (!el) return;
          var cs = getComputedStyle(el);
          if (cs.overflow === 'hidden' || cs.overflowY === 'hidden') el.style.setProperty('overflow', 'auto', 'important');
          if (cs.pointerEvents === 'none') el.style.setProperty('pointer-events', 'auto', 'important');
        });
        var kids = document.body ? document.body.children : [];
        for (var i = 0; i < kids.length && i < 80; i++) {
          var f = getComputedStyle(kids[i]).filter;
          if (f && f.indexOf('blur') >= 0) kids[i].style.setProperty('filter', 'none', 'important');
        }
      } catch (e) {}
    }
    var wallRuns = 0, removedAny = false;
    function wallPass() {
      if (!document.body || wallRuns++ > 60) return;
      try {
        var pts = [[0.5, 0.5], [0.5, 0.3], [0.5, 0.7], [0.5, 0.15], [0.5, 0.85]];
        var seen = [], found = null;
        for (var i = 0; i < pts.length && !found; i++) {
          var els = document.elementsFromPoint(innerWidth * pts[i][0], innerHeight * pts[i][1]);
          for (var j = 0; j < els.length && j < 12; j++) {
            var n = els[j];
            while (n && n !== document.body && n !== document.documentElement) {
              if (isWall(n)) { found = n; break; }
              n = n.parentElement;
            }
            if (found) break;
          }
        }
        if (!found) return;
        // climb to the full-screen wrapper (backdrop) when the wall sits inside one
        var top = found;
        while (top.parentElement && top.parentElement !== document.body && top.parentElement !== document.documentElement &&
               coversScreen(top.parentElement) && wallText(top.parentElement).length < 1600) top = top.parentElement;
        try { top.remove(); } catch (x) { top.style.setProperty('display', 'none', 'important'); }
        removedAny = true;
        // leftover dimming layers: empty, screen-sized, fixed
        var again = document.elementsFromPoint(innerWidth / 2, innerHeight / 2);
        for (var k = 0; k < again.length && k < 4; k++) {
          var b = again[k];
          if (b !== document.body && b !== document.documentElement && coversScreen(b) &&
              (b.innerText || '').trim().length < 5 && !b.querySelector('img,video,canvas,input,iframe')) {
            try { b.remove(); } catch (x) { b.style.setProperty('display', 'none', 'important'); }
          }
        }
        unlock();
      } catch (e) {}
    }
    function startWalls() {
      wallPass();
      [400, 1200, 2500, 5000, 9000].forEach(function (ms) { setTimeout(wallPass, ms); });
      try {
        setInterval(function () { if (removedAny || wallRuns < 40) wallPass(); }, 3000);
      } catch (e) {}
      try {
        var debounce = 0;
        new MutationObserver(function () {
          if (debounce || wallRuns > 60) return;
          debounce = setTimeout(function () { debounce = 0; wallPass(); }, 350);
        }).observe(document.body, { childList: true });
      } catch (e) {}
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startWalls);
    else startWalls();
  })();
  // ---------- 9. link-shortener pages: ads stay blocked, countdowns are skipped, "next" buttons are shown ----------
  // NAV.flow() still lets tap-started steps through the navigation guard. Sites that confirm the ad task or the wait on
  // their own server cannot be skipped from the browser.
  (function () {
    if (!TOP || IS_YT) return;
    var FLOW = /(click\s+(on\s+)?(any|an|the|one)\b[^.]{0,25}\b(images?|ads?|banners?)|then\s+(go\s+)?back\s+and\s+wait|wait\s+for\s+(the\s+)?(link|timer)|get\s+link\b|generate\s+link|your\s+link\s+is\s+(ready|being)|(click|tap)\s+(here\s+)?to\s+continue\s+(to\s+)?(link|download)|scroll\s+down\s+(to\s+)?(continue|get))/i;
    var STRONGFLOW = /(then\s+(go\s+)?back\s+and\s+wait|wait\s+for\s+(the\s+)?(link|timer)|get\s+link\b|generate\s+link|your\s+link\s+is\s+(ready|being)|click\s+(on\s+)?(any|an|the)\b[^.]{0,25}\b(images?|ads?|banners?)\b[^.]{0,40}\b(link|continue|wait|back))/i;
    var SHORTENERS = /(^|\.)(ouo\.io|ouo\.press|adf\.ly|linkvertise\.com|shrinkme\.io|shrinkearn\.com|exe\.io|exey\.io|fc\.lc|cuty\.io|droplink\.co|gplinks\.co|gplinks\.in|shareus\.io|tpi\.li|tnlink\.in|linkshortify\.com|shrtfly\.com|ez4short\.com|arolinks\.com|urlspay\.in|kingurl\.in|indianshortner\.com|clk\.sh|clicksfly\.com|adrinolinks\.in|bc\.vc|shorte\.st|za\.gl|cpmlink\.net|link1s\.com|xpshort\.com|linksly\.co|lksfy\.com|shortingly\.in|vplink\.in|krownlinks\.me|adshrink\.it|ouo\.today|short2url\.in|mdiskshortner\.link|earnow\.online|urlsfly\.com|shrinke\.me|linkpays\.in|tglink\.in)${'$'}/i;
    var SPEED = 25, active = false, t0real = 0, t0warp = 0;

    // ---- clock + timers: recorded from page start, only sped up once the page is known to be a link gate ----
    var rSI = window.setInterval, rCI = window.clearInterval, rST = window.setTimeout, rCT = window.clearTimeout;
    var rNow = Date.now, rPerf = performance.now.bind(performance);
    var live = {}, alias = {};
    function fast(d) { return Math.max(30, Math.round((+d || 0) / SPEED)); }
    try {
      window.setInterval = function (fn, d) {
        var args = [].slice.call(arguments, 2), id;
        var delay = active && d >= 400 ? fast(d) : d;
        id = rSI.apply(window, [fn, delay].concat(args));
        if (d >= 400) live[id] = { t: 'i', fn: fn, d: d, a: args };
        return id;
      };
      window.setTimeout = function (fn, d) {
        var args = [].slice.call(arguments, 2), id;
        var delay = active && d >= 400 ? fast(d) : d;
        var wrapped = fn;
        if (d >= 400 && typeof fn === 'function') wrapped = function () { delete live[id]; return fn.apply(this, arguments); };
        id = rST.apply(window, [wrapped, delay].concat(args));
        if (d >= 400) live[id] = { t: 't', fn: wrapped, d: d, a: args };
        return id;
      };
      window.clearInterval = function (id) { var r = alias[id]; delete live[id]; if (r != null) { delete live[r]; return rCI.call(window, r); } return rCI.call(window, id); };
      window.clearTimeout = function (id) { var r = alias[id]; delete live[id]; if (r != null) { delete live[r]; return rCT.call(window, r); } return rCT.call(window, id); };
    } catch (e) {}
    // Gate pages start their countdown when the person comes back to the tab after clicking an ad (blur/focus,
    // visibilitychange). Ads stay blocked and nothing is opened or clicked: only those page events are sent.
    function returnFromAd() {
      try {
        var hid = false;
        Object.defineProperty(document, 'hidden', { configurable: true, get: function () { return hid; } });
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: function () { return hid ? 'hidden' : 'visible'; } });
        var swap = function (h) {
          hid = h;
          try { document.dispatchEvent(new Event('visibilitychange')); window.dispatchEvent(new Event(h ? 'blur' : 'focus')); } catch (x) {}
        };
        [1500, 6000, 14000].forEach(function (ms) {
          rST.call(window, function () { swap(true); rST.call(window, function () { swap(false); }, 700); }, ms);
        });
      } catch (e) {}
    }
    var t0perf = 0, t0perfReal = 0;
    function speedUp() {
      if (active) return;
      t0real = rNow.call(Date); t0warp = t0real; t0perfReal = rPerf(); t0perf = t0perfReal;
      active = true;
      try {
        Date.now = function () { return t0warp + (rNow.call(Date) - t0real) * SPEED; };
        performance.now = function () { return t0perf + (rPerf() - t0perfReal) * SPEED; };
        maskAll();
      } catch (e) {}
      returnFromAd();
      Object.keys(live).forEach(function (k) {
        var o = live[k]; if (!o) return;
        try {
          if (o.t === 'i') { rCI.call(window, +k); alias[k] = rSI.apply(window, [o.fn, fast(o.d)].concat(o.a)); }
          else { rCT.call(window, +k); alias[k] = rST.apply(window, [o.fn, fast(o.d)].concat(o.a)); }
        } catch (e) {}
      });
    }

    // ---- buttons that the countdown would unlock ----
    var WANT = /^(get\s*(the\s+)?(download\s+)?link|continue(\s+to\s+(link|download|website))?|next(\s+step|\s+page)?|download(\s+now)?|proceed|click\s+here(\s+to\s+continue)?|go\s+to\s+link|open\s+link|unlock\s+link|skip\s+ad|create\s+link)\b/i;
    var HIDEOK = /^(get\s*(the\s+)?(download\s+)?link|continue|next|download|proceed|go\s+to\s+link|open\s+link)/i;
    function reveal() {
      try {
        var els = document.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]');
        for (var i = 0; i < els.length && i < 400; i++) {
          var e = els[i], txt = ((e.innerText || e.value || '') + '').trim();
          if (!txt || txt.length > 40 || !WANT.test(txt)) continue;
          if (e.disabled) e.disabled = false;
          e.removeAttribute('disabled');
          if (e.classList) e.classList.remove('disabled');
          if (e.getAttribute('aria-disabled') === 'true') e.setAttribute('aria-disabled', 'false');
          if (HIDEOK.test(txt)) {
            var n = e, k = 0;
            while (n && n !== document.body && k++ < 4) {
              var cs = getComputedStyle(n);
              if (cs.display === 'none') n.style.setProperty('display', n.tagName === 'A' || n.tagName === 'BUTTON' ? 'inline-block' : 'block', 'important');
              if (n.hidden) n.hidden = false;
              n = n.parentElement;
            }
          }
        }
      } catch (e) {}
    }

    var sent = 0, gated = false, revealUntil = 0;
    function look() {
      if (sent > 12) return;
      try {
        var b = document.body;
        if (!b) return;
        var t = (b.innerText || '').slice(0, 4000);
        var short = SHORTENERS.test(H);
        if (FLOW.test(t) || short) { sent++; if (NAV && NAV.flow) NAV.flow(); }
        if (!gated && (STRONGFLOW.test(t) || short)) { gated = true; speedUp(); revealUntil = Date.now() + 90000 * SPEED; }
      } catch (e) {}
    }
    [300, 900, 2500, 6000, 12000, 25000].forEach(function (ms) { rST.call(window, look, ms); });
    try { document.addEventListener('DOMContentLoaded', function () { rST.call(window, look, 50); }); } catch (e) {}
    rSI.call(window, function () { if (gated && Date.now() < revealUntil) reveal(); }, 600);
  })();
  maskAll();
})();
"""

/** Prefix for document-start scripts that must not run inside captcha / bot-check frames. */
const val CAPTCHA_GUARD = "if(/(^|\\.)(challenges\\.cloudflare\\.com|hcaptcha\\.com|recaptcha\\.net)$/.test(location.hostname)||(location.hostname==='www.google.com'&&location.pathname.indexOf('/recaptcha')===0))return;"

/** Makes the WebView look like Chrome to bot checks: a real Chrome has window.chrome, a WebView does not. */
const val COMPAT_JS = """
(function () {
  try {
    if (!window.chrome) {
      Object.defineProperty(window, 'chrome', {
        value: { app: { isInstalled: false }, loadTimes: function () { return {}; }, csi: function () { return {}; } },
        configurable: true, writable: true
      });
    }
  } catch (e) {}
})();
"""

/** True when the page is just a short "Blocked / Access denied" notice (the site refused this browser). */
const val REFUSAL_PROBE_JS = """
(function () {
  try {
    var t = ((document.body && document.body.innerText) || '').trim();
    return t.length > 0 && t.length < 80 &&
      /^(request\s+)?(blocked|access\s+denied|forbidden|you\s+(have\s+been|are)\s+blocked|error\s*403|403)\b/i.test(t);
  } catch (e) { return false; }
})();
"""
