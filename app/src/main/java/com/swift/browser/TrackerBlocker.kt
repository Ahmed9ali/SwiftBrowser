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

    fun shouldBlock(requestUrl: String, pageUrl: String?): Boolean {
        val requestHost = runCatching { Uri.parse(requestUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
            ?: return false
        val pageHost = runCatching { Uri.parse(pageUrl ?: "").host?.lowercase(Locale.ROOT) }.getOrNull()

        // Do not block first-party requests. This is deliberately conservative.
        if (pageHost != null && (requestHost == pageHost || pageHost.endsWith(".$requestHost"))) {
            return false
        }

        return blockedHosts.any { requestHost == it || requestHost.endsWith(".$it") }
    }
}
