package com.swift.browser

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps EasyList-style lists on disk (app data, refreshed daily) and builds a FilterEngine from them.
 * Runs on a background thread; [onReady] is called from that thread with each new engine.
 */
class FilterListManager(private val context: Context, private val onReady: (FilterEngine) -> Unit) {
    private class ListDef(val name: String, val url: String)

    private val lists = listOf(
        ListDef("easylist", "https://easylist.to/easylist/easylist.txt"),
        ListDef("easyprivacy", "https://easylist.to/easylist/easyprivacy.txt"),
        ListDef("ubo-filters", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt"),
        ListDef("ubo-badware", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt"),
        ListDef("ubo-privacy", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt"),
        // Last: exceptions that repair sites the lists above would break.
        ListDef("ubo-unbreak", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt")
    )

    private val dir = File(context.filesDir, "filterlists").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("filter_lists", Context.MODE_PRIVATE)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        Thread({
            try {
                rebuild()
                val stale = lists.any { !file(it).exists() } ||
                    System.currentTimeMillis() - prefs.getLong("last_update", 0L) > DAY_MS
                if (stale && download()) rebuild()
            } catch (e: Throwable) {
                Log.w(TAG, "filter list setup failed", e)
            }
        }, "swift-filter-lists").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun file(l: ListDef) = File(dir, l.name + ".txt")

    private fun rebuild() {
        val files = lists.map { file(it) }.filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) return
        val t0 = System.currentTimeMillis()
        val b = FilterEngine.Builder()
        for (f in files) f.bufferedReader().use { r -> b.addLines(r.lineSequence()) }
        val e = b.build()
        Log.d(TAG, "engine ready: ${e.networkRuleCount} network, ${e.cosmeticRuleCount} cosmetic rules in ${System.currentTimeMillis() - t0} ms")
        onReady(e)
    }

    /** Returns true if at least one list changed. */
    private fun download(): Boolean {
        var changed = false
        var anyOk = false
        for (l in lists) {
            try {
                val c = URL(l.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                val tag = prefs.getString("etag_" + l.name, null)
                if (tag != null && file(l).exists()) c.setRequestProperty("If-None-Match", tag)
                when (c.responseCode) {
                    200 -> {
                        val tmp = File(dir, l.name + ".tmp")
                        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        if (tmp.length() > 1000 && tmp.renameTo(file(l)).also { if (!it) { file(l).delete(); tmp.renameTo(file(l)) } }) {
                            prefs.edit().putString("etag_" + l.name, c.getHeaderField("ETag")).apply()
                            changed = true
                        } else tmp.delete()
                        anyOk = true
                    }
                    304 -> anyOk = true
                    else -> Log.w(TAG, "${l.name}: HTTP ${c.responseCode}")
                }
                c.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "${l.name} download failed: ${e.message}")
            }
        }
        if (anyOk) prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
        return changed
    }

    companion object {
        private const val TAG = "SwiftFilters"
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
