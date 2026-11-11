package com.swift.browser

import android.content.Context
import android.net.Uri
import java.util.Locale

class TrackerBlocker(context: Context) {
    private val blockedHosts: Set<String> = context.assets.open("trackers.txt")
        .bufferedReader()
        .useLines { lines ->
            lines.map { it.substringBefore("#").trim().lowercase(Locale.ROOT) }
                .filter { it.isNotBlank() }
                .toSet()
        }

    private val adPathParts = listOf(
        "/pagead/", "/adserver", "/adframe", "/adsbygoogle", "/ads/show", "/ads/serve", "/ad/serve",
        "/banner_ads", "/bannerads", "/popunder", "/popads", "/pop.js", "/prebid", "/adview",
        "/ad-manager", "/advert.", "/adverts/", "/ads.php", "/adx.js", "show_ads", "ad_banner",
        "/vast.xml", "/vast?", "adtag", "/ad.php?", "/click.php?", "/clickunder"
    )

    // Removed generic "analytics.js" and "ads.js" from here, as it breaks sites.
    // Now we only block very specific known ad bait scripts.
    private val alwaysBlockParts = listOf(
        "/pagead.js", "/adsbygoogle.js", "/pagead/js/adsbygoogle", "/pagead/js/",
        "/pagead/viewthroughconversion", "/doubleclick/", "/googleadservices/", "/ads/pagead",
        "/advertisement.js", "/adsense.js", "/beacon.js", "/gpt.js", "/prebid.js",
        "blockadblock", "fuckadblock", "adblock-detect", "detectadblock", "adblockcheck"
    )

    private val youtubeAdParts = listOf(
        "/api/stats/ads", "/pagead/", "/ptracking", "/get_midroll_info", "/api/stats/atr", "adformat=", "&ad_type="
    )

    private fun hostOf(url: String?): String? =
        runCatching { Uri.parse(url ?: "").host?.lowercase(Locale.ROOT) }.getOrNull()

    fun isAdHost(host: String?): Boolean {
        var h = host?.lowercase(Locale.ROOT) ?: return false
        while (true) {
            if (h in blockedHosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun blockedHostsJson(): String {
        val obj = org.json.JSONObject()
        blockedHosts.forEach { obj.put(it, true) }
        return obj.toString()
    }

    private fun sameSite(a: String, b: String) = a == b || a.endsWith(".$b") || b.endsWith(".$a")

    // Removed the aggressive `isMaliciousMainFrameRedirect` entirely. 
    // Shortlinks (bitly, gofile) require cross-domain navigation to work. 
    // The pop-up blocker and ad-host blocker handle the spam.

    fun shouldBlock(requestUrl: String, pageUrl: String?, isMainFrame: Boolean = false): Boolean {
        val requestHost = hostOf(requestUrl) ?: return false
        val pageHost = hostOf(pageUrl)

        // 1. CRITICAL WHITELIST: Never block these or the web breaks.
        if (requestHost.endsWith("google.com") && requestUrl.contains("recaptcha")) return false
        if (requestHost == "challenges.cloudflare.com") return false
        if (requestHost.endsWith("hcaptcha.com")) return false
        if (requestHost.endsWith("jsdelivr.net") || requestHost.endsWith("googleapis.com") || requestHost.endsWith("gstatic.com")) return false
        
        // YouTube needs its APIs to load Shorts and Videos
        if (requestHost.endsWith("youtube.com") || requestHost.endsWith("googlevideo.com")) {
            if (requestUrl.contains("/youtubei/v1/player") || requestUrl.contains("/youtubei/v1/next")) return false
        }

        // 2. Block known ad hosts
        if (isAdHost(requestHost)) {
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