package com.swift.browser

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Bridge between the injected page script (TRANSLATE_JS) and the on-device
 * translator. The page sends small batches of text; results go straight back.
 * Registered on each tab's WebView under the name "SwiftTranslator".
 */
class WebAppInterface(
    private val webView: WebView,
    private val getTarget: () -> String,
    private val onFirstApplied: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastErrorAt = 0L

    @JavascriptInterface
    fun translateBatch(id: Int, jsonTexts: String, sourceLang: String) {
        scope.launch {
            val src = TranslateEngine.normalize(sourceLang) ?: return@launch
            val tgt = getTarget()
            val texts = runCatching { JSONArray(jsonTexts) }.getOrNull() ?: return@launch
            val out = JSONArray()
            var failed = 0
            var firstError: Exception? = null

            for (i in 0 until texts.length()) {
                val original = texts.optString(i)
                val result = try {
                    TranslateEngine.translate(src, tgt, original)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    if (firstError == null) firstError = e
                    original // keep the original text for this piece
                }
                out.put(result)
            }

            if (texts.length() > 0 && failed == texts.length()) {
                val now = System.currentTimeMillis()
                if (now - lastErrorAt > 5000) {
                    lastErrorAt = now
                    onError("Translation failed: ${firstError?.message ?: "unknown error"}")
                }
            }

            runCatching {
                webView.evaluateJavascript("window.__swiftTr&&window.__swiftTr.apply($id,$out);", null)
            }
        }
    }

    /** Called by the page once the first batch has been written back. */
    @JavascriptInterface
    fun onFirstDone(sourceLang: String) {
        scope.launch { onFirstApplied(sourceLang) }
    }

    /** Stop pending work (call when the tab's WebView is destroyed). */
    fun destroy() {
        scope.cancel()
    }
}
