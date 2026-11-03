package com.swift.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt

private val Bg = Color(0xFF101114)
private val Surface = Color(0xFF1A1C20)
private val Surface2 = Color(0xFF25282E)
private val Surface3 = Color(0xFF30343B)
private val Accent = Color(0xFFFF6B35)
private val AccentSoft = Color(0x33FF6B35)
private val TextMain = Color(0xFFF4F4F5)
private val TextMuted = Color(0xFF9A9DA5)
private val Green = Color(0xFF65C98A)

// Tab groups (Brave-style drag-to-group). Group ids live far above tab ids.
private const val GROUP_ID_BASE = 1_000_000L
private const val NEW_TAB_KEY = -1L
private val GroupColors = listOf(
    Color(0xFFFF6B35), Color(0xFF5B8DEF), Color(0xFF65C98A),
    Color(0xFFC98BDE), Color(0xFFE8C15A), Color(0xFF6BC5C9)
)

// One card in the tab grid: either a single tab or a group of tabs.
private data class TabEntry(val key: Long, val isGroup: Boolean, val tabs: List<BrowserTab>)

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
    val addressFocusRequester = remember {androidx.compose.ui.focus.FocusRequester()}
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val store = remember { BrowserStore(context) }
    val blocker = remember { TrackerBlocker(context) }
    val idCounter = remember { AtomicLong(2) }
    val firstPartyUrl = remember { AtomicReference("") }
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
    var addressFocused by remember { mutableStateOf(false) }
    val tabGroups = remember { mutableStateMapOf<Long, Long>() }
    var groupCounter by remember { mutableLongStateOf(GROUP_ID_BASE + 1) }
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

        if (value.isEmpty()) {
            return "swift://home"
        }

        // Real URLs go straight through.
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value
        }

        // Only navigate to a site when the input really looks like a domain
        // (e.g. "wikipedia.org"). Bare words like "google", "brave" or
        // "fydeos" and anything with spaces are search queries.
        val looksLikeDomain = value.contains(".") &&
            !value.contains(" ") &&
            value.substringAfterLast('.', "").length >= 2 &&
            value.substringAfterLast('.', "").all { it.isLetterOrDigit() }

        if (!looksLikeDomain) {
            // Search in-page with Brave Search — results render inside the
            // app instead of opening a search-engine homepage.
            return "https://search.brave.com/search?q=" + Uri.encode(value)
        }

        return "https://$value"
    }

    fun load(input: String) {
        val value = input.trim()

        if (value.isEmpty()) {
            load("swift://home")
            return
        }

        if (value.startsWith("swift://")) {
            pageUrl = value
            address = ""
            pageTitle = if (value == "swift://home") "New tab" else "Swift Browser"
            blockedCount = 0
            updateActiveTab(pageUrl, pageTitle, 0)
            showSuggestions = false
            showTabs = false
            keyboardController?.hide()
            focusManager.clearFocus()
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
        // Hide the keyboard so the loaded page / search results are
        // immediately visible — like Brave.
        keyboardController?.hide()
        focusManager.clearFocus()
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
            tabGroups.clear()
            selectTab(tabs[0])
            return
        }
        val index = tabs.indexOfFirst { it.id == tab.id }
        tabs.removeAll { it.id == tab.id }
        tabGroups.remove(tab.id)
        if (activeId == tab.id) {
            val next = tabs.getOrNull((index - 1).coerceAtLeast(0)) ?: tabs.first()
            selectTab(next)
        }
    }

    // Merge the dragged card (tab or whole group) into the drop target.
    fun handleGroupDrop(sourceKey: Long, targetKey: Long) {
        if (sourceKey == targetKey) return
        val sourceGroup = if (sourceKey >= GROUP_ID_BASE) sourceKey else tabGroups[sourceKey]
        val targetGroup = if (targetKey >= GROUP_ID_BASE) targetKey else tabGroups[targetKey]

        when {
            // Group onto group / grouped tab: merge both groups together.
            sourceGroup != null && targetGroup != null && sourceGroup != targetGroup ->
                tabGroups.keys.filter { tabGroups[it] == sourceGroup }.forEach {
                    tabGroups[it] = targetGroup
                }
            // Loose tab onto an existing group: join it.
            sourceGroup == null && targetGroup != null ->
                tabGroups[sourceKey] = targetGroup
            // Loose tab onto loose tab: create a new group with both.
            sourceGroup == null && targetGroup == null && targetKey != NEW_TAB_KEY -> {
                val gid = groupCounter++
                tabGroups[sourceKey] = gid
                tabGroups[targetKey] = gid
            }
            // Group onto a loose tab: pull that tab into the group.
            sourceGroup != null && targetGroup == null ->
                tabGroups[targetKey] = sourceGroup
        }
    }

    // Dropped on empty space: drag a tab out of its group / dissolve a group.
    fun handleDropOutside(key: Long) {
        if (key >= GROUP_ID_BASE) {
            tabGroups.keys.filter { tabGroups[it] == key }.forEach { tabGroups.remove(it) }
        } else {
            tabGroups.remove(key)
        }
    }

    // Group-icon action: collect every loose tab into one group.
    fun groupAllTabs() {
        val loose = tabs.filter { tabGroups[it.id] == null }
        if (loose.size < 2) {
            Toast.makeText(context, "Nothing to group", Toast.LENGTH_SHORT).show()
            return
        }
        val gid = groupCounter++
        loose.forEach { tabGroups[it.id] = gid }
        Toast.makeText(context, "Grouped ${loose.size} tabs", Toast.LENGTH_SHORT).show()
    }

    // Delete-all action: closes every tab and lands on a fresh home tab.
    fun closeAllTabs() {
        tabs.clear()
        tabGroups.clear()
        val fresh = BrowserTab(idCounter.getAndIncrement())
        tabs.add(fresh)
        selectTab(fresh)
        showTabs = false
        Toast.makeText(context, "All tabs closed", Toast.LENGTH_SHORT).show()
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

    // Settings menu, shared by the normal bottom bar and the tab-overview bar.
    @Composable
    fun SettingsMenuBox() {
        Box {
            ToolbarButton(
                Icons.Default.Settings,
                "Settings",
                selected = showMenu
            ) {
                showMenu = true
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(Surface)
            ) {
                BrowserMenuItem("Bookmarks", Icons.Default.Bookmark) {
                    showMenu = false
                    showTabs = false
                    showShield = false
                    address = "swift://bookmarks"
                    showSuggestions = false
                }
                BrowserMenuItem("History", Icons.Default.History) {
                    showMenu = false
                    address = "swift://history"
                    showSuggestions = false
                }
                BrowserMenuItem("Downloads", Icons.Default.Download) {
                    showMenu = false
                    runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                }
                BrowserMenuItem("Share page", Icons.Default.Share) {
                    showMenu = false
                    sharePage(context, pageUrl)
                }
                BrowserMenuItem("Privacy dashboard", Icons.Default.Shield) {
                    showMenu = false
                    showShield = true
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, background = Bg, surface = Surface,
            onBackground = TextMain, onSurface = TextMain
        )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Browser top bar: just the address pill, app logo inside it
            // (like Brave's lion inside its omnibox).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(24.dp))
                        .background(if (addressFocused) Surface3 else Surface2)
                        .padding(start = 8.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // App logo inside the search bar.
                    Box(
                        Modifier.size(29.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Accent, Color(0xFFE94D25)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("S", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(9.dp))
                    BasicTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            showSuggestions = it.isNotBlank()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(addressFocusRequester)
                            .onFocusChanged { addressFocused = it.isFocused },
                        singleLine = true,
                        textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { load(address) }),
                        decorationBox = { inner ->
                            if (address.isEmpty()) {
                                Text("Search Brave or type URL", color = TextMuted, fontSize = 13.sp)
                            }
                            inner()
                        }
                    )
                    if (address.isNotEmpty()) {
                        IconButton(
                            onClick = { address = ""; showSuggestions = false },
                            modifier = Modifier.size(31.dp)
                        ) {
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
                    Icon(
                        Icons.Default.Shield, null,
                        tint = if (blockingEnabled) Accent else TextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (blockingEnabled) "$blockedCount blocked on this page" else "Shields paused",
                        color = TextMuted, fontSize = 11.sp
                    )
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
                            Toast.makeText(
                                context,
                                if (isBookmarked) "Bookmark removed" else "Bookmark saved",
                                Toast.LENGTH_SHORT
                            ).show()
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
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            // Brave-style: the page blurs behind the omnibox
                            // while typing (no-op below Android 12).
                            if (showSuggestions && !isHome) Modifier.blur(14.dp) else Modifier
                        )
                        .alpha(
                            if (isHome || showTabs || address == "swift://bookmarks" || address == "swift://history") 0f else 1f
                        ),
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                settings.safeBrowsingEnabled = true
                            }
                            settings.mediaPlaybackRequiresUserGesture = true
                            CookieManager.getInstance().setAcceptCookie(true)

                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title ?: "Swift Browser"
                                    updateActiveTab(title = pageTitle)
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val uri = request?.url ?: return false
                                    return uri.scheme !in listOf("http", "https")
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val req = request ?: return null
                                    val url = req.url.toString()

                                    if (currentBlockingEnabled && blocker.shouldBlock(url, firstPartyUrl.get())) {
                                        Handler(Looper.getMainLooper()).post {
                                            blockedCount++
                                            updateActiveTab(count = blockedCount)
                                        }
                                        return WebResourceResponse(
                                            "text/plain",
                                            "utf-8",
                                            204,
                                            "Blocked",
                                            emptyMap(),
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                    return null
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    if (url != null) {
                                        firstPartyUrl.set(url)
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
                                }.onFailure {
                                    Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show()
                                }
                            }

                            webViewRef = this
                            if (!isHome && pageUrl.startsWith("http")) loadUrl(pageUrl)
                        }
                    },
                    update = { webViewRef = it }
                )

                androidx.compose.animation.AnimatedVisibility(
                    visible = isHome && !showTabs && address != "swift://bookmarks" && address != "swift://history",
                    enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { it / 18 }),
                    exit = fadeOut(tween(120)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    HomeScreen(
                        searchActive = addressFocused || showSuggestions,
                        onFocusSearch = {
                            addressFocusRequester.requestFocus()
                            keyboardController?.show()
                        },
                        onDismissKeyboard = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        onOpen = { load(it) },
                        bookmarks = store.bookmarks().take(5),
                        recent = store.history().take(4),
                        onShowBookmarks = { address = "swift://bookmarks" },
                        onShowHistory = { address = "swift://history" }
                    )
                }

                // Tapping the search bar dims everything below it — on every
                // screen (home, results, tabs) and every Android version.
                // The wallpaper blur on home adds on top of this.
                androidx.compose.animation.AnimatedVisibility(
                    visible = addressFocused,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(100)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(Modifier.fillMaxSize().background(Color(0x73000000)))
                }

                androidx.compose.animation.AnimatedVisibility(
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

                androidx.compose.animation.AnimatedVisibility(
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
                                Row(
                                    Modifier.fillMaxWidth().clickable { load(url) }.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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

                androidx.compose.animation.AnimatedVisibility(
                    visible = showTabs,
                    enter = fadeIn(tween(150)) + slideInVertically(initialOffsetY = { it / 12 }),
                    exit = fadeOut(tween(100)) + slideOutVertically(targetOffsetY = { it / 12 }),
                    modifier = Modifier.fillMaxSize()
                ) {
                    TabSwitcher(
                        tabs = tabs.toList(),
                        activeId = activeId,
                        groups = tabGroups,
                        onSelect = { selectTab(it) },
                        onClose = { closeTab(it) },
                        onNew = { newTab() },
                        onGroup = { source, target -> handleGroupDrop(source, target) },
                        onDropOutside = { handleDropOutside(it) },
                        onGroupAll = { groupAllTabs() },
                        onCloseAll = { closeAllTabs() }
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
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

            // Bottom bar — Brave-style. In the tab overview it becomes the
            // overview bar: "+" centered, settings at the right.
            if (showTabs) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Surface)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    IconButton(
                        onClick = { newTab() },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Accent)
                    ) {
                        Icon(Icons.Default.Add, "New tab", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        SettingsMenuBox()
                    }
                }
            } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {

                // HOME
                ToolbarButton(
                    Icons.Default.Home,
                    "Home",
                    selected = isHome
                ) {
                    load("swift://home")
                }

                // BOOKMARKS
                ToolbarButton(
                    Icons.Default.BookmarkBorder,
                    "Bookmarks",
                    selected = address == "swift://bookmarks"
                ) {
                    address = "swift://bookmarks"
                    showTabs = false
                    showSuggestions = false
                }

                // SEARCH
                ToolbarButton(
                    Icons.Default.Search,
                    "Search"
                ) {
                    address = ""
                    showSuggestions = false
                    addressFocusRequester.requestFocus()
                    keyboardController?.show()
                }

                // TABS
                ToolbarButton(
                    Icons.Default.Tab,
                    "${tabs.size} tabs",
                    selected = showTabs
                ) {
                    showTabs = !showTabs
                    showShield = false
                    showSuggestions = false
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }

                // SETTINGS (menu lives here now, not in the top bar)
                SettingsMenuBox()
            }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    searchActive: Boolean,
    onFocusSearch: () -> Unit,
    onDismissKeyboard: () -> Unit,
    onOpen: (String) -> Unit,
    bookmarks: List<Pair<String, String>>,
    recent: List<Pair<String, String>>,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            // Tapping anywhere outside a control closes the keyboard.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onDismissKeyboard() },
                    onDoubleTap = { onDismissKeyboard() },
                    onLongPress = { onDismissKeyboard() }
                )
            }
    ) {
        WallpaperBackdrop(blurred = searchActive)
        // Reading scrim so content stays legible over the wallpaper.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0x22000000), Color(0x4D000000))
                )
            )
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 28.dp)
        ) {

        // Welcome section
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f)
            ) {
                Text(
                    "GOOD TO SEE YOU",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                Text(
                    "Browse freely.",
                    color = TextMain,
                    fontSize = 29.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "A faster, quieter web starts here.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }

            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Accent,
                                Color(0xFFB83F25)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Shield,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.height(30.dp))

        // Search box opens the address bar directly — like Brave's
        // "Search the web privately" field.
        Row(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Surface2)
                .clickable {
                    onFocusSearch()
                }
                .padding(horizontal = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                null,
                tint = TextMuted,
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(13.dp))

            Text(
                "Search the web privately",
                color = TextMuted,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )

            Icon(
                Icons.Default.ArrowForward,
                null,
                tint = TextMuted
            )
        }

        Spacer(Modifier.height(30.dp))
        }
    }
}

