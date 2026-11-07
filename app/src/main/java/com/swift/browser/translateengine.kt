package com.swift.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.Locale

/** A translation problem with a message that is safe to show to the user. */
class TranslateException(message: String) : Exception(message)

/**
 * Everything about offline page translation that is not UI:
 * language codes, model download / delete, and the cached translate call.
 * Runs fully on the device (Google ML Kit). The only network use is the
 * one-time download of a language model (about 30 MB each).
 */
object TranslateEngine {
    const val MODEL_MB = 30
    private const val DOWNLOAD_TIMEOUT_MS = 180_000L
    private const val OFFLINE_CHECK_TIMEOUT_MS = 6_000L

    private val manager get() = RemoteModelManager.getInstance()
    private val translators = LinkedHashMap<String, Translator>()
    private val cache = LruCache<String, String>(3000)

    // ---------------------------------------------------------------- languages

    /** All ML Kit language codes (about 59), English included. */
    fun supportedLanguages(): List<String> = TranslateLanguage.getAllLanguages()

    /** Maps "zh-TW", "iw", "nb" ... to an ML Kit code, or null when unsupported. */
    fun normalize(raw: String): String? {
        var c = raw.trim().lowercase().replace('_', '-').substringBefore('-')
        c = when (c) {
            "iw" -> "he"
            "in" -> "id"
            "nb", "nn" -> "no"
            "fil" -> "tl"
            else -> c
        }
        if (c.isEmpty()) return null
        return TranslateLanguage.fromLanguageTag(c)
    }

    fun displayName(code: String): String {
        val n = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        val name = if (n.isNullOrBlank() || n.equals(code, ignoreCase = true)) code.uppercase() else n
        return name.replaceFirstChar { it.uppercase() }
    }

    // ---------------------------------------------------------------- network

    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    fun isOnWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }.getOrDefault(true)

    // ---------------------------------------------------------------- models

    private fun modelOf(lang: String) = TranslateRemoteModel.Builder(lang).build()

    /** Language codes whose model is on the device right now. */
    suspend fun downloadedLanguages(): Set<String> =
        manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }.toSet()

    /**
     * Downloads [lang] plus English (the bridge language ML Kit translates
     * through) and the user's target language, so it works fully offline after.
     */
    suspend fun downloadWithDependencies(lang: String, target: String) {
        val have = downloadedLanguages()
        val wanted = listOf(lang, "en", target).distinct().filter { it !in have }
        for (l in wanted) {
            try {
                withTimeout(DOWNLOAD_TIMEOUT_MS) {
                    manager.download(modelOf(l), DownloadConditions.Builder().build()).await()
                }
            } catch (e: TimeoutCancellationException) {
                throw TranslateException("Download timed out. Check your connection and try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // English / target are best effort; the language itself must succeed.
                if (l == lang) throw TranslateException("Download failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    @Synchronized
    private fun closeAll() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    suspend fun delete(lang: String) {
        closeAll() // open translators keep their models in use
        manager.deleteDownloadedModel(modelOf(lang)).await()
        cache.evictAll()
    }

    /**
     * Makes sure [src] -> [tgt] can run. Throws [TranslateException] with a clear
     * message instead of hanging (offline, Wi-Fi only, timeouts).
     */
    suspend fun ensureModels(
        context: Context,
        src: String,
        tgt: String,
        wifiOnly: Boolean,
        onDownloading: () -> Unit
    ) {
        val have = downloadedLanguages()
        val missing = listOf(src, tgt).filter { it != "en" && it !in have }
        val online = isOnline(context)
        if (missing.isNotEmpty()) {
            val names = missing.joinToString(", ") { displayName(it) }
            if (!online) {
                throw TranslateException("$names isn't downloaded. Connect once and download it in Settings > Languages.")
            }
            if (wifiOnly && !isOnWifi(context)) {
                throw TranslateException("$names needs a download. Connect to Wi-Fi, or turn off Wi-Fi only in Settings.")
            }
            onDownloading()
        }
        // All main models present and offline: this returns at once, or fails fast.
        val limit = if (missing.isEmpty() && !online) OFFLINE_CHECK_TIMEOUT_MS else DOWNLOAD_TIMEOUT_MS
        try {
            withTimeout(limit) {
                translatorFor(src, tgt).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            }
        } catch (e: TimeoutCancellationException) {
            throw TranslateException(
                if (online) "Download timed out. Check your connection and try again."
                else "A language pack is missing. Connect once and download it in Settings > Languages."
            )
        }
    }

    // ---------------------------------------------------------------- translate

    @Synchronized
    private fun translatorFor(src: String, tgt: String): Translator {
        val key = "$src>$tgt"
        translators.remove(key)?.let { translators[key] = it; return it } // mark most recent
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(tgt)
            .build()
        val t = Translation.getClient(options)
        translators[key] = t
        while (translators.size > 3) {
            val eldest = translators.keys.first()
            translators.remove(eldest)?.let { runCatching { it.close() } }
        }
        return t
    }

    suspend fun translate(src: String, tgt: String, text: String): String {
        val key = "$src>$tgt|$text"
        cache.get(key)?.let { return it }
        val out = translatorFor(src, tgt).translate(text).await()
        cache.put(key, out)
        return out
    }
}
