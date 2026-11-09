package com.swift.browser

import android.content.Context
import org.json.JSONArray

class BrowserStore(context: Context) {
    private val prefs = context.getSharedPreferences("swift_browser", Context.MODE_PRIVATE)

    fun bookmarks(): List<Pair<String, String>> = readPairs("bookmarks")
    fun history(): List<Pair<String, String>> = readPairs("history")

    fun addBookmark(title: String, url: String) {
        val items = bookmarks().filterNot { it.second == url }.toMutableList()
        items.add(0, title to url)
        writePairs("bookmarks", items.take(300))
    }

    fun removeBookmark(url: String) {
        writePairs("bookmarks", bookmarks().filterNot { it.second == url })
    }

    fun isBookmarked(url: String) = bookmarks().any { it.second == url }

    fun addHistory(title: String, url: String) {
        if (url.isBlank() || url == "about:blank") return
        val items = history().filterNot { it.second == url }.toMutableList()
        items.add(0, title to url)
        writePairs("history", items.take(500))
    }

    fun clearHistory() = prefs.edit().remove("history").remove("queries").apply()

    /** Things typed into the address bar as searches (newest first), for suggestions. */
    fun queries(): List<String> {
        val raw = prefs.getString("queries", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun addQuery(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        val items = queries().filterNot { it.equals(t, ignoreCase = true) }.toMutableList()
        items.add(0, t)
        prefs.edit().putString("queries", JSONArray(items.take(200)).toString()).apply()
    }

    private fun readPairs(key: String): List<Pair<String, String>> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                obj.optString("title") to obj.optString("url")
            }
        }.getOrDefault(emptyList())
    }

    private fun writePairs(key: String, values: List<Pair<String, String>>) {
        val arr = JSONArray()
        values.forEach { (title, url) ->
            arr.put(org.json.JSONObject().put("title", title).put("url", url))
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
