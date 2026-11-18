package com.swift.browser

import java.net.URI
import java.util.Locale

/**
 * Decides whether a page-initiated navigation or popup is something the user asked for.
 * Pure Kotlin (no Android types) so it can be unit tested. One instance per app; state is per tab.
 *
 * Inputs the WebView gives us are weak (hasGesture is sticky, no initiator), so the main signal is
 * a "tap record": the page script reports which link (href) the user's finger actually landed on.
 * A navigation that matches that tap is the user's; one that doesn't, and looks like an ad, is not.
 */
class NavigationGuard(
    private val now: () -> Long,
    private val isAdHost: (String) -> Boolean,
    private val hasAdParams: (String, String?) -> Boolean
) {
    enum class Kind { ALLOW, BLOCK }
    data class Verdict(val kind: Kind, val reason: String) {
        val allowed get() = kind == Kind.ALLOW
    }

    private class Tab {
        var tapHref: String? = null
        var tapAt = 0L
        var tapControl = false        // the tap landed on a button / clickable control with a label
        var commitAt = 0L
        var commitUrl: String = ""
        var tainted = false           // current redirect chain started from a blocked/ad-like hop
        var chainAllowedByTap = false // current chain began with a matching user tap
        var flowUntil = 0L            // page looks like a link-shortener / "click an ad, wait for your link" step
        val allowOnce = HashSet<String>()
    }

    private val tabs = HashMap<Long, Tab>()
    private val visited = HashSet<String>() // registrable domains the user actually reached
    private fun tab(id: Long) = tabs.getOrPut(id) { Tab() }

    // ---- events ----------------------------------------------------------------------------

    /** Page script reported a real finger tap. href = absolute URL of the closest <a>, or "". */
    @Synchronized fun recordTap(tabId: Long, href: String?, control: Boolean = false) {
        val t = tab(tabId)
        t.tapControl = control
        t.tapHref = href?.takeIf { it.isNotBlank() }
        t.tapAt = now()
    }

    /** A page was committed (onPageStarted for a real navigation that was not blocked). */
    @Synchronized fun onCommit(tabId: Long, url: String) {
        val t = tab(tabId)
        t.commitAt = now()
        t.commitUrl = url
        t.tainted = false
        t.chainAllowedByTap = false
        host(url)?.let { visited.add(registrable(it)) }
    }

    /** User typed / picked / bookmarked this URL: it counts as a deliberate destination. */
    @Synchronized fun userNavigated(tabId: Long, url: String) {
        val t = tab(tabId)
        t.tainted = false
        t.chainAllowedByTap = false
        t.flowUntil = 0L
        t.allowOnce.add(key(url))
        host(url)?.let { visited.add(registrable(it)) }
    }

    /**
     * The page asks for an ad click / waiting before it hands over a link (link shorteners). While this is on, taps
     * on the page may open the ad task and the following steps, but ad hosts still cannot navigate on their own.
     */
    @Synchronized fun markFlow(tabId: Long) { tab(tabId).flowUntil = now() + FLOW_MS }

    /** "Allow" chip. */
    @Synchronized fun allowOnce(tabId: Long, url: String) {
        tab(tabId).allowOnce.add(key(url))
    }

    @Synchronized fun forget(tabId: Long) { tabs.remove(tabId) }

    // ---- decisions -------------------------------------------------------------------------

    /**
     * A main-frame navigation requested by the page (shouldOverrideUrlLoading).
     * [pageUrl] is the currently committed page of that tab.
     */
    @Synchronized fun decideNavigation(tabId: Long, url: String, pageUrl: String?, isRedirect: Boolean): Verdict {
        val t = tab(tabId)
        val h = host(url) ?: return Verdict(Kind.ALLOW, "no host")
        val t0 = now()

        if (t.allowOnce.remove(key(url))) return Verdict(Kind.ALLOW, "allowed by user")
        if (isTrusted(h)) return Verdict(Kind.ALLOW, "trusted host")

        val pageHost = pageUrl?.let { host(it) }
        if (pageHost != null && sameSite(h, pageHost)) {
            // Same-site hop right after a tap (search-result redirectors): keep the chain user-initiated.
            if (t0 - t.tapAt in 0..TAP_WINDOW_MS) t.chainAllowedByTap = true
            return Verdict(Kind.ALLOW, "same site")
        }

        val hostAd = isAdHost(h)
        val adLike = hostAd || hasAdParams(url, pageUrl)
        if (t0 < t.flowUntil) {
            if (t0 - t.tapAt in 0..FLOW_TAP_MS) {
                t.chainAllowedByTap = true; t.tainted = false
                return Verdict(Kind.ALLOW, "shortlink step after a tap")
            }
            if (!hostAd) return Verdict(Kind.ALLOW, "shortlink step, not a listed ad host")
        }
        val tapFresh = t0 - t.tapAt in 0..TAP_WINDOW_MS
        // "Download", "Continue", "Get app" ... : the user pressed a real, labelled control a moment ago.
        // Its destination is theirs even when it goes through a tracking/redirect host.
        if (t.tapControl && t0 - t.tapAt in 0..CONTROL_WINDOW_MS) {
            t.chainAllowedByTap = true
            t.tainted = false
            return Verdict(Kind.ALLOW, "after tapping a button")
        }
        val tapMatches = tapFresh && t.tapHref != null && sameDestination(t.tapHref!!, url)

        if (tapMatches) {
            t.chainAllowedByTap = true
            t.tainted = false
            // The user really tapped a link to this ad host (e.g. a sponsored link): still honour it.
            return Verdict(Kind.ALLOW, "matches tapped link")
        }

        if (isRedirect) {
            // Continuation of a chain the user started: follow it unless it hits something ad-like.
            if (t.chainAllowedByTap && !adLike) return Verdict(Kind.ALLOW, "redirect of tapped link")
            if (adLike) { t.tainted = true; return Verdict(Kind.BLOCK, "redirect to ad destination") }
            if (t.tainted) return Verdict(Kind.BLOCK, "redirect in ad chain")
            // Plain server redirect of an unknown navigation: allow only if we have seen this site.
            return if (isVisited(h)) Verdict(Kind.ALLOW, "redirect to visited site")
            else Verdict(Kind.ALLOW, "redirect, no ad signs")
        }

        // The user has used this page since it loaded and only the URL pattern (not the host) looks ad-like:
        // news sites and shops put tracking parameters on real article links.
        if (!hostAd && t.tapAt > 0L && t.tapAt >= t.commitAt) return Verdict(Kind.ALLOW, "user interacted, link only has tracking parameters")
        if (adLike) { t.tainted = true; return Verdict(Kind.BLOCK, "ad destination") }

        // Cross-site, not an ad, no matching tap.
        val sinceCommit = if (t.commitAt == 0L) Long.MAX_VALUE else t0 - t.commitAt
        if (t.tapAt > 0L && t.tapAt >= t.commitAt) {
            // The user has interacted with this page (tap on a link, button, search result, "Pay" ...) and the
            // destination does not look like an ad: real sites redirect through gateways, SSO and trackers.
            return Verdict(Kind.ALLOW, "user interacted with the page")
        }
        if (sinceCommit < AUTO_REDIRECT_MS) {
            // Fast automatic jump right after load, no tap: classic ad bounce unless the site is known.
            return if (isVisited(h) || !suspicious(h)) Verdict(Kind.ALLOW, "auto redirect, host looks ordinary")
            else Verdict(Kind.BLOCK, "automatic redirect right after load")
        }
        // Long after load, no tap: scripted navigation (timer / ad script).
        return if (isVisited(h) || !suspicious(h)) Verdict(Kind.ALLOW, "scripted, host looks ordinary")
        else Verdict(Kind.BLOCK, "scripted navigation without a tap")
    }

    /**
     * window.open / target=_blank. [url] may be null if not yet known; then only the gesture is judged
     * and the caller must re-check with the first URL.
     */
    @Synchronized fun decidePopup(tabId: Long, url: String?, pageUrl: String?, hasGesture: Boolean): Verdict {
        val t = tab(tabId)
        val t0 = now()
        val inFlow = t0 < t.flowUntil
        val tapFresh = t0 - t.tapAt in 0..(if (inFlow) FLOW_TAP_MS else TAP_WINDOW_MS)
        if (!hasGesture && !tapFresh) return Verdict(Kind.BLOCK, "popup without a tap")
        if (url == null || url.isBlank() || url == "about:blank") {
            return Verdict(Kind.ALLOW, "popup pending url")
        }
        val h = host(url) ?: return Verdict(Kind.ALLOW, "no host")
        if (t.allowOnce.remove(key(url))) return Verdict(Kind.ALLOW, "allowed by user")
        if (isTrusted(h)) return Verdict(Kind.ALLOW, "trusted host")
        val pageHost = pageUrl?.let { host(it) }
        if (pageHost != null && sameSite(h, pageHost)) return Verdict(Kind.ALLOW, "same site popup")
        if (t.tapHref != null && tapFresh && sameDestination(t.tapHref!!, url)) {
            return Verdict(Kind.ALLOW, "popup from tapped link")
        }
        if (inFlow && tapFresh) return Verdict(Kind.ALLOW, "shortlink task opened by a tap")
        if (isAdHost(h) || hasAdParams(url, pageUrl)) return Verdict(Kind.BLOCK, "ad popup")
        // A real link was tapped (e.g. a search result opening in a new tab) and the target is not ad-like.
        if (tapFresh && t.tapHref != null) return Verdict(Kind.ALLOW, "popup from tapped link, not ad-like")
        if (isVisited(h)) return Verdict(Kind.ALLOW, "popup to visited site")
        return Verdict(Kind.BLOCK, "popup to unknown site")
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Ad bounces go to throwaway domains: bare IPs, cheap TLDs, hyphen / digit soup. Ordinary names are left alone. */
    private fun suspicious(h: String): Boolean {
        if (h.split('.').size == 4 && h.split('.').all { p -> p.isNotEmpty() && p.all { it.isDigit() } }) return true
        if (h.substringAfterLast('.') in CHEAP_TLDS) return true
        val label = registrable(h).substringBefore('.')
        return label.count { it == '-' } >= 2 || label.count { it.isDigit() } >= 5
    }

    private fun isVisited(h: String) = registrable(h) in visited

    private fun key(url: String) = url.substringBefore('#')

    private fun sameDestination(href: String, url: String): Boolean {
        if (key(href) == key(url)) return true
        val a = host(href) ?: return false
        val b = host(url) ?: return false
        // Same registrable domain is enough: the link may 302 within its own site first.
        return sameSite(a, b)
    }

    companion object {
        const val TAP_WINDOW_MS = 1500L
        const val AUTO_REDIRECT_MS = 4000L
        const val CONTROL_WINDOW_MS = 8000L
        const val FLOW_MS = 300_000L
        const val FLOW_TAP_MS = 4000L

        private val CHEAP_TLDS = setOf(
            "xyz", "top", "click", "site", "online", "icu", "buzz", "monster", "fun", "cyou", "sbs", "cfd",
            "rest", "quest", "gdn", "lol", "space", "website", "vip", "bid", "win", "loan", "download",
            "stream", "trade", "review", "party", "men", "work", "link", "pw", "tk", "ml", "ga", "cf", "gq"
        )

        /** Hosts that carry sign-in, payment and captcha flows: never interfere. */
        private val TRUSTED_SUFFIXES = listOf(
            "accounts.google.com", "login.microsoftonline.com", "login.live.com", "appleid.apple.com",
            "facebook.com", "github.com", "paypal.com", "stripe.com", "checkout.stripe.com",
            "razorpay.com", "paytm.com", "phonepe.com", "okta.com", "auth0.com", "recaptcha.net",
            "hcaptcha.com", "challenges.cloudflare.com", "twitter.com", "x.com", "discord.com",
            "linkedin.com", "yahoo.com", "amazon.com", "line.me", "naver.com", "kakao.com",
            "razorpay.com", "paytm.com", "paytmpayments.com", "payu.in", "payu.com", "payumoney.com", "cashfree.com",
            "juspay.in", "juspay.io", "billdesk.com", "ccavenue.com", "braintreegateway.com", "adyen.com",
            "checkout.com", "cybersource.com", "npci.org.in", "bhimupi.org.in", "google.com", "gstatic.com",
            "apple.com", "icloud.com", "microsoft.com", "live.com", "bing.com", "duckduckgo.com", "yandex.com",
            "mastercard.com", "visa.com", "americanexpress.com", "hdfcbank.com", "icicibank.com", "sbi.co.in",
            "axisbank.com", "kotak.com", "gpay.app.goo.gl", "pay.google.com", "phonepe.com"
        )

        // Common two-level public suffixes (approximation of the public suffix list).
        private val SECOND_LEVEL = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.in", "net.in",
            "org.in", "co.jp", "ne.jp", "or.jp", "com.br", "com.cn", "com.mx", "co.nz", "co.za",
            "com.sg", "com.hk", "com.tw", "com.tr", "com.ar", "co.kr", "co.id", "com.pk", "com.bd",
            "com.ng", "com.eg", "com.sa", "com.ua", "com.ph", "com.my", "com.vn"
        )

        fun isTrusted(h: String): Boolean = TRUSTED_SUFFIXES.any { h == it || h.endsWith(".$it") }

        fun host(url: String): String? = try {
            URI(url.trim()).host?.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        }

        fun registrable(h: String): String {
            val parts = h.split('.')
            if (parts.size <= 2) return h
            val last2 = parts.takeLast(2).joinToString(".")
            return if (last2 in SECOND_LEVEL && parts.size >= 3) parts.takeLast(3).joinToString(".")
            else last2
        }

        /** bbc.com / bbc.co.uk, amazon.in / amazon.com: same name under another country suffix is the same site. */
        fun sameSite(a: String, b: String): Boolean {
            val ra = registrable(a); val rb = registrable(b)
            if (ra == rb) return true
            val la = ra.substringBefore('.'); val lb = rb.substringBefore('.')
            return la.length >= 3 && la == lb
        }
    }
}
