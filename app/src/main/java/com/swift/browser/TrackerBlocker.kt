package com.swift.browser

import android.content.Context
import android.net.Uri
import java.util.Locale

/**
 * Shields request filter (a small, Brave-like subset):
 *  - ad / tracker / pop-up network hosts from assets/trackers.txt (subdomains included),
 *  - third-party URLs that look like ad requests (ad server paths, pop-under scripts),
 *  - first-party ad endpoints of YouTube (ad stats, mid-roll info, pagead calls).
 * Cosmetic hiding, pop-up blocking and YouTube ad stripping are done by the injected SHIELDS_JS.
 */
class TrackerBlocker(context: Context) {
    private val blockedHosts: Set<String> = context.assets.open("trackers.txt")
        .bufferedReader()
        .useLines { lines ->
            lines.map { it.substringBefore("#").trim().lowercase(Locale.ROOT) }
                .filter { it.isNotBlank() }
                .toSet()
        }

    // Third-party only: these fragments are very rarely part of real content.
    private val adPathParts = listOf(
        "/pagead/", "/adserver", "/adframe", "/adsbygoogle", "/ads/show", "/ads/serve", "/ad/serve",
        "/banner_ads", "/bannerads", "/popunder", "/popads", "/pop.js", "/prebid", "/adview",
        "/ad-manager", "/advert.", "/adverts/", "/ads.js", "/ads.php", "/adx.js", "show_ads", "ad_banner",
        "/vast.xml", "/vast?", "adtag", "/ad.php?", "/click.php?", "/clickunder"
    )

    // Well-known ad / bait script names: blocked even when a site serves them from its own domain.
    private val alwaysBlockParts = listOf(
        "/pagead.js", "/adsbygoogle.js", "/pagead/js/adsbygoogle", "/pagead/js/", "/pagead/viewthroughconversion",
        "/doubleclick/", "/googleadservices/", "/ads/pagead"
    )

    // Ad endpoints that live on the site's own domain (checked even for first-party requests).
    private val youtubeAdParts = listOf(
        "/api/stats/ads", "/pagead/", "/ptracking", "/get_midroll_info", "/api/stats/atr", "adformat=", "&ad_type="
    )

    private fun hostOf(url: String?): String? =
        runCatching { Uri.parse(url ?: "").host?.lowercase(Locale.ROOT) }.getOrNull()

    /** True when [host] (or a parent domain of it) is in the block list. */
    fun isAdHost(host: String?): Boolean {
        var h = host?.lowercase(Locale.ROOT) ?: return false
        while (true) {
            if (h in blockedHosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false // never match a bare TLD
            h = h.substring(dot + 1)
        }
    }

    private fun sameSite(a: String, b: String) = a == b || a.endsWith(".$b") || b.endsWith(".$a")

    // Parameters / paths that ad-network click URLs carry (never part of a normal page address).
    private val redirectParts = listOf(
        "zoneid=", "campaignid=", "clickid=", "click_id=", "aff_sub", "subid=", "sub_id=", "pubid=", "adzone",
        "/popunder", "/popads", "/clickunder", "/pop.php", "/click.php", "/ad.php", "/redirect.php?ad",
        "utm_medium=pop", "utm_source=pop", "utm_medium=push", "utm_source=push", "tracking.php?", "/go.php?id="
    )

    /**
     * A page that sends you somewhere else on its own (no tap) and the target looks like an ad
     * click: the classic spam redirect of shortlink / streaming / download sites. Taps are never touched.
     */
    fun isSpamRedirect(url: String, pageUrl: String?, hasGesture: Boolean): Boolean {
        if (hasGesture) return false
        val host = hostOf(url) ?: return false
        val pageHost = hostOf(pageUrl) ?: return false
        if (sameSite(host, pageHost)) return false
        val lower = url.lowercase(Locale.ROOT)
        return redirectParts.any { lower.contains(it) }
    }

    fun shouldBlock(requestUrl: String, pageUrl: String?, isMainFrame: Boolean = false): Boolean {
        val requestHost = hostOf(requestUrl) ?: return false
        val pageHost = hostOf(pageUrl)

        if (isAdHost(requestHost)) {
            // The user typed / opened an ad host on purpose: not our call (only redirects are cancelled).
            if (isMainFrame && pageHost == null) return false
            return !(pageHost != null && sameSite(requestHost, pageHost))
        }

        val firstParty = pageHost != null && sameSite(requestHost, pageHost)
        val lower = requestUrl.lowercase(Locale.ROOT)

        if (pageHost != null && (pageHost.endsWith("youtube.com") || pageHost.endsWith("youtube-nocookie.com")) &&
            (requestHost.endsWith("youtube.com") || requestHost.endsWith("googlevideo.com"))
        ) {
            if (youtubeAdParts.any { lower.contains(it) } && !lower.contains("/youtubei/v1/player")) return true
        }

        if (!isMainFrame && alwaysBlockParts.any { lower.contains(it) }) return true
        if (firstParty || isMainFrame) return false
        return adPathParts.any { lower.contains(it) }
    }
}
