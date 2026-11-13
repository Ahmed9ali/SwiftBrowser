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
  var styleEl = null;
  function installStyle() {
    try {
      if (styleEl && styleEl.isConnected) return;
      var parent = document.head || document.documentElement;
      if (!parent) return;
      styleEl = document.createElement('style');
      styleEl.setAttribute('data-swift-cosmetic', '1');
      styleEl.textContent = COSMETIC.join(',\n') + '{display:none !important;}';
      parent.appendChild(styleEl);
    } catch (e) {}
  }
  installStyle();
  try { document.addEventListener('readystatechange', installStyle); } catch (e) {}
  try { document.addEventListener('DOMContentLoaded', installStyle); } catch (e) {}
  // Re-check occasionally in case a page wipes <head>; a single boolean read, no DOM scan.
  try { setInterval(installStyle, 2000); } catch (e) {}

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

  // ---------- 7. fetch / XHR shim for known ad hosts ----------
  (function () {
    var hosts = null;
    try {
      if (window.SwiftFilter && window.SwiftFilter.getBlockedHosts) hosts = JSON.parse(window.SwiftFilter.getBlockedHosts());
    } catch (e) {}
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
