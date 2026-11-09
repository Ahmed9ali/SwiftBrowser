package com.swift.browser

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DBg = Color(0xFF101114)
private val DLine = Color(0xFF30343B)
private val DText = Color(0xFFF4F4F5)
private val DMuted = Color(0xFF9A9DA5)
private val DAccent = Color(0xFFFF6B35)
private val DRing = Color(0xFFB9C3FF)

private fun kindOf(d: DownloadItem): String {
    val m = d.mime ?: ""
    val n = d.filename.lowercase()
    return when {
        m.startsWith("video/") -> "Videos"
        m.startsWith("image/") -> "Images"
        m.startsWith("audio/") -> "Audio"
        m == "application/pdf" || m.startsWith("text/") || m.contains("officedocument") ||
            listOf(".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt").any { n.endsWith(it) } -> "Documents"
        else -> "Other"
    }
}

private fun iconOf(d: DownloadItem): ImageVector {
    val m = d.mime ?: ""
    return when {
        m.startsWith("video/") -> Icons.Default.Movie
        m.startsWith("image/") -> Icons.Default.Image
        m.startsWith("audio/") -> Icons.Default.MusicNote
        m == "application/vnd.android.package-archive" -> Icons.Default.Android
        kindOf(d) == "Documents" -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}

private fun dayLabel(ts: Long): String {
    val d = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ts))
    fun key(t: Long) = java.text.SimpleDateFormat("yyyyDDD", java.util.Locale.US).format(java.util.Date(t))
    val now = System.currentTimeMillis()
    return when (key(ts)) {
        key(now) -> "Today - $d"
        key(now - 86_400_000L) -> "Yesterday - $d"
        else -> d
    }
}

/**
 * Brave-style downloads list. Tap a running download to pause it, tap it again to
 * resume; tap a finished one to open it. The 3-dot menu has the extras only.
 */
@Composable
fun DownloadsScreen(onClose: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val all = DownloadEngine.items // snapshot-state list: recomposes on every progress update
    var filter by remember { mutableStateOf("All") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Removed rows are hidden at once; the file is really deleted only after the Undo bar goes away.
    val pending = remember { mutableStateMapOf<Long, Boolean>() } // id -> also delete file
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun commit(id: Long) { pending.remove(id)?.let { DownloadEngine.remove(id, it) } }
    DisposableEffect(Unit) { onDispose { pending.keys.toList().forEach { commit(it) } } }
    val onRemove: (DownloadItem, Boolean) -> Unit = { d, deleteFile ->
        pending[d.id] = deleteFile
        scope.launch {
            val r = snack.showSnackbar(
                message = (if (deleteFile) "Deleted " else "Removed ") + d.filename,
                actionLabel = "Undo", duration = SnackbarDuration.Short
            )
            if (r == SnackbarResult.ActionPerformed) pending.remove(d.id) else commit(d.id)
        }
    }
    val totalSpace = remember {
        runCatching {
            android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).totalBytes
        }.getOrDefault(0L)
    }
    val fmt = { n: Long -> android.text.format.Formatter.formatFileSize(context, n) }

    val shown = all.filter { !pending.containsKey(it.id) }.filter {
        (filter == "All" || kindOf(it) == filter) && (query.isBlank() || it.filename.contains(query, true))
    }
    val active = shown.filter { it.state != DlState.DONE }.sortedByDescending { it.created }
    val finished = shown.filter { it.state == DlState.DONE }.sortedByDescending { it.finished }
    val byDay = LinkedHashMap<String, MutableList<DownloadItem>>()
    finished.forEach { byDay.getOrPut(dayLabel(it.finished)) { mutableListOf() }.add(it) }
    val used = all.filter { it.state == DlState.DONE && !pending.containsKey(it.id) }.sumOf { maxOf(it.total, it.done) }

    Box(Modifier.fillMaxSize().background(DBg)) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 17.dp, end = 6.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Box(Modifier.weight(1f).padding(end = 8.dp)) {
                    if (query.isEmpty()) Text("Search downloads", color = DMuted, fontSize = 18.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = DText, fontSize = 18.sp),
                        cursorBrush = SolidColor(DAccent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                }
            } else {
                Text(
                    "Downloads", color = DText, fontSize = 28.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                )
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Download settings", tint = DText) }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search downloads", tint = DText)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = DText) }
        }
        Text(
            "Using ${fmt(used)}" + (if (totalSpace > 0) " of ${fmt(totalSpace)}" else ""),
            color = DMuted, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 10.dp)
        )
        HorizontalDivider(color = DLine)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 17.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(
                "All" to null, "Videos" to Icons.Default.Movie, "Images" to Icons.Default.Image,
                "Audio" to Icons.Default.MusicNote, "Documents" to Icons.Default.Description,
                "Other" to Icons.Default.InsertDriveFile
            ).forEach { (name, icon) ->
                val on = filter == name
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier.clip(shape)
                        .border(1.dp, if (on) Color.Transparent else DLine, shape)
                        .background(if (on) Color(0xFF343A6B) else Color.Transparent)
                        .clickable { filter = name }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (on) {
                        Icon(Icons.Default.Check, null, tint = DText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    } else if (icon != null) {
                        Icon(icon, null, tint = DText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(name, color = DText, fontSize = 15.sp)
                }
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No downloads yet", color = DMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (active.isNotEmpty()) {
                    item(key = "h-now") { SectionTitle("Just now") }
                    items(active, key = { it.id }) { d -> DownloadRowItem(d, fmt, onRemove) }
                }
                byDay.forEach { (label, list) ->
                    item(key = "h-$label") { SectionTitle(label) }
                    items(list, key = { it.id }) { d -> DownloadRowItem(d, fmt, onRemove) }
                }
            }
        }
    }
    SnackbarHost(
        hostState = snack,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)
    ) { data ->
        Snackbar(
            snackbarData = data, containerColor = Color(0xFF2B2E34),
            contentColor = DText, actionColor = DRing, shape = RoundedCornerShape(16.dp)
        )
    }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, color = DText, fontSize = 20.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 17.dp, end = 17.dp, top = 20.dp, bottom = 6.dp)
    )
}

