package com.swift.browser

// Injected into every page (and frame) at document start while Shields are on.
//  1. Pop-up / pop-under guard: window.open and programmatic link clicks only work right after a
//     real tap on a link or button. Pop-unders that hang on "click anywhere" are dropped.
//  2. Anti-Adblock bypass: fakes element dimensions for known bait selectors so anti-adblock
//     scripts think the ads are visible.
//  3. Cosmetic filters: hides ad containers, banners, and anti-adblock modals.
//  4. YouTube: strips ad data from the player responses before the player sees it.
//  5. Fingerprinting protection: deterministic per-site noise on canvas, WebGL, and audio.
//  6. Network API shim: rejects fetch / XHR / WebSocket requests to blocked hosts.
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
  var AD_URL = /(popunder|popads|propeller|exoclick|adsterra|clickadu|hilltopads|onclickalgo|trafficjunky|juicyads|adcash|popcash|monetag|realsrv|mondiad|richads|\/pop\.js|clickunder|adsco\.re|go\.oclasrv|nudus|luckyday)/i;
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
      var legit = this.hasAttribute('download') || /^(blob|data):/i.test(this.href || '');
      if (!this.isConnected && !legit) return;
      if (AD_URL.test(this.href || '')) return;
      return realClick.apply(this, arguments);
    };
  } catch (e) {}

  // ---------- 2. Anti-Adblock Bypass ----------
  // Anti-adblock scripts check if bait elements (like .adsbox) have an offsetHeight of 0.
  // We intercept the JavaScript getters to lie and say they are 50px tall.
  (function() {
    var BaitSelectors = '.adsbox, .ad-banner, #ad-container, .adsbygoogle, .google-ad, #ad_slot, .ad-slot, .ad-wrapper, [id^="google_ads"]';
    try {
      var origOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
      var origOffsetWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
      var origClientHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight');
      var origClientWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth');

      function isBait(el) {
        try { return el && el.matches && el.matches(BaitSelectors); } catch(e) { return false; }
      }

      Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
        get: function() { return isBait(this) ? 50 : origOffsetHeight.get.call(this); }
      });
      Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
        get: function() { return isBait(this) ? 300 : origOffsetWidth.get.call(this); }
      });
      Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
        get: function() { return isBait(this) ? 50 : origClientHeight.get.call(this); }
      });
      Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
        get: function() { return isBait(this) ? 300 : origClientWidth.get.call(this); }
      });

      // Fake adsbygoogle
      if (!window.adsbygoogle) {
        window.adsbygoogle = { push: function(){}, loaded: true };
      }
    } catch(e) {}
  })();

  // ---------- 3. cosmetic filters ----------
  var COSMETIC = [
    'ins.adsbygoogle', '.adsbygoogle', '.google-ad', '.google-ads', '.google-ad-slot',
    '[id^="google_ads_"]', '[id^="div-gpt-ad"]', '[id^="aswift_"]',
    'iframe[src*="googlesyndication.com"]', 'iframe[src*="doubleclick.net"]',
    'iframe[id^="google_ads_iframe"]',
    'iframe[src*="adsterra"]', 'iframe[src*="exoclick"]', 'iframe[src*="propellerads"]',
    'iframe[src*="nudus"]', 'iframe[src*="luckyday"]',
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
    '.ad-placeholder', '.ad-label', '.ads-label', '.ad-overlay',
    // Specific to movie sites (native banners)
    'a[href*="skillify"]', 'img[src*="nudus"]', 'a[href*="nudus"]', 'img[src*="banner"]',
    // Anti-adblock modals
    '[class*="adblock"]', '[id*="adblock"]', '.adblock-modal', '#adblock-modal',
    '[class*="ad-block"]', '[id*="ad-block"]', '.anti-adblock'
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

  // ---------- 4. YouTube ----------
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

  // ---------- 5. fingerprinting protection ----------
  (function () {
    if (/(cloudflare|challenge|captcha|turnstile)\./i.test(H)) return;

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

    try {
      var getParam = WebGLRenderingContext.prototype.getParameter;
      WebGLRenderingContext.prototype.getParameter = function (p) {
        if (p === 37445 || p === 37446) return 'Swift Browser';
        return getParam.apply(this, arguments);
      };
    } catch (e) {}

    try {
      var gffd = AnalyserNode.prototype.getFloatFrequencyData;
      AnalyserNode.prototype.getFloatFrequencyData = function (arr) {
        gffd.apply(this, arguments);
        try { for (var i = 0; i < arr.length; i += 32) arr[i] += (noiseAt(i) - 4) * 1e-6; } catch (e) {}
      };
    } catch (e) {}

    try { Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { get: function () { return 4; }, configurable: true }); } catch (e) {}
    try { Object.defineProperty(Navigator.prototype, 'deviceMemory', { get: function () { return 4; }, configurable: true }); } catch (e) {}
  })();

  // ---------- 6. fetch / XHR / beacon / WebSocket shim ----------
  (function () {
    var hosts = null;
    try {
      if (window.SwiftFilter && window.SwiftFilter.getBlockedHosts) {
        hosts = JSON.parse(window.SwiftFilter.getBlockedHosts());
      }
    } catch (e) {}
    if (!hosts) return;

    function isAdUrl(u) {
      if (u === undefined || u === null) return false;
      try {
        var s = String(u);
        if (s.indexOf(':') === 0) return false;
        if (!/^[a-z][a-z0-9+.\-]*:/i.test(s) && s.charAt(0) !== '/') s = 'https://' + s;
        var host = '';
        try { host = new URL(s, location.href).hostname.toLowerCase(); } catch (e) { return false; }
        if (!host) return false;
        // Whitelist essential CDNs
        if (host.endsWith('jsdelivr.net') || host.endsWith('cloudflare.com') || host.endsWith('googleapis.com')) return false;
        
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
        if (isAdUrl(url)) return Promise.reject(new TypeError('Failed to fetch'));
        return realFetch.apply(this, arguments);
      };
    } catch (e) {}

    try {
      var XHR = window.XMLHttpRequest;
      var realOpen = XHR.prototype.open;
      var realSend = XHR.prototype.send;
      XHR.prototype.open = function (method, url) {
        try { this.__swiftBlocked = isAdUrl(String(url)); } catch (e) { this.__swiftBlocked = false; }
        return realOpen.apply(this, arguments);
      };
      XHR.prototype.send = function () {
        if (this.__swiftBlocked) {
          var xhr = this;
          setTimeout(function () {
            try {
              Object.defineProperty(xhr, 'readyState', { value: 4, configurable: true });
              Object.defineProperty(xhr, 'status', { value: 0, configurable: true });
              Object.defineProperty(xhr, 'statusText', { value: '', configurable: true });
              if (typeof xhr.onerror === 'function') xhr.onerror(new Event('error'));
              if (typeof xhr.onreadystatechange === 'function') xhr.onreadystatechange(new Event('readystatechange'));
            } catch (e) {}
          }, 0);
          return;
        }
        return realSend.apply(this, arguments);
      };
    } catch (e) {}

    try {
      var realBeacon = navigator.sendBeacon;
      if (realBeacon) {
        navigator.sendBeacon = function (url) {
          if (isAdUrl(String(url))) return true;
          return realBeacon.apply(this, arguments);
        };
      }
    } catch (e) {}

    try {
      var RealWS = window.WebSocket;
      if (RealWS) {
        var WrappedWS = function (url, protocols) {
          if (isAdUrl(String(url))) {
            var self = this;
            setTimeout(function () {
              try {
                Object.defineProperty(self, 'readyState', { value: 3, configurable: true });
                if (typeof self.onerror === 'function') self.onerror(new Event('error'));
                if (typeof self.onclose === 'function')
                  self.onclose({ code: 1006, reason: 'Blocked by Swift Shields', wasClean: false });
              } catch (e) {}
            }, 0);
            return;
          }
          return protocols === undefined ? new RealWS(url) : new RealWS(url, protocols);
        };
        WrappedWS.prototype = RealWS.prototype;
        WrappedWS.CONNECTING = RealWS.CONNECTING;
        WrappedWS.OPEN = RealWS.OPEN;
        WrappedWS.CLOSING = RealWS.CLOSING;
        WrappedWS.CLOSED = RealWS.CLOSED;
        window.WebSocket = WrappedWS;
      }
    } catch (e) {}

    try {
      var RealES = window.EventSource;
      if (RealES) {
        var WrappedES = function (url, config) {
          if (isAdUrl(String(url))) {
            var self = this;
            setTimeout(function () {
              try { if (typeof self.onerror === 'function') self.onerror(new Event('error')); } catch (e) {}
            }, 0);
            return;
          }
          return config === undefined ? new RealES(url) : new RealES(url, config);
        };
        WrappedES.prototype = RealES.prototype;
        window.EventSource = WrappedES;
      }
    } catch (e) {}
  })();
})();
"""