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
    val error: String? = null
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

    var notificationsEnabled = true
    var onCompleted: ((DownloadItem) -> Unit)? = null
    var onTick: (() -> Unit)? = null

    var maxParallel: Int
        get() = prefs().getInt("max_parallel", 3)
        set(v) {
            prefs().edit().putInt("max_parallel", v.coerceIn(1, 8)).apply()
            pump()
        }

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
            DlState.RUNNING -> flags[id]?.set(1) // worker posts PAUSED when it stops
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
                Thread({ work(started) }, "swift-dl-${d.id}").start()
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

    private fun work(first: DownloadItem) {
        val id = first.id
        val flag = flags[id] ?: return
        val part = partFile(id)
        var conn: HttpURLConnection? = null
        try {
            var offset = if (part.exists()) part.length() else 0L
            conn = (URL(first.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", first.userAgent ?: android.webkit.WebSettings.getDefaultUserAgent(app))
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                first.referer?.takeIf { it.startsWith("http") }?.let { setRequestProperty("Referer", it) }
                first.cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = conn.responseCode
            if (code == 416 && offset > 0) {
                // Everything was already downloaded.
                finish(id, part, first, offset)
                return
            }
            if (code !in 200..299) throw java.io.IOException("Server returned $code")

            val append = code == 206 && offset > 0
            if (!append) offset = 0L
            val total = when {
                code == 206 -> conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                else -> conn.contentLengthLong
            }
            val serverMime = conn.contentType?.substringBefore(';')?.trim()
            postProgress(id, offset, total, 0L)

            var done = offset
            var lastPost = 0L
            var lastBytes = done
            var lastTime = System.currentTimeMillis()
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (flag.get() != 0) break
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastPost >= 400) {
                            val speed = ((done - lastBytes) * 1000L / (now - lastTime).coerceAtLeast(1))
                            lastBytes = done
                            lastTime = now
                            lastPost = now
                            postProgress(id, done, total, speed)
                        }
                    }
                }
            }
            when (flag.get()) {
                1 -> main.post { setState(id) { it.copy(state = DlState.PAUSED, speed = 0, done = done) }; after() }
                2 -> { part.delete() }
                else -> {
                    if (total > 0 && done < total) throw java.io.IOException("Connection lost")
                    finish(id, part, first.copy(mime = first.mime ?: serverMime), done)
                }
            }
        } catch (e: Exception) {
            val f = flag.get()
            if (f == 1) {
                main.post { setState(id) { it.copy(state = DlState.PAUSED, speed = 0) }; after() }
            } else if (f != 2) {
                val msg = e.message ?: "Download failed"
                main.post { setState(id) { it.copy(state = DlState.FAILED, speed = 0, error = msg) }; after() }
            }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun postProgress(id: Long, done: Long, total: Long, speed: Long) {
        main.post {
            setState(id) { it.copy(done = done, total = total, speed = speed) }
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
                        mime = mime, error = null
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
                        uri = o.str("uri"), error = o.str("err")
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

    /** Builds the single ongoing notification shown by the foreground service. */
    fun progressNotification(c: Context): Notification {
        ensureChannels(c)
        val active = items.filter { it.state == DlState.RUNNING || it.state == DlState.QUEUED }
        val running = active.filter { it.state == DlState.RUNNING }
        val known = running.filter { it.total > 0 }
        val pct = if (known.isNotEmpty()) (known.sumOf { it.done } * 100L / known.sumOf { it.total }).toInt() else -1
        val title = if (active.size <= 1) (active.firstOrNull()?.filename ?: "Downloads")
        else "Downloading ${active.size} files"
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(c, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (pct >= 0) b.setProgress(100, pct, false).setContentText("$pct%")
        else b.setProgress(0, 0, true)
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

    private fun refresh() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (DownloadEngine.activeCount() == 0) {
                DownloadEngine.onTick = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, DownloadEngine.progressNotification(this))
            }
        }, 300)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object { const val NOTIF_ID = 4711 }
}