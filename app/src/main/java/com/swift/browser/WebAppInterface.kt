package com.swift.browser

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray

class WebAppInterface(
    private val webView: WebView,
    private val sourceLang: String,
    private val targetLang: String,
    private val onComplete: () -> Unit,
    private val onError: (String) -> Unit
) {
    @JavascriptInterface
    fun requestTranslation(jsonTexts: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val texts = JSONArray(jsonTexts)
                val translated = JSONArray()
                val translator = MlKitTranslator(sourceLang, targetLang)
                
                // 1. Download the offline language file
                translator.prepareModel()

                // 2. Translate each text node
                for (i in 0 until texts.length()) {
                    translated.put(translator.translate(texts.getString(i)))
                }
                translator.close()

                // 3. Send translated text back to JavaScript
                val resultJson = translated.toString()
                webView.post {
                    webView.evaluateJavascript("window.__swiftTranslateNodes($resultJson);", null)
                    onComplete()
                }
            } catch (e: Exception) {
                onError("Couldn't download translation model. Check your connection.")
            }
        }
    }
}