// Night-mountain wallpaper (no image asset needed).
// The blurred variant renders the scene into a TINY bitmap and scales it back
// up with bilinear filtering — that upscale IS the blur, and unlike
// Modifier.blur() it works on every Android version, not just Android 12+.
@Composable
private fun WallpaperBackdrop(blurred: Boolean) {
    // Sharp wallpaper — always drawn underneath.
    Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Night sky
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1C2540), Color(0xFF131826), Color(0xFF101114))
                    )
                )

                // Ambient glows
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0x59FF6B35), Color(0x00FF6B35)),
                        center = Offset(w * 0.85f, h * 0.10f),
                        radius = w * 0.55f
                    ),
                    radius = w * 0.55f,
                    center = Offset(w * 0.85f, h * 0.10f)
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0x335B8DEF), Color(0x005B8DEF)),
                        center = Offset(w * 0.12f, h * 0.30f),
                        radius = w * 0.45f
                    ),
                    radius = w * 0.45f,
                    center = Offset(w * 0.12f, h * 0.30f)
                )

                // Stars (deterministic placement)
                for (i in 0 until 34) {
                    val sx = (i * 137.508f) % w
                    val sy = (i * 79.377f) % (h * 0.52f)
                    drawCircle(
                        Color(0x59FFFFFF),
                        radius = 1.1f + (i % 3) * 0.5f,
                        center = Offset(sx, sy)
                    )
                }

                // Moon
                drawCircle(Color(0x2EF2EAD8), radius = w * 0.06f, center = Offset(w * 0.72f, h * 0.09f))
                drawCircle(Color(0x1AF2EAD8), radius = w * 0.075f, center = Offset(w * 0.72f, h * 0.09f))

                // Snow-capped ridges
                ridge(Color(0xFF26324B), 0.64f, 0.022f, 0.0f)
                ridge(Color(0xFF1D2436), 0.74f, 0.028f, 1.7f)
                ridge(Color(0xFF151A26), 0.85f, 0.034f, 3.4f)
    }

    // The opaque pre-blurred bitmap fades in ON TOP of the sharp wallpaper
    // when the search bar is active. Unlike Modifier.blur(), this renders
    // on every Android version and every phone.
    androidx.compose.animation.AnimatedVisibility(
        visible = blurred,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160))
    ) {
        val blurredBitmap = remember { renderWallpaperBitmap() }
        Image(
            bitmap = blurredBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
            filterQuality = FilterQuality.High
        )
    }
}

