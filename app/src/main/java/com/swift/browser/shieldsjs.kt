package com.swift.browser

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
  var AD_URL = /(popunder|popads|propeller|exoclick|adsterra|clickadu|hilltopads|onclickalgo|trafficjunky|juicyads|adcash|popcash|monetag|realsrv|mondiad|richads|\/pop\.js|clickunder|adsco\.re|go\.oclasrv|nudus|luckyday|brolackview|skillify|vpn-trusthub)/i;
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
  
  // ---------- 2. Aggressive Ad Click Blocker ----------
  // Stops clicks on images or links that point to known ad hosts
  document.addEventListener('click', function(e) {
      var el = e.target;
      while (el) {
          if (el.tagName === 'A' && el.href) {
              if (AD_URL.test(el.href)) {
                  e.preventDefault();
                  e.stopPropagation();
                  return false;
              }
          }
          // Check if the image source contains ad keywords
          if (el.tagName === 'IMG' && el.src) {
              if (AD_URL.test(el.src)) {
                  e.preventDefault();
                  e.stopPropagation();
                  return false;
              }
          }
          el = el.parentElement;
      }
  }, true);

  // ---------- 3. Anti-Adblock Bypass ----------
  (function() {
    if (!window.adsbygoogle) window.adsbygoogle = { push: function(){}, loaded: true };
    if (!window.canRunAds) window.canRunAds = true;
    if (!window.adBlockDetected) window.adBlockDetected = false;
    
    // Watch for the anti-adblock modal and kill it
    var observer = new MutationObserver(function(mutations) {
      mutations.forEach(function(m) {
        m.addedNodes.forEach(function(node) {
          if (node.nodeType === 1) { // Element
            var text = node.innerText || "";
            if (text.includes("ADBLOCK") || text.includes("AdBlocker") || text.includes("VPN Required")) {
              node.style.display = 'none';
              node.remove();
            }
          }
        });
      });
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
  })();

  // ---------- 4. cosmetic filters ----------
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
    // Specific native banners (NUDUS, etc.)
    'a[href*="skillify"]', 'img[src*="nudus"]', 'a[href*="nudus"]', 'img[src*="banner"]',
    'img[src*="brolackview"]', 'a[href*="brolackview"]',
    // Anti-Adblock Modals
    '[class*="adblock"]', '[id*="adblock"]', '.adblock-modal', '#adblock-modal',
    '[class*="ad-block"]', '[id*="ad-block"]', '.anti-adblock', '#anti-adblock',
    // VPN Prompt
    '[class*="vpn"]', '[id*="vpn"]', '.vpn-popup', '#vpn-popup'
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
  var COSMETIC_ATTR = 'data-swift-cosmetic';

  function addStyle() {
    try {
      var old = document.querySelectorAll('style[' + COSMETIC_ATTR + ']');
      for (var k = 0; k < old.length; k++) { if (old[k].parentNode) old[k].parentNode.removeChild(old[k]); }
      var cssText = '';
      for (var i = 0; i < COSMETIC.length; i++) {
        cssText += COSMETIC[i] + '{display:none !important; visibility:hidden !important;}\n';
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

  // ---------- 5. YouTube ----------
  if (IS_YT) {
    if (location.pathname.indexOf('/shorts/') === -1) {
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
    }
  }

  // ---------- 6. fetch / XHR shim (Optimized for speed) ----------
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
        if (s.indexOf(':') === 0 || s.indexOf('//') === 0) return false;
        
        var host = '';
        try { host = new URL(s, location.href).hostname.toLowerCase(); } catch (e) { return false; }
        if (!host) return false;
        
        if (host.endsWith('cloudflare.com') || host.endsWith('hcaptcha.com') || 
            host.endsWith('google.com') || host.endsWith('gstatic.com') || host.endsWith('jsdelivr.net')) return false;
        
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
  })();
})();
"""