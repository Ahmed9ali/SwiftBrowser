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
    private val hasAdParams: (String) -> Boolean
) {
    enum class Kind { ALLOW, BLOCK }
    data class Verdict(val kind: Kind, val reason: String) {
        val allowed get() = kind == Kind.ALLOW
    }

    private class Tab {
        var tapHref: String? = null
        var tapAt = 0L
        var commitAt = 0L
        var commitUrl: String = ""
        var tainted = false           // current redirect chain started from a blocked/ad-like hop
        var chainAllowedByTap = false // current chain began with a matching user tap
        val allowOnce = HashSet<String>()
    }

    private val tabs = HashMap<Long, Tab>()
    private val visited = HashSet<String>() // registrable domains the user actually reached
    private fun tab(id: Long) = tabs.getOrPut(id) { Tab() }

    // ---- events ----------------------------------------------------------------------------

    /** Page script reported a real finger tap. href = absolute URL of the closest <a>, or "". */
    @Synchronized fun recordTap(tabId: Long, href: String?) {
        val t = tab(tabId)
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
        t.allowOnce.add(key(url))
        host(url)?.let { visited.add(registrable(it)) }
    }

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
        if (pageHost != null && sameSite(h, pageHost)) return Verdict(Kind.ALLOW, "same site")

        val adLike = isAdHost(h) || hasAdParams(url)
        val tapFresh = t0 - t.tapAt in 0..TAP_WINDOW_MS
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

        if (adLike) { t.tainted = true; return Verdict(Kind.BLOCK, "ad destination") }

        // Cross-site, not an ad, no matching tap.
        val sinceCommit = if (t.commitAt == 0L) Long.MAX_VALUE else t0 - t.commitAt
        if (tapFresh && t.tapHref != null) {
            // The finger was on a link, but the page sent us somewhere else: click hijack.
            return Verdict(Kind.BLOCK, "tapped link did not match destination")
        }
        if (tapFresh) {
            // Tap on a button / JS link that then navigates (shortlink "continue", "open site"): the user asked.
            return Verdict(Kind.ALLOW, "tap led to navigation")
        }
        if (sinceCommit < AUTO_REDIRECT_MS) {
            // Fast automatic jump right after load, no tap: classic ad bounce unless the site is known.
            return if (isVisited(h)) Verdict(Kind.ALLOW, "auto redirect to visited site")
            else Verdict(Kind.BLOCK, "automatic redirect right after load")
        }
        // Long after load, no tap: scripted navigation (timer / ad script).
        return if (isVisited(h)) Verdict(Kind.ALLOW, "scripted, visited site")
        else Verdict(Kind.BLOCK, "scripted navigation without a tap")
    }

    /**
     * window.open / target=_blank. [url] may be null if not yet known; then only the gesture is judged
     * and the caller must re-check with the first URL.
     */
    @Synchronized fun decidePopup(tabId: Long, url: String?, pageUrl: String?, hasGesture: Boolean): Verdict {
        val t = tab(tabId)
        val t0 = now()
        val tapFresh = t0 - t.tapAt in 0..TAP_WINDOW_MS
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
        if (isAdHost(h) || hasAdParams(url)) return Verdict(Kind.BLOCK, "ad popup")
        if (isVisited(h)) return Verdict(Kind.ALLOW, "popup to visited site")
        return Verdict(Kind.BLOCK, "popup to unknown site")
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun isVisited(h: String) = registrable(h) in visited

    private fun key(url: String) = url.substringBefore('#')

    private fun sameDestination(href: String, url: String): Boolean {
        if (key(href) == key(url)) return true
        val a = host(href) ?: return false
        val b = host(url) ?: return false
        // Same registrable domain is enough: the link may 302 within its own site first.
        return registrable(a) == registrable(b)
    }

    companion object {
        const val TAP_WINDOW_MS = 1500L
        const val AUTO_REDIRECT_MS = 4000L

        /** Hosts that carry sign-in, payment and captcha flows: never interfere. */
        private val TRUSTED_SUFFIXES = listOf(
            "accounts.google.com", "login.microsoftonline.com", "login.live.com", "appleid.apple.com",
            "facebook.com", "github.com", "paypal.com", "stripe.com", "checkout.stripe.com",
            "razorpay.com", "paytm.com", "phonepe.com", "okta.com", "auth0.com", "recaptcha.net",
            "hcaptcha.com", "challenges.cloudflare.com", "twitter.com", "x.com", "discord.com",
            "linkedin.com", "yahoo.com", "amazon.com", "line.me", "naver.com", "kakao.com"
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

        fun sameSite(a: String, b: String) = registrable(a) == registrable(b)
    }
}
