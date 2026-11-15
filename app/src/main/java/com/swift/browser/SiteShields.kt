package com.swift.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Per-site Shields choices, remembered across visits (like Brave): Shields off for a site,
 * and scripts blocked for a site. Sites are matched by their registrable domain, so
 * m.example.com and www.example.com share one setting.
 */
class SiteShields(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("swift_site_shields", Context.MODE_PRIVATE)
    private val off = HashSet(sp.getStringSet("off", emptySet()) ?: emptySet())
    private val scripts = HashSet(sp.getStringSet("scripts", emptySet()) ?: emptySet())

    /** Bumped on every change so Compose screens that read it redraw. */
    var rev by mutableIntStateOf(0)
        private set

    companion object {
        fun key(hostOrUrl: String?): String {
            if (hostOrUrl.isNullOrBlank()) return ""
            val h = if (hostOrUrl.contains("://")) NavigationGuard.host(hostOrUrl) else hostOrUrl.lowercase()
            return if (h.isNullOrBlank()) "" else NavigationGuard.registrable(h)
        }
    }

    @Synchronized fun isOff(hostOrUrl: String?): Boolean {
        val k = key(hostOrUrl)
        return k.isNotEmpty() && k in off
    }

    @Synchronized fun scriptsBlocked(hostOrUrl: String?): Boolean {
        val k = key(hostOrUrl)
        return k.isNotEmpty() && k in scripts
    }

    @Synchronized fun setOff(hostOrUrl: String?, value: Boolean) {
        val k = key(hostOrUrl); if (k.isEmpty()) return
        if (value) off.add(k) else off.remove(k)
        sp.edit().putStringSet("off", HashSet(off)).apply()
        rev++
    }

    @Synchronized fun setScriptsBlocked(hostOrUrl: String?, value: Boolean) {
        val k = key(hostOrUrl); if (k.isEmpty()) return
        if (value) scripts.add(k) else scripts.remove(k)
        sp.edit().putStringSet("scripts", HashSet(scripts)).apply()
        rev++
    }
}
