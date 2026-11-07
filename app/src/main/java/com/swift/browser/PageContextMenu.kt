package com.swift.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What was under the finger: a link, an image, or an image that is also a link. */
data class ContextTarget(val linkUrl: String?, val linkText: String?, val imageUrl: String?)

private val MenuBg = Color(0xFF2F3135)
private val MenuText = Color(0xFFF1F1F3)
private val MenuMuted = Color(0xFFA9ACB3)
private val MenuLine = Color(0xFF4A4D53)

// ------------------------------------------------------------------ helpers

private val TRACKING_PARAMS = setOf(
    "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "yclid", "mc_cid", "mc_eid", "igshid", "igsh",
    "_ga", "_gl", "ref_src", "ref_url", "ref", "spm", "si", "vero_id", "mkt_tok", "oly_enc_id", "oly_anon_id"
)

/** Link without tracking parameters (utm_*, fbclid, gclid ...). */
fun cleanLink(url: String): String = runCatching {
    val uri = Uri.parse(url)
    if (uri.query.isNullOrEmpty()) return url
    val b = uri.buildUpon().clearQuery()
    uri.queryParameterNames.forEach { name ->
        val lower = name.lowercase()
        if (!lower.startsWith("utm_") && lower !in TRACKING_PARAMS) {
            uri.getQueryParameters(name).forEach { b.appendQueryParameter(name, it) }
        }
    }
    b.build().toString()
}.getOrDefault(url)

private fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareText(context: Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Downloads the image bytes (http, https or data: URLs). Returns null when it fails. */
private suspend fun fetchBytes(url: String, referer: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            val meta = url.substring(0, comma)
            val data = url.substring(comma + 1)
            return@runCatching if (meta.contains(";base64")) Base64.decode(data, Base64.DEFAULT)
            else Uri.decode(data).toByteArray()
        }
        if (!url.startsWith("http")) return@runCatching null
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        if (referer.startsWith("http")) c.setRequestProperty("Referer", referer)
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > 25 * 1024 * 1024) return@runCatching null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()
}

private fun imageExtension(b: ByteArray): String = when {
    b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
    b.size > 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "gif"
    b.size > 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "webp"
    else -> "jpg"
}

/** Saves the image in the app cache and returns a content:// address other apps can read. */
private fun cacheImage(context: Context, bytes: ByteArray): Uri {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
    val file = File(dir, "image_${System.currentTimeMillis()}.${imageExtension(bytes)}")
    file.writeBytes(bytes)
    return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
}

private fun copyImage(context: Context, bytes: ByteArray, fallbackUrl: String) {
    val ok = runCatching {
        val uri = cacheImage(context, bytes)
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newUri(context.contentResolver, "Image", uri))
    }.isSuccess
    if (!ok) {
        copyText(context, "Image link", fallbackUrl)
        Toast.makeText(context, "Could not copy the image, copied its link instead", Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, "Image copied", Toast.LENGTH_SHORT).show()
    }
}

private fun shareImage(context: Context, bytes: ByteArray, fallbackUrl: String) {
    val ok = runCatching {
        val uri = cacheImage(context, bytes)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!ok) shareText(context, fallbackUrl)
}

// ------------------------------------------------------------------ menu

