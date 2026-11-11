package com.swift.browser

// Injected into every page (and frame) at document start while Shields are on.
//  1. Pop-up / pop-under guard: window.open and programmatic link clicks only work right after a
//     real tap on a link or button. Pop-unders that hang on "click anywhere" are dropped.
//  2. Cosmetic filters: hides common ad containers (ad slots, sponsored boxes, ad iframes).
//  3. YouTube: strips ad data from the player responses before the player sees it, hides ad
//     elements, and skips whatever ad still starts.
//
// NOTE: Kotlin raw string, so the JS must not contain a dollar sign.
const val SHIELDS_JS = """
(function () {
  if (window.__swiftShields) return;
  window.__swiftShields = true;
  var H = (location.hostname || '').toLowerCase();

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
  var css = [
    'ins.adsbygoogle', '.adsbygoogle', '[id^="google_ads_"]', '[id^="div-gpt-ad"]', '[id^="aswift_"]',
    'iframe[src*="googlesyndication.com"]', 'iframe[src*="doubleclick.net"]', 'iframe[id^="google_ads_iframe"]',
    'iframe[src*="adsterra"]', 'iframe[src*="exoclick"]', 'iframe[src*="propellerads"]',
    '.ad-slot', '.ad-container', '.ad-banner', '.ad-wrapper', '.ad-unit', '.adbox', '.adsbox', '.ads-container',
    '.advertisement', '.advert-banner', '#ad-container', '#ad_banner', '#ad-banner', '#adbox', '.popup-ad',
    '[data-ad-slot]', '[data-google-query-id]', 'amp-ad', '.taboola', '[id^="taboola-"]', '.OUTBRAIN',
    '[class*="sponsored-content"]', '.sponsored-post', '[id^="mgid_"]', '[class^="mgbox"]',
    '.google-auto-placed', 'ins[data-ad-client]', '.adsbygoogle-noablate', '.ad-placement', '.ads-banner', '.banner-ad',
    '.sticky-ad', '#sticky-ad', '.adcontainer', '[data-testid="ad"]', '.mgid', '.tbl-feed-container', '.rc-widget',
    'iframe[src*="a-ads.com"]', 'iframe[src*="coinzilla"]', 'iframe[src*="mgid.com"]', 'iframe[src*="popads"]',
    '[id^="bsa-zone"]', '#carbonads', '.carbon-wrap'
  ];
  if (/(^|\.)youtube\.com${'$'}/.test(H)) {
    css = css.concat([
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
  function addStyle() {
    try {
      var st = document.createElement('style');
      st.textContent = css.join(',') + '{display:none !important;}';
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }
  if (document.documentElement) addStyle();
  else document.addEventListener('DOMContentLoaded', addStyle);

  // ---------- 3. YouTube ----------
  if (/(^|\.)youtube(-nocookie)?\.com${'$'}/.test(H)) {
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
})();
"""
