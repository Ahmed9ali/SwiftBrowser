package com.swift.browser

// Injected into every page (and frame) at document start while Shields are on.
//  1. Pop-up / pop-under guard: window.open and programmatic link clicks only work right after a
//     real tap on a link or button. Pop-unders that hang on "click anywhere" are dropped.
//  2. Cosmetic filters: hides ad containers. Each selector gets its own CSS rule (one invalid
//     selector can no longer take down the whole stylesheet). A MutationObserver plus an inline
//     `display:none !important` fallback covers dynamically inserted nodes and pages that wipe <head>.
//  3. YouTube: strips ad data from the player responses before the player sees it, hides ad
//     elements, and skips whatever ad still starts.
//  4. Fingerprinting protection (Brave "farbling" lite): deterministic per-site noise on canvas,
//     WebGL vendor/renderer and audio data plus a smaller navigator surface. Pages keep working.
//
// NOTE: Kotlin raw string, so the JS must not contain a dollar sign (use ${'$'} if needed).
const val SHIELDS_JS = """
(function () {
  if (window.__swiftShields) return;
  window.__swiftShields = true;
  var H = (location.hostname || '').toLowerCase();
  var IS_YT = /(^|\.)youtube(-nocookie)?\.com${'$'}/.test(H);

  // ---------- 1. pop-up / pop-under guard ----------
  var lastGesture = 0, lastTarget = null;
  function note(e) { lastGesture = Date.now(); lastTarget = e.target; }
  ['pointerdown', 'mousedown', 'touchstart', 'click', 'keydown'].forEach(function (t) {
    try { window.addEventListener(t, note, true); } catch (e) {}
  });
  function onRealControl() {
    var el = lastTarget;
    while (el && el.nodeType === 1) {
      var tn = el.tagName, role = el.getAttribute && el.getAttribute('role');
      if (tn === 'A' || tn === 'BUTTON' || tn === 'INPUT' || tn === 'SUMMARY' || tn === 'LABEL' ||
          role === 'button' || role === 'link') return true;
      el = el.parentElement;
    }
    return false;
  }
  var AD_URL = /(popunder|popads|propeller|exoclick|adsterra|clickadu|hilltopads|onclickalgo|trafficjunky|juicyads|adcash|popcash|monetag|realsrv|mondiad|richads|\/pop\.js|clickunder|adsco\.re|go\.oclasrv)/i;
  function stubWindow() {
    return { closed: false, focus: function () {}, blur: function () {}, close: function () {},
      postMessage: function () {}, location: { href: '' }, document: { write: function () {}, close: function () {} } };
  }
  try {
    var realOpen = window.open;
    window.open = function (u) {
      var fresh = Date.now() - lastGesture < 1200;
      if (!fresh || !onRealControl() || (u && AD_URL.test(String(u)))) return stubWindow();
      return realOpen.apply(window, arguments);
    };
  } catch (e) {}
  try {
    var realClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      // A link that was never put on the page and is clicked by script: the classic pop-up trick.
      var legit = this.hasAttribute('download') || /^(blob|data):/i.test(this.href || '');
      if (!this.isConnected && !legit) return;
      if (AD_URL.test(this.href || '')) return;
      return realClick.apply(this, arguments);
    };
  } catch (e) {}

  // ---------- 2. cosmetic filters ----------
  var COSMETIC = [
    'ins.adsbygoogle', '.adsbygoogle', '.google-ad', '.google-ads', '.google-ad-slot',
    '[id^="google_ads_"]', '[id^="div-gpt-ad"]', '[id^="aswift_"]',
    'iframe[src*="googlesyndication.com"]', 'iframe[src*="doubleclick.net"]',
    'iframe[id^="google_ads_iframe"]',
    'iframe[src*="adsterra"]', 'iframe[src*="exoclick"]', 'iframe[src*="propellerads"]',
    '.ad-slot', '.ad-container', '.ad-banner', '.ad-wrapper', '.ad-unit',
    '.adbox', '.adsbox', '.ads-container', '.advertisement', '.advert-banner',
    '#ad-container', '#ad_banner', '#ad-banner', '#adbox', '.popup-ad',
    '[data-ad-slot]', '[data-google-query-id]', 'amp-ad',
    '.taboola', '[id^="taboola-"]', '.OUTBRAIN',
    '[class*="sponsored-content"]', '.sponsored-post',
    '[id^="mgid_"]', '[class^="mgbox"]',
    '.google-auto-placed', 'ins[data-ad-client]', '.adsbygoogle-noablate',
    '.ad-placement', '.ads-banner', '.banner-ad', '.sticky-ad', '#sticky-ad',
    '.adcontainer', '[data-testid="ad"]', '.mgid', '.tbl-feed-container', '.rc-widget',
    'iframe[src*="a-ads.com"]', 'iframe[src*="coinzilla"]',
    'iframe[src*="mgid.com"]', 'iframe[src*="popads"]',
    '[id^="bsa-zone"]', '#carbonads', '.carbon-wrap',
    '[id^="ad-slot-"]', '[id*="-ad-slot"]', '[id*="-ad-container"]',
    'div[class^="ad-"]', 'div[class^="ads-"]', 'div[id^="ad-"]',
    '.ad-placeholder', '.ad-label', '.ads-label', '.ad-overlay'
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
      'ytm-mealbar-promo-renderer', 'ytm-paid-content-overlay-renderer', 'ad-slot-renderer',
      'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]'
    ]);
  }
  var COSMETIC_ATTR = 'data-swift-cosmetic';

  function addStyle() {
    try {
      var old = document.querySelectorAll('style[' + COSMETIC_ATTR + ']');
      for (var k = 0; k < old.length; k++) { if (old[k].parentNode) old[k].parentNode.removeChild(old[k]); }
      // One rule per selector: a single invalid selector can only kill its own rule,
      // never the entire comma-joined stylesheet.
      var cssText = '';
      for (var i = 0; i < COSMETIC.length; i++) {
        cssText += COSMETIC[i] + '{display:none !important;}\n';
      }
      var st = document.createElement('style');
      st.setAttribute(COSMETIC_ATTR, '1');
      st.textContent = cssText;
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }

  function hideEl(el) {
    try { el.style.setProperty('display', 'none', 'important'); } catch (e) {}
  }

  function enforce() {
    for (var i = 0; i < COSMETIC.length; i++) {
      var list;
      try { list = document.querySelectorAll(COSMETIC[i]); } catch (e) { continue; }
      for (var j = 0; j < list.length; j++) hideEl(list[j]);
    }
  }

  function applyCosmetic() { addStyle(); enforce(); }

  if (document.documentElement) applyCosmetic();
  try { document.addEventListener('DOMContentLoaded', applyCosmetic); } catch (e) {}
  try { window.addEventListener('load', applyCosmetic); } catch (e) {}

  // Dynamic pages: re-hide anything added later, and re-create the stylesheet if a page
  // wipes <head> (some frameworks replace <html>'s children on navigation).
  try {
    var pending = false;
    var mo = new MutationObserver(function () {
      if (pending) return;
      pending = true;
      var run = function () {
        pending = false;
        try { if (!document.querySelector('style[' + COSMETIC_ATTR + ']')) addStyle(); } catch (e) {}
        enforce();
      };
      try { (window.requestAnimationFrame || window.setTimeout)(run, 16); }
      catch (e) { run(); }
    });
    mo.observe(document.documentElement || document, { childList: true, subtree: true });
  } catch (e) {}

  // ---------- 3. YouTube ----------
  if (IS_YT) {
    var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
    function strip(o) {
      if (!o || typeof o !== 'object') return o;
      try {
        for (var i = 0; i < KEYS.length; i++) if (KEYS[i] in o) delete o[KEYS[i]];
        if (o.playerResponse) strip(o.playerResponse);
        if (o.response && o.response.playerResponse) strip(o.response.playerResponse);
      } catch (e) {}
      return o;
    }
    try {
      var jp = JSON.parse;
      JSON.parse = function () {
        var r = jp.apply(this, arguments);
        if (r && typeof r === 'object' && (r.adPlacements || r.playerAds || r.playerResponse || r.streamingData)) strip(r);
        return r;
      };
    } catch (e) {}
    try {
      var rj = Response.prototype.json;
      Response.prototype.json = function () { return rj.apply(this, arguments).then(strip); };
    } catch (e) {}
    try {
      var ipr;
      Object.defineProperty(window, 'ytInitialPlayerResponse', {
        configurable: true,
        get: function () { return ipr; },
        set: function (v) { ipr = strip(v); }
      });
    } catch (e) {}

    var wasAd = false;
    function clickAll(sel) {
      var n = document.querySelectorAll(sel);
      for (var i = 0; i < n.length; i++) { try { n[i].click(); } catch (e) {} }
    }
    setInterval(function () {
      var player = document.querySelector('.html5-video-player') || document.getElementById('movie_player');
      var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
      var ad = (player && player.classList.contains('ad-showing')) || !!document.querySelector('.ad-showing');
      if (ad) {
        wasAd = true;
        clickAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button, .videoAdUiSkipButton');
        clickAll('.ytp-ad-overlay-close-button');
        if (v && isFinite(v.duration) && v.duration > 0) {
          v.muted = true;
          v.playbackRate = 16;
          try { v.currentTime = v.duration; } catch (e) {}
        }
      } else if (wasAd) {
        wasAd = false;
        if (v) { v.muted = false; v.playbackRate = 1; }
      }
    }, 250);
  }

  // ---------- 4. fingerprinting protection ----------
  (function () {
    var seed = 0;
    try {
      var s = (location.hostname || '') + '|' + (navigator.userAgent || '');
      for (var i = 0; i < s.length; i++) seed = (seed * 31 + s.charCodeAt(i)) | 0;
    } catch (e) {}
    function noiseAt(i) {
      var x = (i * 2654435761 + seed) >>> 0;
      x = (x * 1103515245 + 12345) >>> 0;
      return (x >>> 16) & 0x07;
    }

    // Canvas: per-(pixel, session) noise, so the readback differs from the real pixels
    // by a tiny amount while the picture the user sees is untouched.
    try {
      var getImgData = CanvasRenderingContext2D.prototype.getImageData;
      CanvasRenderingContext2D.prototype.getImageData = function () {
        var img = getImgData.apply(this, arguments);
        try {
          var d = img.data;
          for (var i = 0; i < d.length; i += 4 * 97) d[i] = (d[i] + noiseAt(i)) & 0xff;
        } catch (e) {}
        return img;
      };
    } catch (e) {}
    try {
      var tu = HTMLCanvasElement.prototype.toDataURL;
      HTMLCanvasElement.prototype.toDataURL = function () {
        try {
          var w = this.width | 0, h = this.height | 0;
          var src = this.getContext ? this.getContext('2d') : null;
          if (src && w > 0 && h > 0) {
            var img = src.getImageData(0, 0, w, h);
            var tc = document.createElement('canvas');
            tc.width = w; tc.height = h;
            tc.getContext('2d').putImageData(img, 0, 0);
            return tu.apply(tc, arguments);
          }
        } catch (e) {}
        return tu.apply(this, arguments);
      };
    } catch (e) {}
    try {
      var tb = HTMLCanvasElement.prototype.toBlob;
      HTMLCanvasElement.prototype.toBlob = function () {
        try {
          var w = this.width | 0, h = this.height | 0;
          var src = this.getContext ? this.getContext('2d') : null;
          if (src && w > 0 && h > 0) {
            var img = src.getImageData(0, 0, w, h);
            var tc = document.createElement('canvas');
            tc.width = w; tc.height = h;
            tc.getContext('2d').putImageData(img, 0, 0);
            return tb.apply(tc, arguments);
          }
        } catch (e) {}
        return tb.apply(this, arguments);
      };
    } catch (e) {}

    // WebGL: vendor / renderer strings used for the classic GPU fingerprint.
    try {
      var getParam = WebGLRenderingContext.prototype.getParameter;
      WebGLRenderingContext.prototype.getParameter = function (p) {
        if (p === 37445 || p === 37446) return 'Swift Browser';
        return getParam.apply(this, arguments);
      };
    } catch (e) {}

    // Audio fingerprinting: tiny deterministic noise on the frequency arrays.
    try {
      var gffd = AnalyserNode.prototype.getFloatFrequencyData;
      AnalyserNode.prototype.getFloatFrequencyData = function (arr) {
        gffd.apply(this, arguments);
        try { for (var i = 0; i < arr.length; i += 32) arr[i] += (noiseAt(i) - 4) * 1e-6; } catch (e) {}
      };
    } catch (e) {}

    // Navigator surface: smaller and more common values, so the page sees a "boring" device.
    try { Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { get: function () { return 4; }, configurable: true }); } catch (e) {}
    try { Object.defineProperty(Navigator.prototype, 'deviceMemory', { get: function () { return 4; }, configurable: true }); } catch (e) {}
  })();
})();
"""