// Renders the wallpaper scene into a deliberately low-resolution bitmap.
private fun renderWallpaperBitmap(): Bitmap {
    val w = 64
    val h = 144
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    // Night sky
    paint.shader = android.graphics.LinearGradient(
        0f, 0f, 0f, h.toFloat(),
        0xFF1C2540.toInt(), 0xFF101114.toInt(),
        android.graphics.Shader.TileMode.CLAMP
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

    // Ambient glows
    paint.shader = android.graphics.RadialGradient(
        w * 0.85f, h * 0.10f, w * 0.55f,
        0x59FF6B35.toInt(), 0x00FF6B35.toInt(),
        android.graphics.Shader.TileMode.CLAMP
    )
    canvas.drawCircle(w * 0.85f, h * 0.10f, w * 0.55f, paint)
    paint.shader = android.graphics.RadialGradient(
        w * 0.12f, h * 0.30f, w * 0.45f,
        0x335B8DEF.toInt(), 0x005B8DEF.toInt(),
        android.graphics.Shader.TileMode.CLAMP
    )
    canvas.drawCircle(w * 0.12f, h * 0.30f, w * 0.45f, paint)
    paint.shader = null

    // Moon
    paint.color = 0x2EF2EAD8.toInt()
    canvas.drawCircle(w * 0.72f, h * 0.09f, w * 0.07f, paint)
    paint.color = 0x1AF2EAD8.toInt()
    canvas.drawCircle(w * 0.72f, h * 0.09f, w * 0.075f, paint)

    // Ridges
    drawFrameworkRidge(canvas, paint, 0xFF26324B.toInt(), 0.64f, 0.022f, 0.0f)
    drawFrameworkRidge(canvas, paint, 0xFF1D2436.toInt(), 0.74f, 0.028f, 1.7f)
    drawFrameworkRidge(canvas, paint, 0xFF151A26.toInt(), 0.85f, 0.034f, 3.4f)
    return bmp
}

private fun drawFrameworkRidge(
    canvas: android.graphics.Canvas,
    paint: android.graphics.Paint,
    color: Int,
    topFraction: Float,
    jag: Float,
    phase: Float
) {
    val w = canvas.width.toFloat()
    val h = canvas.height.toFloat()
    val base = h * topFraction
    val path = android.graphics.Path()
    path.moveTo(-10f, h + 10f)
    path.lineTo(-10f, base)
    var x = -10f
    val step = (w + 20f) / 6f
    for (i in 0 until 6) {
        val peak = base - h * jag * (1f + abs(kotlin.math.sin(i * 2.3f + phase)) * 2.2f)
        path.lineTo(x + step * 0.45f, peak)
        path.lineTo(x + step, base + h * jag * 0.5f)
        x += step
    }
    path.lineTo(w + 10f, h + 10f)
    path.close()
    paint.color = color
    canvas.drawPath(path, paint)
}

private fun DrawScope.ridge(color: Color, topFraction: Float, jag: Float, phase: Float) {
    val w = size.width
    val h = size.height
    val base = h * topFraction
    val path = Path().apply {
        moveTo(-10f, h + 10f)
        lineTo(-10f, base)
        var x = -10f
        val step = (w + 20f) / 6f
        for (i in 0 until 6) {
            val peak = base - h * jag * (1f + abs(kotlin.math.sin(i * 2.3f + phase)) * 2.2f)
            lineTo(x + step * 0.45f, peak)
            lineTo(x + step, base + h * jag * 0.5f)
            x += step
        }
        lineTo(w + 10f, h + 10f)
        close()
    }
    drawPath(path, color)
}

@Composable
private fun EmptyHomeCard(icon: ImageVector, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
        Text(
            "Note: filtering is a lightweight host list, not full EasyList or uBlock-level protection.",
            color = TextMuted, fontSize = 10.sp
        )
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
    groups: Map<Long, Long>,
    onSelect: (BrowserTab) -> Unit,
    onClose: (BrowserTab) -> Unit,
    onNew: () -> Unit,
    onGroup: (Long, Long) -> Unit,
    onDropOutside: (Long) -> Unit,
    onGroupAll: () -> Unit,
    onCloseAll: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var dragKey by remember { mutableStateOf<Long?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dragDistance by remember { mutableStateOf(0f) }
    var hoverKey by remember { mutableStateOf<Long?>(null) }
    val cardBounds = remember { mutableStateMapOf<Long, Rect>() }
    val expandedGroups = remember { mutableStateListOf<Long>() }
    var gridTopLeft by remember { mutableStateOf(Offset.Zero) }

    // Build grid entries: single tabs plus one stacked card per group.
    val entries = run {
        val list = ArrayList<TabEntry>()
        val seen = mutableSetOf<Long>()
        tabs.forEach { tab ->
            val gid = groups[tab.id]
            if (gid == null) {
                list.add(TabEntry(tab.id, false, listOf(tab)))
            } else if (seen.add(gid)) {
                list.add(TabEntry(gid, true, tabs.filter { groups[it.id] == gid }))
            }
        }
        list
    }

    val filtered = if (query.isBlank()) entries else entries.filter { e ->
        e.tabs.any { it.title.contains(query, true) || it.url.contains(query, true) }
    }

    // Clear bounds of removed cards so drop targeting stays accurate.
    LaunchedEffect(entries) {
        val valid = entries.map { it.key }.toHashSet() + NEW_TAB_KEY
        cardBounds.keys.filterNot { it in valid }.forEach { cardBounds.remove(it) }
    }

    fun targetUnder(pos: Offset, exclude: Long?): Long? {
        var best: Long? = null
        var bestArea = Float.MAX_VALUE
        cardBounds.forEach { (key, rect) ->
            if (key != exclude && rect.contains(pos)) {
                val area = rect.width * rect.height
                if (area < bestArea) {
                    bestArea = area
                    best = key
                }
            }
        }
        return best
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // Header — Brave-style: "Tabs" left, group icon middle, delete-all right.
        Box(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 13.dp)) {
            Column(Modifier.align(Alignment.CenterStart)) {
                Text("Tabs", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${tabs.size} open ${if (tabs.size == 1) "tab" else "tabs"}",
                    color = TextMuted, fontSize = 12.sp
                )
            }
            IconButton(
                onClick = onGroupAll,
                modifier = Modifier.align(Alignment.Center).size(40.dp)
            ) {
                Icon(Icons.Default.CreateNewFolder, "Group tabs", tint = TextMain, modifier = Modifier.size(22.dp))
            }
            IconButton(
                onClick = onCloseAll,
                modifier = Modifier.align(Alignment.CenterEnd).size(40.dp)
            ) {
                Icon(Icons.Default.DeleteSweep, "Close all tabs", tint = TextMuted, modifier = Modifier.size(22.dp))
            }
        }

        // "Search your tabs" — filters the OPEN TABS only, never searches the web.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp)
                .padding(bottom = 12.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Surface2)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Tab, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(color = TextMain, fontSize = 13.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("Search your tabs", color = TextMuted, fontSize = 13.sp)
                    }
                    inner()
                }
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, "Clear search", tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { gridTopLeft = it.positionInRoot() }) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 17.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                gridItems(filtered, key = { it.key }) { entry ->
                    val activeMember = entry.tabs.firstOrNull { it.id == activeId } ?: entry.tabs.first()
                    val isActiveEntry = activeMember.id == activeId
                    val isHovered = hoverKey == entry.key && dragKey != entry.key
                    val isExpanded = entry.key in expandedGroups
                    val groupColor = if (entry.isGroup) {
                        GroupColors[(entry.key % GroupColors.size).toInt()]
                    } else Accent
                    val height = if (entry.isGroup && isExpanded) {
                        (160 + 44 * entry.tabs.size).dp
                    } else 160.dp

                    Box(Modifier.fillMaxWidth().height(height)) {
                        // Stacked layers behind a group card
                        if (entry.isGroup) {
                            Box(
                                Modifier.matchParentSize()
                                    .absoluteOffset(x = 10.dp, y = 10.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Surface)
                            )
                            Box(
                                Modifier.matchParentSize()
                                    .absoluteOffset(x = 5.dp, y = 5.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Surface2)
                            )
                        }

                        Column(
                            Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { cardBounds[entry.key] = it.boundsInRoot() }
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isActiveEntry) Color(0xFF2A2119) else Surface)
                                .border(
                                    width = when {
                                        isHovered -> 2.dp
                                        isActiveEntry -> 1.5.dp
                                        else -> 1.dp
                                    },
                                    color = when {
                                        isHovered -> Color.White
                                        isActiveEntry -> Accent
                                        else -> if (entry.isGroup) groupColor.copy(alpha = 0.55f) else Color(0x14FFFFFF)
                                    },
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .pointerInput(entry.key) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragKey = entry.key
                                            dragDistance = 0f
                                            dragPos = cardBounds[entry.key]?.center ?: Offset.Zero
                                            hoverKey = null
                                        },
                                        onDrag = { _, amount ->
                                            dragDistance += amount.getDistance()
                                            dragPos += amount
                                            hoverKey = if (dragDistance > 24f) {
                                                targetUnder(dragPos, entry.key)
                                            } else null
                                        },
                                        onDragEnd = {
                                            if (dragDistance > 40f) {
                                                val target = targetUnder(dragPos, entry.key)
                                                when (target) {
                                                    null -> onDropOutside(entry.key)
                                                    NEW_TAB_KEY -> { /* ignore drops on the New tab card */ }
                                                    else -> onGroup(entry.key, target)
                                                }
                                            }
                                            dragKey = null
                                            hoverKey = null
                                            dragDistance = 0f
                                        },
                                        onDragCancel = {
                                            dragKey = null
                                            hoverKey = null
                                            dragDistance = 0f
                                        }
                                    )
                                }
                                .clickable { onSelect(activeMember) }
                                .padding(11.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
                                        .background(
                                            if (entry.isGroup) groupColor.copy(alpha = 0.22f) else Surface3
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        when {
                                            entry.isGroup -> Icons.Default.Folder
                                            activeMember.url == "swift://home" -> Icons.Default.Home
                                            else -> Icons.Default.Language
                                        },
                                        null,
                                        tint = if (entry.isGroup) groupColor else Accent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (entry.isGroup) "${entry.tabs.size} tabs grouped"
                                        else activeMember.title,
                                        color = TextMain, fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1
                                    )
                                    Text(
                                        if (entry.isGroup) activeMember.title
                                        else if (activeMember.url == "swift://home") "New tab" else activeMember.url,
                                        color = TextMuted, fontSize = 9.sp, maxLines = 1
                                    )
                                }
                                if (isActiveEntry) {
                                    Text("ACTIVE", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                                if (!entry.isGroup) {
                                    IconButton(onClick = { onClose(activeMember) }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Default.Close, "Close tab", tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            if (entry.key in expandedGroups) expandedGroups.remove(entry.key)
                                            else expandedGroups.add(entry.key)
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            "Expand group", tint = TextMuted, modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Surface2),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (activeMember.url == "swift://home") Icons.Default.Home else Icons.Default.Language,
                                    null,
                                    tint = TextMuted.copy(alpha = 0.5f),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            if (activeMember.blockedCount > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text("${activeMember.blockedCount} trackers blocked", color = Green, fontSize = 9.sp)
                            }
                            if (entry.isGroup && isExpanded) {
                                Spacer(Modifier.height(8.dp))
                                entry.tabs.forEach { member ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(Surface2)
                                            .clickable { onSelect(member) }
                                            .padding(horizontal = 8.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (member.url == "swift://home") Icons.Default.Home else Icons.Default.Language,
                                            null, tint = groupColor, modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            member.title.ifBlank { member.url },
                                            color = TextMain, fontSize = 10.sp, maxLines = 1,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (member.id == activeId) {
                                            Text("ACTIVE", color = Accent, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                                        }
                                        IconButton(onClick = { onClose(member) }, modifier = Modifier.size(26.dp)) {
                                            Icon(Icons.Default.Close, "Close tab", tint = TextMuted, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tabs match \"$query\"", color = TextMuted, fontSize = 13.sp)
                }
            }

            // Floating chip that follows the dragged tab / group.
            dragKey?.let { key ->
                val dragged = entries.firstOrNull { it.key == key }
                Box(
                    Modifier.offset {
                        IntOffset(
                            (dragPos.x - gridTopLeft.x - 70.dp.toPx()).roundToInt(),
                            (dragPos.y - gridTopLeft.y - 20.dp.toPx()).roundToInt()
                        )
                    }
                ) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Surface3)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (dragged?.isGroup == true) Icons.Default.Folder else Icons.Default.Language,
                            null, tint = Accent, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            dragged?.tabs?.firstOrNull()?.title?.ifBlank { "Tab" } ?: "Tab",
                            color = TextMain, fontSize = 12.sp, maxLines = 1
                        )
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
        Icon(
            icon, contentDescription = label,
            tint = if (enabled) (if (selected) Accent else TextMain) else TextMuted.copy(alpha = .35f),
            modifier = Modifier.size(21.dp)
        )
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