package com.swift.browser

import androidx.compose.foundation.verticalScroll
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private val Bg = Color(0xFF101114)
private val Surface = Color(0xFF1A1C20)
private val Surface2 = Color(0xFF25282E)
private val Surface3 = Color(0xFF30343B)
private val Accent = Color(0xFFFF6B35)
private val AccentSoft = Color(0x33FF6B35)
private val TextMain = Color(0xFFF4F4F5)
private val TextMuted = Color(0xFF9A9DA5)
private val Green = Color(0xFF65C98A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 17, 20)
        window.navigationBarColor = android.graphics.Color.rgb(16, 17, 20)
        setContent { SwiftBrowserApp() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SwiftBrowserApp() {
    val context = LocalContext.current
    val store = remember { BrowserStore(context) }
    val blocker = remember { TrackerBlocker(context) }
    val idCounter = remember { AtomicLong(2) }
    val tabs = remember { mutableStateListOf(BrowserTab(1)) }
    var activeId by remember { mutableLongStateOf(1L) }
    var address by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("Swift Browser") }
    var pageUrl by remember { mutableStateOf("swift://home") }
    var blockedCount by remember { mutableIntStateOf(0) }
    var blockingEnabled by remember { mutableStateOf(true) }
    var privateMode by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showShield by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val currentBlockingEnabled by rememberUpdatedState(blockingEnabled)
    val currentPrivateMode by rememberUpdatedState(privateMode)
    val isHome = pageUrl == "swift://home"
    var bookmarkRefresh by remember { mutableIntStateOf(0) }
    val isBookmarked = remember(pageUrl, bookmarkRefresh) { store.isBookmarked(pageUrl) }

    fun updateActiveTab(url: String = pageUrl, title: String = pageTitle, count: Int = blockedCount) {
        val index = tabs.indexOfFirst { it.id == activeId }
        if (index >= 0) tabs[index] = tabs[index].copy(url = url, title = title, blockedCount = count)
    }

    fun normalize(input: String): String {
        val value = input.trim()
        if (value.isEmpty()) return "https://search.brave.com"
        if (value.contains(" ") || (!value.contains(".") && !value.startsWith("http"))) {
            return "https://search.brave.com/search?q=" + Uri.encode(value)
        }
        return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    }

    fun load(input: String) {
        val value = input.trim()
        if (value == "swift://home") {
            pageUrl = "swift://home"
            address = ""
            pageTitle = "New tab"
            blockedCount = 0
            updateActiveTab("swift://home", "New tab", 0)
            return
        }
        val url = normalize(value)
        pageUrl = url
        address = url
        blockedCount = 0
        pageTitle = url
        updateActiveTab(url, pageTitle, 0)
        webViewRef?.loadUrl(url)
        showSuggestions = false
        showTabs = false
    }

    fun newTab() {
        val tab = BrowserTab(idCounter.getAndIncrement())
        tabs.add(tab)
        activeId = tab.id
        pageUrl = "swift://home"
        address = ""
        pageTitle = "New tab"
        blockedCount = 0
        showTabs = false
    }

    fun selectTab(tab: BrowserTab) {
        activeId = tab.id
        pageUrl = tab.url
        address = if (tab.url == "swift://home") "" else tab.url
        pageTitle = tab.title
        blockedCount = tab.blockedCount
        showTabs = false
        if (tab.url != "swift://home") webViewRef?.loadUrl(tab.url)
    }

    fun closeTab(tab: BrowserTab) {
        if (tabs.size == 1) {
            tabs[0] = BrowserTab(tab.id)
            selectTab(tabs[0])
            return
        }
        val index = tabs.indexOfFirst { it.id == tab.id }
        tabs.removeAll { it.id == tab.id }
        if (activeId == tab.id) {
            val next = tabs.getOrNull((index - 1).coerceAtLeast(0)) ?: tabs.first()
            selectTab(next)
        }
    }

    fun refreshNavigation() {
        canGoBack = webViewRef?.canGoBack() == true
        canGoForward = webViewRef?.canGoForward() == true
    }

    BackHandler(enabled = showShield || showTabs || showSuggestions || (webViewRef?.canGoBack() == true)) {
        when {
            showShield -> showShield = false
            showTabs -> showTabs = false
            showSuggestions -> showSuggestions = false
            else -> {
                webViewRef?.goBack()
                refreshNavigation()
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, background = Bg, surface = Surface,
        onBackground = TextMain, onSurface = TextMain
    )) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            // Browser top bar and interactive address field.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(35.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Accent, Color(0xFFE94D25)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("S", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(9.dp))
                Row(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(24.dp))
                        .background(Surface2).padding(start = 12.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isHome) Icons.Default.Search else Icons.Default.Lock,
                        contentDescription = "Address",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    BasicTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            showSuggestions = it.isNotBlank()
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { load(address) }),
                        decorationBox = { inner ->
                            if (address.isEmpty()) Text(
                                if (isHome) "Search or enter address" else "Search or enter address",
                                color = TextMuted, fontSize = 13.sp
                            )
                            inner()
                        }
                    )
                    if (address.isNotEmpty()) {
                        IconButton(onClick = { address = ""; showSuggestions = false }, modifier = Modifier.size(31.dp)) {
                            Icon(Icons.Default.Close, "Clear address", tint = TextMuted, modifier = Modifier.size(17.dp))
                        }
                    } else if (!isHome) {
                        IconButton(onClick = {
                            runCatching {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", pageUrl))
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            }
                        }, modifier = Modifier.size(31.dp)) {
                            Icon(Icons.Default.ContentCopy, "Copy URL", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                    IconButton(
                        onClick = { load(address) },
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(Accent)
                    ) {
                        Icon(Icons.Default.ArrowForward, "Go", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(5.dp))
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.MoreVert, "Menu", tint = TextMain)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(Surface)) {
                        BrowserMenuItem("Bookmarks", Icons.Default.Bookmark) { showMenu = false; showTabs = false; showShield = false; address = "swift://bookmarks"; showSuggestions = false; }
                        BrowserMenuItem("History", Icons.Default.History) { showMenu = false; address = "swift://history"; showSuggestions = false }
                        BrowserMenuItem("Downloads", Icons.Default.Download) {
                            showMenu = false
                            runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                        }
                        BrowserMenuItem("Share page", Icons.Default.Share) {
                            showMenu = false
                            sharePage(context, pageUrl)
                        }
                        BrowserMenuItem("Privacy dashboard", Icons.Default.Shield) { showMenu = false; showShield = true }
                    }
                }
            }

            // Small protection strip opens the detailed shield dashboard.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .clickable { showShield = true }.padding(horizontal = 5.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shield, null, tint = if (blockingEnabled) Accent else TextMuted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (blockingEnabled) "$blockedCount blocked on this page" else "Shields paused",
                        color = TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text("SHIELDS", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(15.dp))
                }
                if (!isHome && pageUrl.startsWith("http")) {
                    IconButton(
                        onClick = {
                            if (isBookmarked) store.removeBookmark(pageUrl)
                            else store.addBookmark(pageTitle, pageUrl)
                            bookmarkRefresh++
                            Toast.makeText(context, if (isBookmarked) "Bookmark removed" else "Bookmark saved", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Toggle bookmark",
                            tint = if (isBookmarked) Accent else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().alpha(if (isHome || showTabs || address == "swift://bookmarks" || address == "swift://history") 0f else 1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.safeBrowsingEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title ?: "Swift Browser"
                                    updateActiveTab(title = pageTitle)
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val uri = request?.url ?: return false
                                    return uri.scheme !in listOf("http", "https")
                                }
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val req = request ?: return null
                                    if (currentBlockingEnabled && blocker.shouldBlock(req.url.toString(), view?.url)) {
                                        Handler(Looper.getMainLooper()).post {
                                            blockedCount++
                                            updateActiveTab(count = blockedCount)
                                        }
                                        return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), null)
                                    }
                                    return null
                                }
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    if (url != null) {
                                        pageUrl = url
                                        address = url
                                        showSuggestions = false
                                    }
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    pageUrl = url ?: pageUrl
                                    address = pageUrl
                                    updateActiveTab(url = pageUrl, title = pageTitle, count = blockedCount)
                                    if (!currentPrivateMode) store.addHistory(pageTitle, pageUrl)
                                    refreshNavigation()
                                }
                            }
                            setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
                                runCatching {
                                    val filename = URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
                                    val request = DownloadManager.Request(Uri.parse(downloadUrl))
                                        .setMimeType(mimeType)
                                        .addRequestHeader("User-Agent", userAgent)
                                        .setTitle(filename)
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                                    (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                                    Toast.makeText(ctx, "Download started", Toast.LENGTH_SHORT).show()
                                }.onFailure { Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show() }
                            }
                            webViewRef = this
                            if (!isHome && pageUrl.startsWith("http")) loadUrl(pageUrl)
                        }
                    },
                    update = { webViewRef = it }
                )

                AnimatedVisibility(
                    visible = isHome && !showTabs && address != "swift://bookmarks" && address != "swift://history",
                    enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { it / 18 }),
                    exit = fadeOut(tween(120)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    HomeScreen(
                        onSearch = { load(it) },
                        onOpen = { load(it) },
                        bookmarks = store.bookmarks().take(5),
                        recent = store.history().take(4),
                        onShowBookmarks = { address = "swift://bookmarks" },
                        onShowHistory = { address = "swift://history" }
                    )
                }

                AnimatedVisibility(
                    visible = address == "swift://bookmarks" || address == "swift://history",
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(100)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val isBookmarks = address == "swift://bookmarks"
                    LinkLibraryPanel(
                        title = if (isBookmarks) "Bookmarks" else "History",
                        entries = if (isBookmarks) store.bookmarks() else store.history(),
                        icon = if (isBookmarks) Icons.Default.Bookmark else Icons.Default.History,
                        onOpen = { load(it) },
                        onClose = { address = ""; pageUrl = "swift://home" }
                    )
                }

                AnimatedVisibility(
                    visible = showSuggestions && address.isNotBlank(),
                    enter = fadeIn(tween(100)),
                    exit = fadeOut(tween(80)),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    val suggestions = (store.bookmarks() + store.history())
                        .distinctBy { it.second }
                        .filter { it.first.contains(address, true) || it.second.contains(address, true) }
                        .take(5)
                    if (suggestions.isNotEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                .clip(RoundedCornerShape(16.dp)).background(Surface).padding(8.dp)
                        ) {
                            suggestions.forEach { (title, url) ->
                                Row(Modifier.fillMaxWidth().clickable { load(url) }.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(title.ifBlank { url }, color = TextMain, fontSize = 13.sp, maxLines = 1)
                                        Text(url, color = TextMuted, fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showTabs,
                    enter = fadeIn(tween(150)) + slideInVertically(initialOffsetY = { it / 12 }),
                    exit = fadeOut(tween(100)) + slideOutVertically(targetOffsetY = { it / 12 }),
                    modifier = Modifier.fillMaxSize()
                ) {
                    TabSwitcher(
                        tabs = tabs.toList(),
                        activeId = activeId,
                        onSelect = { selectTab(it) },
                        onClose = { closeTab(it) },
                        onNew = { newTab() }
                    )
                }

                AnimatedVisibility(
                    visible = showShield,
                    enter = fadeIn(tween(130)) + slideInVertically(initialOffsetY = { it / 3 }),
                    exit = fadeOut(tween(100)) + slideOutVertically(targetOffsetY = { it / 3 }),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    ShieldDashboard(
                        enabled = blockingEnabled,
                        blockedCount = blockedCount,
                        privateMode = privateMode,
                        onEnabledChange = { blockingEnabled = it },
                        onPrivateChange = {
                            privateMode = it
                            if (it) {
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                            }
                        },
                        onClose = { showShield = false }
                    )
                }
            }

            // Brave-inspired bottom toolbar with clear touch targets.
            Row(
                Modifier.fillMaxWidth().background(Surface).padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarButton(Icons.Default.ArrowBack, "Back", canGoBack && !isHome) {
                    webViewRef?.goBack(); refreshNavigation()
                }
                ToolbarButton(Icons.Default.ArrowForward, "Forward", canGoForward && !isHome) {
                    webViewRef?.goForward(); refreshNavigation()
                }
                ToolbarButton(Icons.Default.Home, "Home") { load("swift://home") }
                ToolbarButton(Icons.Default.Refresh, "Reload", !isHome) { webViewRef?.reload() }
                ToolbarButton(Icons.Default.Add, "New tab") { newTab() }
                ToolbarButton(Icons.Default.Tab, "${tabs.size} tabs", selected = showTabs) {
                    showTabs = !showTabs
                    showShield = false
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    bookmarks: List<Pair<String, String>>,
    recent: List<Pair<String, String>>,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(
            Brush.verticalGradient(listOf(Color(0xFF17191E), Bg, Bg))
        ).padding(horizontal = 20.dp).padding(top = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GOOD TO SEE YOU", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Browse freely.", color = TextMain, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Text("A faster, quieter web starts here.", color = TextMuted, fontSize = 13.sp)
            }
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(17.dp))
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFFB83F25)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Surface2)
                .clickable { onSearch("https://search.brave.com") }.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = TextMuted)
            Spacer(Modifier.width(10.dp))
            Text("Search the web privately", color = TextMuted, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.ArrowForward, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.height(25.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quick access", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onShowBookmarks) { Text("Edit", color = Accent, fontSize = 12.sp) }
        }
        val shortcuts = listOf(
            Triple("Brave", "search.brave.com", "https://search.brave.com"),
            Triple("YouTube", "youtube.com", "https://youtube.com"),
            Triple("Wikipedia", "wikipedia.org", "https://wikipedia.org"),
            Triple("GitHub", "github.com", "https://github.com")
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            shortcuts.forEach { (name, host, url) ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).background(Surface)
                        .clickable { onOpen(url) }.padding(vertical = 13.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(37.dp).clip(CircleShape).background(Surface3), contentAlignment = Alignment.Center) {
                        Text(name.take(1), color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(name, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(25.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your bookmarks", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onShowBookmarks) { Text("See all", color = Accent, fontSize = 12.sp) }
        }
        if (bookmarks.isEmpty()) {
            EmptyHomeCard(Icons.Default.BookmarkBorder, "Your favorite sites live here", "Bookmark a page to add it to quick access.")
        } else {
            bookmarks.take(3).forEach { (title, url) ->
                HomeLinkRow(title.ifBlank { url }, url, Icons.Default.Bookmark, onOpen)
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recently visited", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onShowHistory) { Text("History", color = Accent, fontSize = 12.sp) }
        }
        if (recent.isEmpty()) {
            EmptyHomeCard(Icons.Default.Explore, "Nothing here yet", "Your recent pages will appear after you browse.")
        } else {
            recent.take(3).forEach { (title, url) ->
                HomeLinkRow(title.ifBlank { url }, url, Icons.Default.History, onOpen)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Shield, null, tint = Green, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text("Private by design • Lightweight by default", color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun EmptyHomeCard(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun HomeLinkRow(title: String, url: String, icon: ImageVector, onOpen: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(url) }
            .padding(vertical = 7.dp, horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(33.dp).clip(RoundedCornerShape(10.dp)).background(Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 12.sp, maxLines = 1)
            Text(url, color = TextMuted, fontSize = 10.sp, maxLines = 1)
        }
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ShieldDashboard(
    enabled: Boolean,
    blockedCount: Int,
    privateMode: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPrivateChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp))
            .background(Surface).padding(horizontal = 20.dp).padding(top = 13.dp, bottom = 22.dp)
    ) {
        Box(Modifier.width(38.dp).height(4.dp).clip(CircleShape).background(Surface3).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(15.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shield, null, tint = Accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Privacy dashboard", color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("Protection for this browsing session", color = TextMuted, fontSize = 11.sp)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = TextMuted) }
        }
        Spacer(Modifier.height(17.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldStat("TRACKERS BLOCKED", blockedCount.toString(), Modifier.weight(1f))
            ShieldStat("PROTECTION", if (enabled) "On" else "Off", Modifier.weight(1f))
            ShieldStat("PRIVATE MODE", if (privateMode) "On" else "Off", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Surface3.copy(alpha = .7f))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Block, null, tint = Accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ad & tracker blocking", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Blocks known tracking domains from the local list", color = TextMuted, fontSize = 10.sp)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        HorizontalDivider(color = Surface3.copy(alpha = .7f))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VisibilityOff, null, tint = Accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Private browsing", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Avoids saving new history; clears cookies when enabled", color = TextMuted, fontSize = 10.sp)
            }
            Switch(checked = privateMode, onCheckedChange = onPrivateChange)
        }
        Spacer(Modifier.height(6.dp))
        Text("Note: filtering is a lightweight host list, not full EasyList or uBlock-level protection.",
            color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun ShieldStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(Surface2).padding(11.dp)) {
        Text(value, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TabSwitcher(
    tabs: List<BrowserTab>,
    activeId: Long,
    onSelect: (BrowserTab) -> Unit,
    onClose: (BrowserTab) -> Unit,
    onNew: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 17.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tabs", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("${tabs.size} open ${if (tabs.size == 1) "tab" else "tabs"}", color = TextMuted, fontSize = 12.sp)
            }
            FilledTonalButton(onClick = onNew, colors = ButtonDefaults.filledTonalButtonColors(containerColor = AccentSoft, contentColor = Accent)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(5.dp)); Text("New tab")
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = tabs,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false) },
            label = "tab-grid"
        ) { currentTabs ->
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(currentTabs, key = { it.id }) { tab ->
                    val selected = tab.id == activeId
                    val elevation by animateDpAsState(if (selected) 4.dp else 0.dp, label = "tab-elevation")
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                            .background(if (selected) Color(0xFF30251F) else Surface)
                            .clickable { onSelect(tab) }.padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(29.dp).clip(RoundedCornerShape(9.dp)).background(Surface3), contentAlignment = Alignment.Center) {
                                Icon(if (tab.url == "swift://home") Icons.Default.Home else Icons.Default.Language,
                                    null, tint = Accent, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tab.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(if (tab.url == "swift://home") "New tab" else tab.url, color = TextMuted, fontSize = 10.sp, maxLines = 1)
                            }
                            if (selected) Text("ACTIVE", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { onClose(tab) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "Close tab", tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (tab.blockedCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text("${tab.blockedCount} trackers blocked", color = Green, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkLibraryPanel(
    title: String,
    entries: List<Pair<String, String>>,
    icon: ImageVector,
    onOpen: (String) -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Bg).padding(17.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextMain, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = TextMain) }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing saved here yet", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn {
                items(entries) { (name, url) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(url) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null, tint = Accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name.ifBlank { url }, color = TextMain, fontSize = 13.sp, maxLines = 1)
                            Text(url, color = TextMuted, fontSize = 10.sp, maxLines = 1)
                        }
                        Icon(Icons.Default.OpenInBrowser, null, tint = TextMuted)
                    }
                    HorizontalDivider(color = Surface3.copy(alpha = .45f))
                }
            }
        }
    }
}

@Composable
private fun BrowserMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = TextMain) },
        leadingIcon = { Icon(icon, null, tint = TextMuted) },
        onClick = onClick
    )
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        Modifier.clip(RoundedCornerShape(11.dp))
            .background(if (selected) AccentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) (if (selected) Accent else TextMain) else TextMuted.copy(alpha = .35f),
            modifier = Modifier.size(21.dp))
        Text(label, color = if (selected) Accent else TextMuted, fontSize = 9.sp, maxLines = 1)
    }
}

private fun sharePage(context: Context, url: String) {
    if (!url.startsWith("http")) return
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(send, "Share page"))
    }
}
