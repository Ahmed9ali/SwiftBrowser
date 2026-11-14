package com.swift.browser

import java.net.URI
import java.util.Locale

/**
 * Adblock-Plus / uBlock-style filter list engine (EasyList, EasyPrivacy, uBO filters).
 * Pure Kotlin. Supports: network rules (||host^, |anchors, *, ^, regex), exceptions (@@),
 * options (third-party, resource types, domain=, important, popup, document), and cosmetic
 * rules (##selector, domain##selector, #@# exceptions). Anything it does not understand
 * (scriptlets, redirects, procedural selectors ...) is skipped, never half-applied.
 */
class FilterEngine private constructor(
    private val block: Index,
    private val allow: Index,
    genericCss: List<CosmeticRule>,
    private val specificCss: Map<String, List<CosmeticRule>>,
    private val cssExceptions: Map<String, List<String>>, // selector -> domains (empty list = everywhere)
    private val documentOff: Set<String>,
    private val elemhideOff: Set<String>,
    private val genericHideOff: Set<String>,
    val networkRuleCount: Int,
    val cosmeticRuleCount: Int
) {
    // Generic hide rules are indexed by their leading .class / #id so a page only receives the ones it can match.
    private val genericByKey = HashMap<String, ArrayList<CosmeticRule>>()
    private val genericAlways = ArrayList<CosmeticRule>()
    private val genericAll = genericCss

    init {
        for (r in genericCss) {
            val key = keyOf(r.selector)
            if (key == null) genericAlways.add(r) else genericByKey.getOrPut(key) { ArrayList(1) }.add(r)
        }
    }

    // ---- public API -----------------------------------------------------------------------

    /** Rules needed at document start: site-specific ones plus the few generic ones that cannot be indexed. */
    fun cosmeticStartup(host: String): List<String> {
        val h = host.lowercase(Locale.ROOT)
        if (hostInSet(h, documentOff) || hostInSet(h, elemhideOff)) return emptyList()
        val out = LinkedHashSet<String>()
        collectSpecific(h, out)
        if (!hostInSet(h, genericHideOff)) {
            var n = 0
            for (r in genericAlways) {
                if (n >= MAX_ALWAYS) break
                if (r.excluded(h) || excepted(r.selector, h)) continue
                out.add(r.selector); n++
            }
        }
        return out.toList()
    }

    /** Generic rules for the classes / ids ([tokens] look like ".foo" or "#bar") that exist on the page. */
    fun cosmeticForTokens(host: String, tokens: List<String>): List<String> {
        val h = host.lowercase(Locale.ROOT)
        if (hostInSet(h, documentOff) || hostInSet(h, elemhideOff) || hostInSet(h, genericHideOff)) return emptyList()
        val out = LinkedHashSet<String>()
        for (t in tokens) {
            genericByKey[t]?.forEach { r -> if (!r.excluded(h) && !excepted(r.selector, h)) out.add(r.selector) }
        }
        return out.toList()
    }

    private fun collectSpecific(h: String, out: MutableSet<String>) {
        var d: String? = h
        while (d != null) {
            specificCss[d]?.forEach { r ->
                if (!r.excluded(h) && !excepted(r.selector, h)) out.add(r.selector)
            }
            val dot = d.indexOf('.')
            d = if (dot < 0) null else d.substring(dot + 1)
        }
    }

    /** Should this sub-resource request be blocked? [typeMask] is a combination of T_* flags. */
    fun blocks(url: String, pageUrl: String?, typeMask: Int): Boolean {
        val pageHost = pageUrl?.let { hostOf(it) }
        if (pageHost != null && hostInSet(pageHost, documentOff)) return false
        val ctx = Ctx(url.lowercase(Locale.ROOT), hostOf(url) ?: return false, pageHost, typeMask)
        val hit = block.firstMatch(ctx) ?: return false
        if (hit.important) return true
        return allow.firstMatch(ctx) == null
    }

    /** Is this navigation / popup destination an ad? Uses popup rules and typeless host rules. */
    fun blocksNavigation(url: String, pageUrl: String?): Boolean = blocks(url, pageUrl, T_POPUP or T_DOCUMENT)

    /** CSS selectors to hide on this host (already filtered by exceptions). */
    fun cosmeticFor(host: String): List<String> {
        val h = host.lowercase(Locale.ROOT)
        if (hostInSet(h, documentOff) || hostInSet(h, elemhideOff)) return emptyList()
        val out = LinkedHashSet<String>()
        if (!hostInSet(h, genericHideOff)) {
            for (r in genericAll) {
                if (r.excluded(h)) continue
                if (excepted(r.selector, h)) continue
                out.add(r.selector)
            }
        }
        var d: String? = h
        while (d != null) {
            specificCss[d]?.forEach { r ->
                if (!r.excluded(h) && !excepted(r.selector, h)) out.add(r.selector)
            }
            val dot = d.indexOf('.')
            d = if (dot < 0) null else d.substring(dot + 1)
        }
        return out.toList()
    }

    private fun excepted(selector: String, host: String): Boolean {
        val doms = cssExceptions[selector] ?: return false
        if (doms.isEmpty()) return true
        return doms.any { host == it || host.endsWith(".$it") }
    }

    // ---- types ----------------------------------------------------------------------------

    companion object {
        const val MAX_ALWAYS = 1500
        private val KEY_RE = Regex("^([.#])([A-Za-z0-9_-]+)")

        /** '.foo ...' -> '.foo' ; null when the selector has a top-level comma or does not start with a class/id. */
        fun keyOf(sel: String): String? {
            if (sel.contains(',')) return null
            val m = KEY_RE.find(sel) ?: return null
            return m.value
        }

        const val T_SCRIPT = 1
        const val T_IMAGE = 2
        const val T_CSS = 4
        const val T_XHR = 8
        const val T_SUBDOC = 16
        const val T_FONT = 32
        const val T_MEDIA = 64
        const val T_OBJECT = 128
        const val T_PING = 256
        const val T_WS = 512
        const val T_OTHER = 1024
        const val T_POPUP = 2048
        const val T_DOCUMENT = 4096
        const val ALL_REQUEST = T_SCRIPT or T_IMAGE or T_CSS or T_XHR or T_SUBDOC or T_FONT or T_MEDIA or
            T_OBJECT or T_PING or T_WS or T_OTHER
        /** WebView gives no resource type; when we cannot tell, try the types ads usually use. */
        const val T_UNKNOWN = T_SCRIPT or T_XHR or T_OTHER or T_PING

        fun inferType(url: String, accept: String?, isMainFrame: Boolean): Int {
            if (isMainFrame) return T_DOCUMENT
            val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            val ext = path.substringAfterLast('.', "")
            val a = accept?.lowercase(Locale.ROOT) ?: ""
            return when {
                ext == "js" || ext == "mjs" -> T_SCRIPT
                ext == "css" -> T_CSS
                ext in IMG_EXT || a.startsWith("image/") -> T_IMAGE
                ext in FONT_EXT -> T_FONT
                ext in MEDIA_EXT -> T_MEDIA
                a.startsWith("text/css") -> T_CSS
                a.contains("text/html") -> T_SUBDOC
                else -> T_UNKNOWN
            }
        }

        private val IMG_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp")
        private val FONT_EXT = setOf("woff", "woff2", "ttf", "otf", "eot")
        private val MEDIA_EXT = setOf("mp4", "webm", "m3u8", "mp3", "ogg", "m4s", "ts", "mpd", "m4a", "flv")

        fun hostOf(url: String): String? = try {
            URI(url.trim()).host?.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        }

        private fun hostInSet(h: String, set: Set<String>): Boolean {
            if (set.isEmpty()) return false
            var d: String? = h
            while (d != null) {
                if (d in set) return true
                val dot = d.indexOf('.')
                d = if (dot < 0) null else d.substring(dot + 1)
            }
            return false
        }
    }

    // ---- matching internals ---------------------------------------------------------------

    private class Ctx(val url: String, val host: String, val pageHost: String?, val type: Int) {
        val thirdParty: Boolean =
            pageHost != null && NavigationGuard.registrable(host) != NavigationGuard.registrable(pageHost)
    }

    private class NetRule(
        val pattern: String,           // lowercased, options stripped
        val types: Int,
        val party: Int,                // 0 any, 1 third-party only, 2 first-party only
        val includeDomains: List<String>,
        val excludeDomains: List<String>,
        val important: Boolean,
        val hostOnly: Boolean          // plain ||host^ : no path part to check
    ) {
        var regex: Regex? = null
        var regexReady = false

        fun optionsMatch(c: Ctx): Boolean {
            if ((types and c.type) == 0) return false
            if (party == 1 && !c.thirdParty) return false
            if (party == 2 && c.thirdParty) return false
            val ph = c.pageHost
            if (includeDomains.isNotEmpty()) {
                if (ph == null || includeDomains.none { ph == it || ph.endsWith(".$it") }) return false
            }
            if (excludeDomains.isNotEmpty() && ph != null &&
                excludeDomains.any { ph == it || ph.endsWith(".$it") }) return false
            return true
        }

        fun matches(c: Ctx): Boolean {
            if (!optionsMatch(c)) return false
            if (hostOnly) return true
            if (!regexReady) { regex = compile(pattern); regexReady = true }
            return regex?.containsMatchIn(c.url) ?: false
        }
    }

    private class Index {
        val hostMap = HashMap<String, ArrayList<NetRule>>()
        val tokenMap = HashMap<String, ArrayList<NetRule>>()
        val loose = ArrayList<NetRule>()
        var size = 0

        fun add(r: NetRule, host: String?, token: String?) {
            size++
            when {
                host != null -> hostMap.getOrPut(host) { ArrayList(1) }.add(r)
                token != null -> tokenMap.getOrPut(token) { ArrayList(1) }.add(r)
                else -> loose.add(r)
            }
        }

        fun firstMatch(c: Ctx): NetRule? {
            var d: String? = c.host
            while (d != null) {
                hostMap[d]?.let { l -> for (r in l) if (r.matches(c)) return r }
                val dot = d.indexOf('.')
                d = if (dot < 0) null else d.substring(dot + 1)
            }
            var i = 0
            val u = c.url
            val n = u.length
            while (i < n) {
                while (i < n && !isAlnum(u[i])) i++
                val s = i
                while (i < n && isAlnum(u[i])) i++
                if (i - s >= 2) {
                    tokenMap[u.substring(s, i)]?.let { l -> for (r in l) if (r.matches(c)) return r }
                }
            }
            for (r in loose) if (r.matches(c)) return r
            return null
        }
    }

    private class CosmeticRule(val selector: String, val exclude: List<String>) {
        fun excluded(h: String) = exclude.isNotEmpty() && exclude.any { h == it || h.endsWith(".$it") }
    }

    // ---- builder / parser -----------------------------------------------------------------

    class Builder {
        private val block = Index()
        private val allow = Index()
        private val generic = ArrayList<CosmeticRule>()
        private val specific = HashMap<String, ArrayList<CosmeticRule>>()
        private val cssEx = HashMap<String, ArrayList<String>>()
        private val docOff = HashSet<String>()
        private val ehOff = HashSet<String>()
        private val ghOff = HashSet<String>()
        private val seenGeneric = HashSet<String>()
        private var cosmetic = 0

        fun addLines(lines: Sequence<String>): Builder {
            for (raw in lines) addLine(raw)
            return this
        }

        fun addLine(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
            if (line.contains("#?#") || line.contains("#\$#") || line.contains("#%#") || line.contains("##^")) return
            val hide = line.indexOf("##")
            val unhide = line.indexOf("#@#")
            if (hide >= 0 || unhide >= 0) { addCosmetic(line, hide, unhide); return }
            addNetwork(line)
        }

        private fun addCosmetic(line: String, hide: Int, unhide: Int) {
            val isEx = unhide >= 0 && (hide < 0 || unhide < hide)
            val idx = if (isEx) unhide else hide
            val domainPart = line.substring(0, idx)
            val sel = line.substring(idx + 3 - if (isEx) 0 else 1).trim()
            if (sel.isEmpty() || sel.startsWith("+js(") || UNSUPPORTED.any { sel.contains(it) }) return
            val inc = ArrayList<String>(); val exc = ArrayList<String>()
            if (domainPart.isNotEmpty()) {
                for (d in domainPart.split(',')) {
                    val t = d.trim().lowercase(Locale.ROOT)
                    if (t.isEmpty()) continue
                    if (t.startsWith("~")) exc.add(t.substring(1)) else inc.add(t)
                }
            }
            if (isEx) {
                cssEx.getOrPut(sel) { ArrayList() }.apply { if (inc.isEmpty()) clear().also { add("") } else addAll(inc) }
                return
            }
            cosmetic++
            val r = CosmeticRule(sel, exc)
            if (inc.isEmpty()) {
                if (exc.isEmpty()) { if (!seenGeneric.add(sel)) return }
                if (generic.size < MAX_GENERIC) generic.add(r)
            } else {
                for (d in inc) specific.getOrPut(d) { ArrayList() }.add(r)
            }
        }

        private fun addNetwork(line0: String) {
            var line = line0
            var isException = false
            if (line.startsWith("@@")) { isException = true; line = line.substring(2) }
            var opts = ""
            val isRegexRule = line.length > 2 && line.startsWith("/") && line.endsWith("/")
            if (!isRegexRule) {
                val d = line.lastIndexOf('$')
                if (d >= 0 && OPTION_CHARS.matches(line.substring(d + 1)) ) {
                    opts = line.substring(d + 1); line = line.substring(0, d)
                }
            } else {
                // regex rule possibly with options after the closing slash is not supported
            }
            if (line.isEmpty()) return

            var types = 0; var notTypes = 0; var party = 0; var important = false
            var hasType = false
            val inc = ArrayList<String>(); val exc = ArrayList<String>()
            var docException = false; var elemhide = false; var genhide = false
            if (opts.isNotEmpty()) {
                for (o0 in opts.split(',')) {
                    val o = o0.trim().lowercase(Locale.ROOT)
                    if (o.isEmpty()) continue
                    val neg = o.startsWith("~")
                    val name = if (neg) o.substring(1) else o
                    when {
                        name == "third-party" || name == "3p" -> party = if (neg) 2 else 1
                        name == "first-party" || name == "1p" -> party = if (neg) 1 else 2
                        name == "important" -> important = true
                        name == "match-case" -> {}
                        name == "all" -> { hasType = true; types = types or ALL_REQUEST or T_POPUP or T_DOCUMENT }
                        name.startsWith("domain=") || name.startsWith("from=") -> {
                            for (d in name.substringAfter('=').split('|')) {
                                val t = d.trim(); if (t.isEmpty()) continue
                                if (t.startsWith("~")) exc.add(t.substring(1)) else inc.add(t)
                            }
                        }
                        name == "elemhide" || name == "ehide" -> elemhide = true
                        name == "generichide" || name == "ghide" -> genhide = true
                        name == "document" || name == "doc" -> {
                            if (isException) docException = true
                            if (neg) notTypes = notTypes or T_DOCUMENT else { hasType = true; types = types or T_DOCUMENT }
                        }
                        typeBit(name) != 0 -> {
                            if (neg) notTypes = notTypes or typeBit(name) else { hasType = true; types = types or typeBit(name) }
                        }
                        else -> return // redirect=, csp=, removeparam=, badfilter, denyallow=, to=, ... : skip rule
                    }
                }
            }
            val hostForSpecial = hostOnlyPattern(line)
            if (isException && (docException || elemhide || genhide)) {
                if (hostForSpecial != null) {
                    if (docException) docOff.add(hostForSpecial)
                    if (elemhide) ehOff.add(hostForSpecial)
                    if (genhide) ghOff.add(hostForSpecial)
                } else if (inc.isNotEmpty()) {
                    for (d in inc) { if (docException) docOff.add(d); if (elemhide) ehOff.add(d); if (genhide) ghOff.add(d) }
                }
                return
            }
            if (elemhide || genhide) return

            val pat = line.lowercase(Locale.ROOT)
            val hostInfo = hostPart(pat)
            val hostOnly = hostInfo != null && hostInfo.second.let { it == "" || it == "^" }
            var mask = if (hasType) types else (ALL_REQUEST or (if (hostOnly && inc.isEmpty()) T_POPUP else 0))
            mask = mask and notTypes.inv()
            if (mask == 0) return
            val rule = NetRule(pat, mask, party, inc, exc, important, hostOnly)
            val idx = if (isException) allow else block
            when {
                hostInfo != null -> idx.add(rule, hostInfo.first, null)
                isRegexRule -> idx.add(rule, null, null)
                else -> idx.add(rule, null, tokenOf(pat))
            }
        }

        fun build(): FilterEngine {
            val cssEx2 = HashMap<String, List<String>>()
            for ((k, v) in cssEx) cssEx2[k] = if (v.contains("")) emptyList() else v
            return FilterEngine(block, allow, generic, specific, cssEx2, docOff, ehOff, ghOff,
                block.size + allow.size, cosmetic)
        }

        companion object {
            const val MAX_GENERIC = 6000
            private val OPTION_CHARS = Regex("^[a-zA-Z0-9_~=|,.\\-*]+$")
            private val UNSUPPORTED = listOf(
                ":has-text(", ":xpath(", ":matches-", ":upward(", ":remove(", ":style(", ":-abp-",
                ":contains(", ":watch-attr(", ":others(", ":min-text-length(", ":nth-ancestor(", ":not(:has-text"
            )

            fun typeBit(n: String): Int = when (n) {
                "script" -> T_SCRIPT
                "image" -> T_IMAGE
                "stylesheet", "css" -> T_CSS
                "xmlhttprequest", "xhr" -> T_XHR
                "subdocument", "frame" -> T_SUBDOC
                "font" -> T_FONT
                "media" -> T_MEDIA
                "object" -> T_OBJECT
                "ping" -> T_PING
                "websocket" -> T_WS
                "other" -> T_OTHER
                "popup" -> T_POPUP
                else -> 0
            }

            /** For ||host... rules: (host, rest-of-pattern) or null if the host part contains wildcards. */
            fun hostPart(p: String): Pair<String, String>? {
                if (!p.startsWith("||")) return null
                var i = 2
                while (i < p.length && (p[i].isLetterOrDigit() || p[i] == '.' || p[i] == '-')) i++
                if (i == 2) return null
                if (i < p.length && p[i] != '^' && p[i] != '/' && p[i] != '?' && p[i] != ':' && p[i] != '|') return null
                val h = p.substring(2, i)
                if (h.startsWith(".") || h.endsWith(".")) return null
                var rest = p.substring(i)
                if (rest.startsWith(":")) return null
                if (rest == "|") rest = ""
                return h to rest
            }

            fun hostOnlyPattern(line: String): String? {
                val hp = hostPart(line.lowercase(Locale.ROOT)) ?: return null
                return if (hp.second == "" || hp.second == "^") hp.first else null
            }

            /** Longest alnum run bounded on both sides by a non-wildcard char (or an anchor). */
            fun tokenOf(p: String): String? {
                var best: String? = null
                var i = 0
                while (i < p.length) {
                    if (!isAlnum(p[i])) { i++; continue }
                    val s = i
                    while (i < p.length && isAlnum(p[i])) i++
                    val e = i
                    val leftOk = if (s == 0) p.startsWith("|") else p[s - 1] != '*'
                    val rightOk = if (e == p.length) p.endsWith("|") else p[e] != '*'
                    // A run at the very start of an unanchored pattern may be the tail of a longer URL token.
                    val leftBounded = if (s == 0) p.startsWith("|") else true
                    val rightBounded = if (e == p.length) p.endsWith("|") else true
                    if (leftOk && rightOk && leftBounded && rightBounded && e - s >= 3) {
                        if (best == null || e - s > best.length) best = p.substring(s, e)
                    }
                }
                return best
            }
        }
    }
}

private fun isAlnum(c: Char) = (c in 'a'..'z') || (c in '0'..'9')

private fun compile(p0: String): Regex? {
    try {
        var p = p0
        if (p.length > 2 && p.startsWith("/") && p.endsWith("/")) return Regex(p.substring(1, p.length - 1))
        val sb = StringBuilder()
        var start = false
        if (p.startsWith("||")) { sb.append("^[a-z][a-z0-9+.\\-]*://(?:[^/?#]*\\.)?"); p = p.substring(2) }
        else if (p.startsWith("|")) { sb.append('^'); p = p.substring(1) }
        var end = false
        if (p.endsWith("|")) { end = true; p = p.substring(0, p.length - 1) }
        for (ch in p) {
            when (ch) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-z0-9_.%\\-]|$)")
                '.', '+', '?', '(', ')', '[', ']', '{', '}', '\\', '|', '$' -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        if (end) sb.append('$')
        if (start) sb.append("")
        return Regex(sb.toString())
    } catch (e: Exception) {
        return null
    }
}
