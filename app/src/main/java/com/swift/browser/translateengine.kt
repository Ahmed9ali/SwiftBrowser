package com.swift.browser

import android.app.DownloadManager
import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /** Download progress (0..1) per language code while its pack is being downloaded. */
    val progress = mutableStateMapOf<String, Float>()

    // ---------------------------------------------------------------- download progress

    /** ML Kit downloads its packs through Android's DownloadManager; read how far they are. */
    private fun dmFraction(context: Context): Float? = runCatching {
        val dm = context.getSystemService(DownloadManager::class.java)
        val q = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED
        )
        dm.query(q)?.use { c ->
            var so = 0L
            var tot = 0L
            while (c.moveToNext()) {
                so += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val t = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (t > 0) tot += t
            }
            if (tot > 0) (so.toFloat() / tot).coerceIn(0f, 1f) else null
        }
    }.getOrNull()

    /**
     * ML Kit starts the pack download inside Android's DownloadManager, which posts its own
     * notification. The app already shows the progress itself, so ask the system to keep these
     * downloads hidden (needs DOWNLOAD_WITHOUT_NOTIFICATION; ignored silently if the phone refuses).
     */
    private fun hideSystemNotifications(context: Context) {
        runCatching {
            val dm = context.getSystemService(DownloadManager::class.java)
            val q = DownloadManager.Query().setFilterByStatus(
                DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED
            )
            val ids = ArrayList<Long>()
            dm.query(q)?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
            }
            val base = android.net.Uri.parse("content://downloads/my_downloads")
            for (id in ids) {
                runCatching {
                    context.contentResolver.update(
                        android.content.ContentUris.withAppendedId(base, id),
                        android.content.ContentValues().apply { put("visibility", 2) }, // 2 = hidden
                        null, null
                    )
                }
            }
        }
    }

    private fun dirBytes(f: java.io.File?): Long =
        if (f == null || !f.exists()) 0L else if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirBytes(it) } ?: 0L)

    private fun storedBytes(context: Context): Long =
        dirBytes(context.filesDir) + dirBytes(context.noBackupFilesDir) + dirBytes(context.getExternalFilesDir(null))

    /**
     * Runs [block] while reporting 0..1 progress for the packs in [models] that are not on the
     * device yet: finished packs count fully, the running one from DownloadManager's byte counts
     * (or, when those are not available, from how much the app's storage has grown).
     */
    private suspend fun <T> tracking(context: Context, models: List<String>, report: (Float) -> Unit, block: suspend () -> T): T =
        coroutineScope {
            val before = runCatching { downloadedLanguages() }.getOrDefault(emptySet())
            val need = models.distinct().filter { it !in before }
            val startBytes = storedBytes(context)
            val job = launch {
                while (isActive) {
                    hideSystemNotifications(context)
                    val have = runCatching { downloadedLanguages() }.getOrDefault(before)
                    val done = need.count { it in have }
                    val total = need.size.coerceAtLeast(1)
                    val grown = ((storedBytes(context) - startBytes).toFloat() / (MODEL_MB * 1024f * 1024f * total)).coerceIn(0f, 0.97f)
                    val cur = dmFraction(context)
                    val f = if (cur != null) (done + cur.coerceAtMost(0.99f)) / total else maxOf(done.toFloat() / total, grown)
                    report(f.coerceIn(0f, 0.99f))
                    delay(300)
                }
            }
            try { block() } finally { job.cancel() }
        }

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
    suspend fun downloadWithDependencies(context: Context, lang: String, target: String) {
        val have = downloadedLanguages()
        val wanted = listOf(lang, "en", target).distinct().filter { it !in have }
        progress[lang] = 0f
        try {
            tracking(context, wanted, { progress[lang] = it }) { downloadAll(wanted, lang) }
        } finally {
            progress.remove(lang)
        }
    }

    private suspend fun downloadAll(wanted: List<String>, lang: String) {
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
        onProgress: (Float) -> Unit = {},
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
                val run: suspend () -> Unit = {
                    translatorFor(src, tgt).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                }
                if (missing.isNotEmpty()) tracking(context, listOf(src, tgt, "en"), onProgress) { run() } else run()
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
