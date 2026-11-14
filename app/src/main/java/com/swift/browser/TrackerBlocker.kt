package com.swift.browser

import android.content.Context
import android.net.Uri
import java.util.Locale

class TrackerBlocker(context: Context) {
    /** Filter-list engine (EasyList etc.), set by FilterListManager once lists are loaded. */
    @Volatile var engine: FilterEngine? = null

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
        if (isExemptPage(pageHost) || isExemptPage(requestHost)) return false

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

    private fun engineWhitelisted(host: String, url: String): Boolean =
        (host.endsWith("google.com") && url.contains("recaptcha")) || host == "challenges.cloudflare.com" ||
            host.endsWith("hcaptcha.com") || host.endsWith("jsdelivr.net") || host.endsWith("googleapis.com") ||
            host.endsWith("gstatic.com") || host.endsWith("ytimg.com") || host.endsWith("ggpht.com") ||
            host.endsWith("googleusercontent.com")

    /** Pages where shields must stay out of the way (speed tests measure real network traffic). */
    private val exemptPages = setOf(
        "speedtest.net", "ookla.com", "ooklaserver.net", "fast.com", "speedof.me", "nperf.com",
        "testmy.net", "speed.cloudflare.com", "fiber.google.com", "librespeed.org", "meter.net"
    )

    fun isExemptPage(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        val reg = NavigationGuard.registrable(h)
        return reg in exemptPages || h in exemptPages
    }

    fun isExemptHost(host: String?): Boolean = isExemptPage(host)

    private val entities = listOf(
        setOf("youtube.com", "ytimg.com", "ggpht.com", "googlevideo.com", "gstatic.com", "googleapis.com",
            "google.com", "googleusercontent.com", "youtu.be", "youtube-nocookie.com"),
        setOf("facebook.com", "fbcdn.net", "instagram.com", "cdninstagram.com", "fb.com"),
        setOf("twitter.com", "x.com", "twimg.com"),
        setOf("microsoft.com", "live.com", "msftauth.net", "bing.com", "office.com", "msn.com", "microsoftonline.com"),
        setOf("amazon.com", "media-amazon.com", "ssl-images-amazon.com", "amazonaws.com", "cloudfront.net")
    )

    private fun sameOwner(a: String, b: String): Boolean {
        val ra = NavigationGuard.registrable(a)
        val rb = NavigationGuard.registrable(b)
        if (ra == rb) return true
        return entities.any { ra in it && rb in it }
    }

    /**
     * Sub-resource check against the downloaded filter lists. Deliberately conservative to protect real content:
     * only third-party requests, never on exempt pages, and never on YouTube (handled by the YouTube rules above).
     */
    fun engineBlocks(url: String, pageUrl: String?, accept: String?): Boolean {
        val e = engine ?: return false
        val host = hostOf(url) ?: return false
        val pageHost = hostOf(pageUrl) ?: return false
        if (isExemptPage(pageHost) || isExemptPage(host)) return false
        if (engineWhitelisted(host, url)) return false
        if (NavigationGuard.registrable(pageHost) == "youtube.com") return false
        if (sameOwner(host, pageHost)) return false
        return e.blocks(url, pageUrl, FilterEngine.inferType(url, accept, false))
    }

    /** Navigation / popup destination check against popup rules and plain ad-host rules. */
    fun engineBlocksNavigation(url: String, pageUrl: String?): Boolean {
        val e = engine ?: return false
        val host = hostOf(url) ?: return false
        if (engineWhitelisted(host, url) || isExemptPage(host)) return false
        return e.blocksNavigation(url, pageUrl)
    }

    private fun cosmeticAllowed(host: String): Boolean =
        engine != null && !isExemptPage(host) &&
            NavigationGuard.registrable(host.lowercase(Locale.ROOT)) != "youtube.com"

    private fun safeSelectors(list: List<String>): String =
        list.filter { !it.contains('{') && !it.contains('}') && !it.contains('\n') && it.length < 300 && balanced(it) }
            .joinToString("\n")

    /** Site-specific rules + the few generic rules that cannot be indexed; sent once at document start. */
    fun cosmeticStartup(host: String): String {
        val e = engine ?: return ""
        if (!cosmeticAllowed(host)) return ""
        return safeSelectors(e.cosmeticStartup(host))
    }

    /** Generic rules for the classes/ids actually present on the page (tokensJson = [".foo","#bar"]). */
    fun cosmeticForTokens(host: String, tokensJson: String): String {
        val e = engine ?: return ""
        if (!cosmeticAllowed(host)) return ""
        val arr = org.json.JSONArray(tokensJson)
        val toks = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) toks.add(arr.getString(i))
        return safeSelectors(e.cosmeticForTokens(host, toks))
    }

    /** An unbalanced bracket or quote would swallow the CSS rules after it, so drop such selectors. */
    private fun balanced(s: String): Boolean {
        var paren = 0; var square = 0; var dq = false; var sq = false
        for (c in s) {
            when {
                c == '"' && !sq -> dq = !dq
                c == '\'' && !dq -> sq = !sq
                dq || sq -> {}
                c == '(' -> paren++
                c == ')' -> paren--
                c == '[' -> square++
                c == ']' -> square--
            }
            if (paren < 0 || square < 0) return false
        }
        return paren == 0 && square == 0 && !dq && !sq
    }
}