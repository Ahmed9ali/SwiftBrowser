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

    private val alwaysBlockParts = listOf(
        "/pagead.js", "/adsbygoogle.js", "/pagead/js/adsbygoogle", "/pagead/js/",
        "/pagead/viewthroughconversion", "/doubleclick/", "/googleadservices/", "/ads/pagead",
        "/advertisement.js", "/adsense.js", "/beacon.js", "/gpt.js", "/prebid.js",
        "blockadblock", "fuckadblock", "adblock-detect", "detectadblock", "adblockcheck"
    )

    private val youtubeAdParts = listOf(
        "/api/stats/ads", "/pagead/", "/ptracking", "/get_midroll_info", "/api/stats/atr", "adformat=", "&ad_type="
    )

    // Hardcoded known spam redirect hosts to immediately block the blank page issue
    private val hardcodedSpamHosts = setOf(
        "brolackview.ink", "brolackview.com", "brolack.com", "nudus.com", "nudus.net", 
        "adsterra.com", "werrefilledwit.org", "vpn-trusthub.com", "skillify.com"
    )

    private val redirectParts = listOf(
        "zoneid=", "campaignid=", "clickid=", "click_id=", "aff_sub", "subid=", "sub_id=", "pubid=", "adzone",
        "/popunder", "/popads", "/clickunder", "/pop.php", "/click.php", "/ad.php", "/redirect.php?ad",
        "utm_medium=pop", "utm_source=pop", "utm_medium=push", "utm_source=push", "tracking.php?", "/go.php?id="
    )

    private fun hostOf(url: String?): String? =
        runCatching { Uri.parse(url ?: "").host?.lowercase(Locale.ROOT) }.getOrNull()

    fun isAdHost(host: String?): Boolean {
        var h = host?.lowercase(Locale.ROOT) ?: return false
        if (h in hardcodedSpamHosts) return true
        while (true) {
            if (h in blockedHosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun hasAdParams(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return redirectParts.any { lower.contains(it) }
    }

    fun blockedHostsJson(): String {
        val obj = org.json.JSONObject()
        blockedHosts.forEach { obj.put(it, true) }
        hardcodedSpamHosts.forEach { obj.put(it, true) }
        return obj.toString()
    }

    private fun sameSite(a: String, b: String) = a == b || a.endsWith(".$b") || b.endsWith(".$a")

    fun isSpamRedirect(url: String, pageUrl: String?, hasGesture: Boolean): Boolean {
        if (hasGesture) return false // Let user-intended clicks through
        val host = hostOf(url) ?: return false
        val pageHost = hostOf(pageUrl) ?: return false
        if (sameSite(host, pageHost)) return false
        
        // Block if it goes to a known spam host or has spam parameters
        if (isAdHost(host)) return true
        val lower = url.lowercase(Locale.ROOT)
        return redirectParts.any { lower.contains(it) }
    }

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