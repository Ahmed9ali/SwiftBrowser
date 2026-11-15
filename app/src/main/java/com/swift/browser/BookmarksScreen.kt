package com.swift.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val BBg get() = themed(0xFF101114, 0xFFF4F5F8)
private val BRow get() = themed(0xFF25282E, 0xFFE9EBF0)
private val BText get() = themed(0xFFF4F4F5, 0xFF16181C)
private val BMuted get() = themed(0xFF9A9DA5, 0xFF666B76)
private val BAccent = Color(0xFFFF6B35)
private val BMenu get() = themed(0xFF2F3137, 0xFFFFFFFF)

private object FaviconCache {
    val map = HashMap<String, Bitmap?>()
    val tried = HashSet<String>()
}

private suspend fun loadFavicon(host: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val c = URL("https://$host/favicon.ico").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        c.instanceFollowRedirects = true
        if (c.responseCode !in 200..299) return@withContext null
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }
}

@Composable
private fun SiteIcon(url: String, title: String) {
    val host = NavigationGuard.host(url) ?: ""
    var bmp by remember(host) { mutableStateOf(FaviconCache.map[host]) }
    LaunchedEffect(host) {
        if (host.isNotEmpty() && bmp == null && FaviconCache.tried.add(host)) {
            val b = loadFavicon(host)
            FaviconCache.map[host] = b
            bmp = b
        }
    }
    Box(Modifier.size(44.dp).clip(CircleShape).background(BRow), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.size(26.dp), contentScale = ContentScale.Fit)
        } else {
            val letter = (host.removePrefix("www.").firstOrNull() ?: title.firstOrNull() ?: '?').uppercaseChar().toString()
            Text(letter, color = BAccent, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Full-screen bookmarks list: site icon, title, address and a three-dot menu on each row. Back returns to the page. */
@Composable
fun BookmarksScreen(
    entries: List<Pair<String, String>>,
    onOpen: (String) -> Unit,
    onOpenNewTab: (String) -> Unit,
    onOpenPrivate: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onEdit: (oldUrl: String, title: String, url: String) -> Unit,
    onMove: (url: String, delta: Int) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }

    BackHandler(enabled = selecting) { selecting = false; selected.clear() }

    val shown = remember(entries, query) {
        val q = query.trim()
        if (q.isEmpty()) entries else entries.filter { it.first.contains(q, true) || it.second.contains(q, true) }
    }

    Column(Modifier.fillMaxSize().background(BBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp).height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (selecting) { selecting = false; selected.clear() } else onClose() }) {
                Icon(if (selecting) Icons.Default.Close else Icons.Default.ArrowBack, "Back", tint = BText)
            }
            Text(
                if (selecting) "${selected.size} selected" else "Bookmarks",
                color = BText, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
            )
            if (selecting) {
                IconButton(
                    onClick = { onDelete(selected.toSet()); selected.clear(); selecting = false },
                    enabled = selected.isNotEmpty()
                ) { Icon(Icons.Default.Delete, "Delete", tint = if (selected.isNotEmpty()) BText else BMuted) }
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(46.dp)
                .clip(RoundedCornerShape(14.dp)).background(BRow).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = BText, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(BAccent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search your bookmarks", color = BMuted, fontSize = 16.sp)
                    inner()
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (entries.isEmpty()) "No bookmarks yet" else "No matches", color = BMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(shown, key = { it.second }) { (title, url) ->
                    var menu by remember { mutableStateOf(false) }
                    val isSel = url in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (isSel) BAccent.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable {
                                if (selecting) { if (isSel) selected.remove(url) else selected.add(url) }
                                else onOpen(url)
                            }
                            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selecting) {
                            Checkbox(
                                checked = isSel,
                                onCheckedChange = { if (it) selected.add(url) else selected.remove(url) },
                                colors = CheckboxDefaults.colors(checkedColor = BAccent)
                            )
                        }
                        SiteIcon(url, title)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title.ifBlank { url }, color = BText, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                url.removePrefix("https://").removePrefix("http://").removePrefix("www."),
                                color = BMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!selecting) {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = BText) }
                                DropdownMenu(
                                    expanded = menu, onDismissRequest = { menu = false },
                                    containerColor = BMenu, shape = RoundedCornerShape(18.dp)
                                ) {
                                    @Composable
                                    fun item(label: String, action: () -> Unit) =
                                        DropdownMenuItem(
                                            text = { Text(label, color = BText, fontSize = 17.sp) },
                                            onClick = { menu = false; action() },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        )
                                    item("Select") { selecting = true; selected.clear(); selected.add(url) }
                                    item("Edit") { editing = title to url }
                                    item("Copy link") { onCopyLink(url) }
                                    item("Delete") { onDelete(setOf(url)) }
                                    item("Move up") { onMove(url, -1) }
                                    item("Move down") { onMove(url, 1) }
                                    item("Open in new tab") { onOpenNewTab(url) }
                                    item("Open in Private tab") { onOpenPrivate(url) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { (t0, u0) ->
        var t by remember(u0) { mutableStateOf(t0) }
        var u by remember(u0) { mutableStateOf(u0) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = BMenu,
            title = { Text("Edit bookmark", color = BText, fontWeight = FontWeight.Medium) },
            text = {
                Column {
                    OutlinedTextField(t, { t = it }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(u, { u = it }, label = { Text("URL") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (u.isNotBlank()) onEdit(u0, t.trim().ifBlank { u.trim() }, u.trim())
                    editing = null
                }) { Text("Save", color = BAccent) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = BMuted) } }
        )
    }
}