@Composable
fun PageContextMenu(
    target: ContextTarget,
    referer: String,
    onDismiss: () -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onOpenInGroup: (String) -> Unit,
    onOpenPrivate: (String) -> Unit,
    onDownload: (String) -> Unit,
    onAddBookmark: (title: String, url: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // url to isImage

    val link = target.linkUrl?.takeIf { it.startsWith("http") }
    val image = target.imageUrl?.takeIf { it.isNotBlank() }
    val linkText = target.linkText?.takeIf { it.isNotBlank() }

    LaunchedEffect(image) {
        if (image != null) {
            val bytes = fetchBytes(image, referer)
            imageBytes = bytes
            if (bytes != null) {
                thumb = withContext(Dispatchers.Default) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            }
        }
    }

    fun withImage(action: (ByteArray) -> Unit) {
        val have = imageBytes
        if (have != null) { action(have); return }
        scope.launch {
            val got = image?.let { fetchBytes(it, referer) }
            imageBytes = got
            if (got != null) action(got)
            else Toast.makeText(context, "Could not load the image", Toast.LENGTH_SHORT).show()
        }
    }

    fun done(block: () -> Unit) { block(); onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .padding(horizontal = 40.dp, vertical = 48.dp)
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MenuBg)
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}
            ) {
                // Header: thumbnail / letter, title, address.
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    val shown = thumb
                    if (image != null && link == null && shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        val seed = (link ?: image ?: "?").substringAfter("://").removePrefix("www.")
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF55585E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(seed.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        val title = when {
                            link != null && linkText != null -> linkText
                            link != null -> link.substringAfter("://").substringBefore('/')
                            else -> (image ?: "").substringBefore('?').substringAfterLast('/').ifBlank { "Image" }
                        }
                        Text(title, color = MenuText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (link != null) {
                            Text(link.substringAfter("://").removePrefix("www."), color = MenuMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                HorizontalDivider(color = MenuLine)

                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (link != null) {
                        MenuItem("Open in new tab") { done { onOpenInNewTab(link) } }
                        MenuItem("Open in new tab in group") { done { onOpenInGroup(link) } }
                        MenuItem("Open in Private tab") { done { onOpenPrivate(link) } }
                        MenuItem("Preview page") { preview = link to false }
                        MenuItem("Copy link address") {
                            done { copyText(context, "Link", link); Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show() }
                        }
                        MenuItem("Copy clean link") {
                            done { copyText(context, "Link", cleanLink(link)); Toast.makeText(context, "Clean link copied", Toast.LENGTH_SHORT).show() }
                        }
                        if (linkText != null) {
                            MenuItem("Copy link text") {
                                done { copyText(context, "Link text", linkText); Toast.makeText(context, "Text copied", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        MenuItem("Download link") { done { onDownload(link) } }
                        MenuItem("Add to bookmarks") {
                            done { onAddBookmark(linkText ?: link, link) }
                        }
                        MenuItem("Share link") { done { shareText(context, link) } }
                    }
                    if (link != null && image != null) HorizontalDivider(color = MenuLine)
                    if (image != null) {
                        if (image.startsWith("http")) {
                            MenuItem("Open image in new tab") { done { onOpenInNewTab(image) } }
                        }
                        MenuItem("Preview image") { preview = image to true }
                        MenuItem("Copy image") { withImage { done { copyImage(context, it, image) } } }
                        if (image.startsWith("http")) {
                            MenuItem("Download image") { done { onDownload(image) } }
                        }
                        MenuItem("Share image") { withImage { done { shareImage(context, it, image) } } }
                    }
                }
            }
        }
    }

    preview?.let { (url, isImage) ->
        PreviewDialog(
            url = url, isImage = isImage, imageBytes = imageBytes,
            onClose = { preview = null },
            onOpen = { preview = null; onDismiss(); onOpenInNewTab(url) }
        )
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 15.dp)
    ) {
        Text(text, color = MenuText, fontSize = 16.sp)
    }
}

// ------------------------------------------------------------------ preview

@Composable
private fun PreviewDialog(
    url: String,
    isImage: Boolean,
    imageBytes: ByteArray?,
    onClose: () -> Unit,
    onOpen: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF101114)).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Text(
                    url.substringAfter("://").removePrefix("www."),
                    color = Color(0xFFD0D2D8), fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (url.startsWith("http")) TextButton(onClick = onOpen) { Text("Open in tab", color = Color(0xFFFF6B35)) }
            }
            if (isImage) {
                val bmp = remember(imageBytes) {
                    imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = Color(0xFFFF6B35))
                    }
                }
            } else {
                var webView by remember { mutableStateOf<WebView?>(null) }
                DisposableEffect(Unit) { onDispose { webView?.destroy() } }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl(url)
                            webView = this
                        }
                    }
                )
            }
        }
    }
}