private fun etaText(d: DownloadItem): String? {
    if (d.state != DlState.RUNNING || d.total <= 0 || d.speed <= 0) return null
    val secs = ((d.total - d.done) / d.speed).coerceAtLeast(1)
    return when {
        secs < 60 -> "$secs secs left"
        secs < 3600 -> "${secs / 60} mins left"
        else -> "${secs / 3600} hrs left"
    }
}

@Composable
private fun DownloadRowItem(d: DownloadItem, fmt: (Long) -> String, onRemove: (DownloadItem, Boolean) -> Unit) {
    val context = LocalContext.current
    val done = d.state == DlState.DONE
    val failed = d.state == DlState.FAILED
    val running = d.state == DlState.RUNNING
    val frac = if (d.total > 0) (d.done.toFloat() / d.total).coerceIn(0f, 1f) else 0f
    val host = runCatching { android.net.Uri.parse(d.url).host }.getOrNull().orEmpty().removePrefix("www.")
    var menu by remember { mutableStateOf(false) }

    val subtitle = when {
        done -> fmt(maxOf(d.total, d.done)) + (if (host.isNotEmpty()) " • $host" else "")
        failed -> "Failed • tap to retry" + (if (d.done > 0) " • ${fmt(d.done)}" else "")
        d.state == DlState.PAUSED -> "Paused • " + (if (d.total > 0) "${fmt(d.done)}/${fmt(d.total)}" else fmt(d.done))
        d.state == DlState.QUEUED -> "Waiting…"
        d.total > 0 -> "${fmt(d.done)}/${fmt(d.total)}" + (etaText(d)?.let { " • $it" } ?: "")
        else -> fmt(d.done)
    }

    Row(
        Modifier.fillMaxWidth()
            .clickable {
                if (done) DownloadActions.open(context, d) else DownloadEngine.toggle(d.id)
            }
            .padding(horizontal = 17.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (!done && !failed) {
                if (d.total > 0) {
                    CircularProgressIndicator(
                        progress = { frac }, modifier = Modifier.fillMaxSize(),
                        color = DRing, trackColor = DLine, strokeWidth = 3.dp
                    )
                } else if (running) {
                    CircularProgressIndicator(modifier = Modifier.fillMaxSize(), color = DRing, strokeWidth = 3.dp)
                } else {
                    CircularProgressIndicator(
                        progress = { 0f }, modifier = Modifier.fillMaxSize(),
                        color = DRing, trackColor = DLine, strokeWidth = 3.dp
                    )
                }
                // Brave: pause button while running, play when paused.
                Icon(
                    when (d.state) {
                        DlState.RUNNING -> Icons.Default.Pause
                        DlState.PAUSED -> Icons.Default.PlayArrow
                        else -> Icons.Default.Download
                    },
                    null, tint = DText, modifier = Modifier.size(24.dp)
                )
            } else {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF1B1D21)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (failed) Icons.Default.Refresh else iconOf(d), null,
                        tint = if (failed) Color(0xFFE5484D) else DText
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(d.filename, color = DText, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = DMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!done) {
            IconButton(onClick = { DownloadEngine.cancel(d.id) }) {
                Icon(Icons.Default.Close, "Cancel download", tint = DText)
            }
        } else {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = DText) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("Share", Icons.Default.Share) { menu = false; DownloadActions.share(context, d) }
                    if (d.url.startsWith("http")) {
                        MenuItem("Copy link", Icons.Default.ContentCopy) {
                            menu = false
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Link", d.url))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                    MenuItem("Remove from list", Icons.Default.PlaylistRemove) {
                        menu = false
                        onRemove(d, false)
                    }
                    MenuItem("Delete file", Icons.Default.Delete) {
                        menu = false
                        onRemove(d, true)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = DText) },
        leadingIcon = { Icon(icon, null, tint = DMuted) },
        onClick = onClick
    )
}
