package com.swift.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class DlState { QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class DownloadItem(
    val id: Long,
    val url: String,
    val filename: String,
    val mime: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val cookie: String? = null,
    val state: DlState = DlState.QUEUED,
    val done: Long = 0L,
    val total: Long = -1L,
    val speed: Long = 0L,
    val created: Long = System.currentTimeMillis(),
    val finished: Long = 0L,
    val uri: String? = null,
    val error: String? = null,
    /** Bytes finished per segment when downloaded over several connections (empty = single stream). */
    val segs: List<Long> = emptyList()
)

/**
 * The browser's own download manager: several downloads at once (limit is adjustable),
 * pause / resume with HTTP Range, and a foreground service so downloads keep going
 * (with a notification) while the app is in the background.
 *
 * All state changes happen on the main thread; workers only post updates.
 */
object DownloadEngine {
    private lateinit var app: Context
    private var ready = false
    private val main = Handler(Looper.getMainLooper())

    /** Observed by the Downloads screen (Compose). */
    val items = mutableStateListOf<DownloadItem>()

    /** 0 = run, 1 = pause, 2 = cancel */
    private val flags = ConcurrentHashMap<Long, AtomicInteger>()

    /** Open connections per download, so pause/cancel can cut a blocked read immediately. */
    private val conns = ConcurrentHashMap<Long, MutableSet<HttpURLConnection>>()
    private val workers = ConcurrentHashMap<Long, Thread>()

    private fun dropConnections(id: Long) {
        conns[id]?.let { set -> for (c in set.toList()) runCatching { c.disconnect() }; set.clear() }
    }

    var notificationsEnabled = true
    var onCompleted: ((DownloadItem) -> Unit)? = null
    var onTick: (() -> Unit)? = null

    var maxParallel: Int
        get() = prefs().getInt("max_parallel", 3)
        set(v) {
            prefs().edit().putInt("max_parallel", v.coerceIn(1, 8)).apply()
            pump()
        }

    /** Connections used for one file (like IDM / ADM). 1 = off. */
    var connections: Int
        get() = prefs().getInt("connections", 6)
        set(v) { prefs().edit().putInt("connections", v.coerceIn(1, 16)).apply() }

    private fun prefs() = app.getSharedPreferences("swift_dl_engine", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        ready = true
        load()
        // Anything that was running when the app died waits for the user to resume it.
        for (i in items.indices) {
            val d = items[i]
            if (d.state == DlState.RUNNING || d.state == DlState.QUEUED) {
                items[i] = d.copy(state = DlState.PAUSED, speed = 0)
            }
        }
        save()
    }

    // ---------- public actions ----------

    fun enqueue(
        url: String, filename: String, mime: String?,
        userAgent: String?, referer: String?, cookie: String?
    ): Long {
        val id = System.currentTimeMillis()
        val item = DownloadItem(
            id = id, url = url, filename = filename, mime = mime,
            userAgent = userAgent, referer = referer, cookie = cookie
        )
        items.add(0, item)
        save()
        pump()
        return id
    }

    /** Tap on a row while downloading. */
    fun pause(id: Long) {
        val i = indexOf(id)
        if (i < 0) return
        val d = items[i]
        when (d.state) {
            DlState.RUNNING -> {
                flags[id]?.set(1)
                items[i] = d.copy(state = DlState.PAUSED, speed = 0) // show it at once; the worker unwinds in the background
                dropConnections(id)
                save()
                pump()
            }
            DlState.QUEUED -> {
                items[i] = d.copy(state = DlState.PAUSED)
                save()
                pump()
            }
            else -> {}
        }
    }

    fun resume(id: Long) {
        val i = indexOf(id)
        if (i < 0) return
        val d = items[i]
        if (d.state == DlState.PAUSED || d.state == DlState.FAILED) {
            items[i] = d.copy(state = DlState.QUEUED, error = null)
            save()
            pump()
        }
    }

    fun toggle(id: Long) {
        when (items.firstOrNull { it.id == id }?.state) {
            DlState.RUNNING, DlState.QUEUED -> pause(id)
            DlState.PAUSED, DlState.FAILED -> resume(id)
            else -> {}
        }
    }

    /** Cancels an unfinished download and removes its partial file. */
    fun cancel(id: Long) {
        flags[id]?.set(2)
        dropConnections(id)
        partFile(id).delete()
        removeItem(id)
    }

    /** Removes a finished download from the list; optionally deletes the file too. */
    fun remove(id: Long, deleteFile: Boolean) {
        val d = items.firstOrNull { it.id == id } ?: return
        if (d.state != DlState.DONE) {
            cancel(id)
            return
        }
        if (deleteFile && d.uri != null) {
            runCatching {
                if (d.uri.startsWith("file:")) File(d.uri.removePrefix("file:")).delete()
                else app.contentResolver.delete(android.net.Uri.parse(d.uri), null, null)
            }
        }
        removeItem(id)
    }

    fun activeCount() = items.count { it.state == DlState.RUNNING || it.state == DlState.QUEUED }

    // ---------- scheduling ----------

    private fun pump() {
        var running = items.count { it.state == DlState.RUNNING }
        val limit = maxParallel
        var i = items.size - 1 // oldest first
        while (i >= 0 && running < limit) {
            val d = items[i]
            if (d.state == DlState.QUEUED) {
                items[i] = d.copy(state = DlState.RUNNING, speed = 0)
                flags[d.id] = AtomicInteger(0)
                running++
                val started = items[i]
                val previous = workers[d.id]
                val t = Thread({
                    // A just-paused worker may still be flushing; never run two writers on one file.
                    if (previous != null && previous.isAlive) runCatching { previous.join(4000) }
                    work(started)
                }, "swift-dl-${d.id}")
                workers[d.id] = t
                t.start()
            }
            i--
        }
        if (activeCount() > 0) ensureService()
    }

    private fun ensureService() {
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        }
    }

    // ---------- worker (background thread) ----------

    private const val MIN_SEG = 512 * 1024L // don't split small files

    private fun open(item: DownloadItem, url: String, range: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", item.userAgent ?: android.webkit.WebSettings.getDefaultUserAgent(app))
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            item.referer?.takeIf { it.startsWith("http") }?.let { setRequestProperty("Referer", it) }
            item.cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            if (range != null) setRequestProperty("Range", range)
        }.also { c -> conns.getOrPut(item.id) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(c) }

    private fun work(first: DownloadItem) {
        val id = first.id
        val flag = flags[id] ?: return
        val part = partFile(id)
        try {
            // 1. Probe: does the server do ranges, and how big is the file?
            var url = first.url
            var total = first.total
            var ranged = first.segs.isNotEmpty()
            var serverMime: String? = null
            val canSkipProbe = !ranged && total > 0 && part.exists() && part.length() > 0
            if ((!ranged || total <= 0) && !canSkipProbe) {
                val probe = open(first, first.url, "bytes=0-0")
                try {
                    val code = probe.responseCode
                    url = probe.url.toString()
                    serverMime = probe.contentType?.substringBefore(';')?.trim()
                    if (code == 206) {
                        total = probe.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                        ranged = total > 0
                    } else if (code in 200..299) {
                        total = probe.contentLengthLong
                        ranged = false
                    } else throw java.io.IOException("Server returned $code")
                } finally { runCatching { probe.disconnect() } }
            }

            val wanted = connections
            val n = if (ranged && total >= MIN_SEG * 2) minOf(wanted.toLong(), total / MIN_SEG).toInt().coerceAtLeast(1) else 1
            if (ranged && n > 1) {
                multi(first, url, total, n, part, flag)
            } else {
                single(first, url, total, part, flag)
            }
            val done = if (part.exists()) part.length() else 0L
            when (flag.get()) {
                1 -> {} // state already posted by the downloader
                2 -> part.delete()
                else -> finish(id, part, first.copy(mime = first.mime ?: serverMime), if (total > 0) total else done)
            }
        } catch (e: Exception) {
            val f = flag.get()
            if (f == 1) {
                main.post { setState(id) { it.copy(state = DlState.PAUSED, speed = 0) }; after() }
            } else if (f != 2) {
                val msg = e.message ?: "Download failed"
                main.post { setState(id) { it.copy(state = DlState.FAILED, speed = 0, error = msg) }; after() }
            }
        }
    }

    /** One stream (server has no ranges, or file is tiny / unknown size). */
    private fun single(first: DownloadItem, url: String, total: Long, part: File, flag: AtomicInteger) {
        val id = first.id
        var offset = if (part.exists() && first.segs.isEmpty()) part.length() else 0L
        var conn = open(first, url, if (offset > 0) "bytes=$offset-" else null)
        try {
            val code = conn.responseCode
            if (code == 416 && offset > 0) return
            if (code !in 200..299) throw java.io.IOException("Server returned $code")
            val append = code == 206 && offset > 0
            if (!append) offset = 0L
            var done = offset
            var lastPost = 0L; var lastBytes = done; var lastTime = System.currentTimeMillis()
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (flag.get() == 0) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        val now = System.currentTimeMillis()
                        if (now - lastPost >= 400) {
                            val speed = (done - lastBytes) * 1000L / (now - lastTime).coerceAtLeast(1)
                            lastBytes = done; lastTime = now; lastPost = now
                            postProgress(id, done, total, speed, emptyList())
                        }
                    }
                }
            }
            if (flag.get() == 1) { main.post { setState(id) { it.copy(state = DlState.PAUSED, speed = 0, done = done, total = total) }; after() }; return }
            if (flag.get() == 0 && total > 0 && done < total) throw java.io.IOException("Connection lost")
        } finally { runCatching { conn.disconnect() } }
    }

    /** Several connections, each filling its own slice of a pre-sized file. */
    private fun multi(first: DownloadItem, url: String, total: Long, n: Int, part: File, flag: AtomicInteger) {
        val id = first.id
        val size = (total + n - 1) / n
        val starts = LongArray(n) { it * size }
        val ends = LongArray(n) { minOf((it + 1) * size, total) - 1 }
        val got = java.util.concurrent.atomic.AtomicLongArray(n)
        // Resume only if the old split matches this one.
        if (first.segs.size == n && part.exists() && part.length() == total) {
            for (i in 0 until n) got.set(i, first.segs[i].coerceIn(0, ends[i] - starts[i] + 1))
        } else {
            part.delete()
        }
        java.io.RandomAccessFile(part, "rw").use { it.setLength(total) }

        val failure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        val threads = (0 until n).map { i ->
            Thread({
                var tries = 0
                while (flag.get() == 0 && failure.get() == null) {
                    val from = starts[i] + got.get(i)
                    if (from > ends[i]) break
                    try {
                        val c = open(first, url, "bytes=$from-${ends[i]}")
                        try {
                            if (c.responseCode != 206) throw java.io.IOException("Server stopped supporting ranges (${c.responseCode})")
                            java.io.RandomAccessFile(part, "rw").use { raf ->
                                raf.seek(from)
                                val buf = ByteArray(128 * 1024)
                                c.inputStream.use { input ->
                                    while (flag.get() == 0) {
                                        val room = (ends[i] - starts[i] + 1 - got.get(i)).toInt()
                                        if (room <= 0) break
                                        val r = input.read(buf, 0, minOf(buf.size, room))
                                        if (r < 0) break
                                        raf.write(buf, 0, r)
                                        got.addAndGet(i, r.toLong())
                                    }
                                }
                            }
                        } finally { runCatching { c.disconnect() } }
                        tries = 0
                    } catch (e: Exception) {
                        if (flag.get() != 0) break
                        if (++tries >= 4) { failure.compareAndSet(null, e); break }
                        try { Thread.sleep(800L * tries) } catch (_: InterruptedException) {}
                    }
                }
            }, "swift-dl-$id-$i").apply { start() }
        }

        var lastBytes = (0 until n).sumOf { got.get(it) }
        var lastTime = System.currentTimeMillis()
        var speed = 0L
        while (threads.any { it.isAlive }) {
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
            val now = System.currentTimeMillis()
            if (now - lastTime < 350) continue
            val done = (0 until n).sumOf { got.get(it) }
            val inst = (done - lastBytes) * 1000L / (now - lastTime).coerceAtLeast(1)
            speed = if (speed == 0L) inst else (speed * 6 + inst * 4) / 10 // light smoothing
            lastBytes = done; lastTime = now
            postProgress(id, done, total, speed, (0 until n).map { got.get(it) })
        }
        threads.forEach { it.join() }
        val segsNow = (0 until n).map { got.get(it) }
        val done = segsNow.sum()
        failure.get()?.let { if (flag.get() == 0) throw it }
        when (flag.get()) {
            1 -> { main.post { setState(id) { it.copy(state = DlState.PAUSED, speed = 0, done = done, total = total, segs = segsNow) }; after() } }
            0 -> if (done < total) throw java.io.IOException("Connection lost")
        }
    }

    private var lastSave = 0L

    private fun postProgress(id: Long, done: Long, total: Long, speed: Long, segs: List<Long>) {
        main.post {
            setState(id) { it.copy(done = done, total = total, speed = speed, segs = segs) }
            val now = System.currentTimeMillis()
            if (now - lastSave > 3000) { lastSave = now; save() } // so a crash can still resume
            onTick?.invoke()
        }
    }

    /** Moves the finished file into the public Downloads folder. */
    private fun finish(id: Long, part: File, item: DownloadItem, size: Long) {
        try {
            val name = safeName(item.filename)
            val mime = item.mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: item.mime ?: "application/octet-stream"
            val finalUri: String
            var finalName = name
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = app.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("Can't create file")
                resolver.openOutputStream(uri)!!.use { out -> part.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) finalName = it.getString(0) ?: name
                }
                finalUri = uri.toString()
            } else {
                val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
                dir.mkdirs()
                var target = File(dir, name)
                var n = 1
                while (target.exists()) {
                    target = File(dir, name.substringBeforeLast('.', name) + " ($n)" +
                        (if (name.contains('.')) "." + name.substringAfterLast('.') else ""))
                    n++
                }
                part.copyTo(target, overwrite = true)
                finalName = target.name
                finalUri = "file:" + target.absolutePath
            }
            part.delete()
            main.post {
                setState(id) {
                    it.copy(
                        state = DlState.DONE, done = size, total = maxOf(size, it.total), speed = 0,
                        finished = System.currentTimeMillis(), uri = finalUri, filename = finalName,
                        mime = mime, error = null, segs = emptyList()
                    )
                }
                after()
                items.firstOrNull { it.id == id }?.let { d ->
                    notifyDone(d)
                    onCompleted?.invoke(d)
                }
            }
        } catch (e: Exception) {
            main.post {
                setState(id) { it.copy(state = DlState.FAILED, speed = 0, error = e.message ?: "Couldn't save file") }
                after()
            }
        }
    }

    // ---------- helpers ----------

    private fun after() {
        save()
        pump()
        onTick?.invoke()
    }

    private fun indexOf(id: Long) = items.indexOfFirst { it.id == id }
    private fun setState(id: Long, f: (DownloadItem) -> DownloadItem) {
        val i = indexOf(id)
        if (i >= 0) items[i] = f(items[i])
    }

    private fun removeItem(id: Long) {
        val i = indexOf(id)
        if (i >= 0) items.removeAt(i)
        flags.remove(id)
        save()
        pump()
        onTick?.invoke()
    }

    private fun partFile(id: Long): File {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "dl_parts")
        dir.mkdirs()
        return File(dir, "dl_$id.part")
    }

    private fun safeName(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download" }

    private fun save() {
        val arr = JSONArray()
        items.forEach { d ->
            arr.put(
                JSONObject().put("id", d.id).put("url", d.url).put("name", d.filename)
                    .put("mime", d.mime ?: JSONObject.NULL).put("ua", d.userAgent ?: JSONObject.NULL)
                    .put("ref", d.referer ?: JSONObject.NULL).put("cookie", d.cookie ?: JSONObject.NULL)
                    .put("state", d.state.name).put("done", d.done).put("total", d.total)
                    .put("created", d.created).put("finished", d.finished)
                    .put("uri", d.uri ?: JSONObject.NULL).put("err", d.error ?: JSONObject.NULL)
                    .put("segs", JSONArray(d.segs))
            )
        }
        prefs().edit().putString("items", arr.toString()).apply()
    }

    private fun load() {
        val raw = prefs().getString("items", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    DownloadItem(
                        id = o.getLong("id"), url = o.getString("url"), filename = o.getString("name"),
                        mime = o.str("mime"), userAgent = o.str("ua"), referer = o.str("ref"),
                        cookie = o.str("cookie"),
                        state = runCatching { DlState.valueOf(o.getString("state")) }.getOrDefault(DlState.PAUSED),
                        done = o.optLong("done"), total = o.optLong("total", -1L),
                        created = o.optLong("created"), finished = o.optLong("finished"),
                        uri = o.str("uri"), error = o.str("err"),
                        segs = o.optJSONArray("segs")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
                    )
                )
            }
        }
    }

    // ---------- notifications ----------

    const val CH_PROGRESS = "swift_dl_progress"
    const val CH_DONE = "swift_dl_done"

    fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun notifyDone(d: DownloadItem) {
        if (!notificationsEnabled) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            ensureChannels(app)
            val open = Intent(Intent.ACTION_VIEW)
                .setDataAndType(DownloadActions.uriFor(app, d), d.mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                app, d.id.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(app, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(d.filename)
                .setContentText("Download complete")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            app.getSystemService(NotificationManager::class.java).notify(d.id.toInt(), n)
        }
    }

    private fun size(c: Context, n: Long) = android.text.format.Formatter.formatFileSize(c, n)

    private fun eta(secs: Long) = when {
        secs < 60 -> "$secs secs left"
        secs < 3600 -> "${secs / 60} min ${secs % 60} secs left"
        else -> "${secs / 3600} hr ${(secs % 3600) / 60} min left"
    }

    /** Builds the single ongoing notification shown by the foreground service. */
    fun progressNotification(c: Context): Notification {
        ensureChannels(c)
        val active = items.filter { it.state == DlState.RUNNING || it.state == DlState.QUEUED }
        val running = active.filter { it.state == DlState.RUNNING }
        val known = running.filter { it.total > 0 }
        val done = known.sumOf { it.done }
        val total = known.sumOf { it.total }
        val speed = running.sumOf { it.speed }
        val pct = if (total > 0) (done * 100L / total).toInt() else -1
        val title = if (active.size <= 1) (active.firstOrNull()?.filename ?: "Downloads")
        else "Downloading ${active.size} files"
        val waiting = active.size - running.size
        val text = buildString {
            if (total > 0) append("${size(c, done)} / ${size(c, total)}") else append(size(c, running.sumOf { it.done }))
            if (speed > 0 && total > done) append(" \u2022 ").append(eta((total - done) / speed))
            if (waiting > 0) append(" \u2022 $waiting waiting")
        }
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(c, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (speed > 0) b.setSubText("${size(c, speed)}/s" + if (pct >= 0) " \u2022 $pct%" else "")
        else if (pct >= 0) b.setSubText("$pct%")
        if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }
}

object DownloadActions {
    fun uriFor(context: Context, d: DownloadItem): android.net.Uri {
        val u = d.uri ?: throw IllegalStateException("no file")
        return if (u.startsWith("file:")) {
            androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", File(u.removePrefix("file:"))
            )
        } else android.net.Uri.parse(u)
    }

    fun open(context: Context, d: DownloadItem) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uriFor(context, d), d.mime ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            android.widget.Toast.makeText(context, "Can't open this file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, d: DownloadItem) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = d.mime ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uriFor(context, d))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            android.widget.Toast.makeText(context, "Can't share this file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/** Keeps the process alive (with a progress notification) while downloads run. */
class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadEngine.init(applicationContext)
        val n = DownloadEngine.progressNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        DownloadEngine.onTick = { refresh() }
        refresh()
        return START_NOT_STICKY
    }

    private var pending = false

    private fun refresh() {
        if (pending) return
        pending = true
        handler.postDelayed({
            pending = false
            if (DownloadEngine.activeCount() == 0) {
                DownloadEngine.onTick = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, DownloadEngine.progressNotification(this))
            }
        }, 500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object { const val NOTIF_ID = 4711 }
}
