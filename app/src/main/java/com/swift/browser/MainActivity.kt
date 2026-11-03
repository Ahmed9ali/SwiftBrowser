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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

private val Bg = Color(0xFF101114)
private val Surface = Color(0xFF1A1C20)
private val Surface2 = Color(0xFF25282E)
private val Surface3 = Color(0xFF30343B)
private val Accent = Color(0xFFFF6B35)
private val AccentSoft = Color(0x33FF6B35)
private val TextMain = Color(0xFFF4F4F5)
private val TextMuted = Color(0xFF9A9DA5)
private val Green = Color(0xFF65C98A)

// Per-tab favicons and page thumbnails (shown in tab cards and the group bar).
private val FaviconStore = mutableStateMapOf<Long, Bitmap>()
private val ThumbStore = mutableStateMapOf<Long, Bitmap>()

// Tab groups (Brave-style drag-to-group). Group ids live far above tab ids.
private const val GROUP_ID_BASE = 1_000_000L
private const val NEW_TAB_KEY = -1L

// Brave/Chrome tab-group colors (grey, blue, red, yellow, green, pink, purple, cyan, orange).
private val GroupColors = listOf(
    Color(0xFF9AA0A6), Color(0xFF8AB4F8), Color(0xFFF28B82),
    Color(0xFFFDD663), Color(0xFF81C995), Color(0xFFFF8BCB),
    Color(0xFFC58AF9), Color(0xFF78D9EC), Color(0xFFFCAD70)
)
// Colors the user picked (group key -> palette index); others use a default.
private val GroupColorStore = mutableStateMapOf<Long, Int>()
private val GroupInk = Color(0xFF1B1D22)

// One card in the tab grid: either a single tab or a group of tabs.
private data class TabEntry(val key: Long, val isGroup: Boolean, val tabs: List<BrowserTab>)

// Pull-to-refresh container for a WebView (no extra dependency needed).
private class PullRefreshLayout(context: Context) : android.widget.FrameLayout(context) {
    var onRefresh: (() -> Unit)? = null
    private val density = context.resources.displayMetrics.density
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 72f * density
    private val maxPull = 130f * density
    private var startX = 0f
    private var startY = 0f
    private var pulling = false
    private var pull = 0f
    private var refreshing = false
    private var anim: android.animation.ValueAnimator? = null
    private val timeout = Runnable { endRefresh() }
    private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2D34.toInt()
    }
    private val arcPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6B35.toInt()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val arcRect = android.graphics.RectF()

    init { setWillNotDraw(false) }

    private fun canChildScrollUp(): Boolean = getChildAt(0)?.canScrollVertically(-1) == true

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                pulling = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - startY
                if (!pulling && !refreshing && dy > slop && dy > abs(ev.x - startX) && !canChildScrollUp()) {
                    pulling = true
                    anim?.cancel()
                    startY = ev.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> pulling = false
        }
        return pulling
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!pulling) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE ->
                setPull(((ev.y - startY) * 0.5f).coerceIn(0f, maxPull))
            android.view.MotionEvent.ACTION_UP -> {
                pulling = false
                if (pull >= threshold) startRefresh() else settle(0f)
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                pulling = false
                settle(0f)
            }
        }
        return true
    }

    private fun setPull(v: Float) {
        pull = v
        getChildAt(0)?.translationY = v * 0.35f
        invalidate()
    }

    private fun settle(target: Float) {
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(pull, target).apply {
            duration = 200
            addUpdateListener { setPull(it.animatedValue as Float) }
            start()
        }
    }

    private fun startRefresh() {
        refreshing = true
        settle(threshold * 0.8f)
        onRefresh?.invoke()
        removeCallbacks(timeout)
        postDelayed(timeout, 10_000)
    }

    fun endRefresh() {
        if (!refreshing) return
        refreshing = false
        removeCallbacks(timeout)
        settle(0f)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (pull > 1f) {
            val r = 18f * density
            val cx = width / 2f
            val cy = pull * 0.9f - r
            canvas.drawCircle(cx, cy, r, bgPaint)
            arcRect.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f)
            if (refreshing) {
                val start = (System.currentTimeMillis() % 900L) / 900f * 360f
                canvas.drawArc(arcRect, start, 270f, false, arcPaint)
                postInvalidateOnAnimation()
            } else {
                val sweep = (pull / threshold).coerceAtMost(1f) * 300f
                canvas.drawArc(arcRect, -90f + pull * 1.5f, sweep, false, arcPaint)
            }
        }
    }
}

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
    val firstParty = remember { java.util.concurrent.ConcurrentHashMap<Long, String>() }
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
    val groupNames = remember { mutableStateMapOf<Long, String>() }
    val groupUpdated = remember { mutableStateMapOf<Long, Long>() }
    var pendingOpenGroup by remember { mutableStateOf<Long?>(null) }
    var pendingEditGroup by remember { mutableStateOf<Long?>(null) }
    var showGroupSheet by remember { mutableStateOf(false) }
    var editGroupKey by remember { mutableStateOf<Long?>(null) }
    var groupCounter by remember { mutableLongStateOf(GROUP_ID_BASE + 1) }
    // One WebView per tab: pages, history and network errors never leak between tabs.
    val webViews = remember { HashMap<Long, WebView>() }
    val refreshLayouts = remember { HashMap<Long, PullRefreshLayout>() }
    remember { FaviconStore.clear(); ThumbStore.clear(); GroupColorStore.clear(); 0 }
    var loadProgress by remember { mutableIntStateOf(100) }
    var totalBlocked by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val currentBlockingEnabled by rememberUpdatedState(blockingEnabled)
    val currentPrivateMode by rememberUpdatedState(privateMode)
    val isHome = pageUrl == "swift://home"
    var bookmarkRefresh by remember { mutableIntStateOf(0) }
    val isBookmarked = remember(pageUrl, bookmarkRefresh) { store.isBookmarked(pageUrl) }

    fun activeWebView(): WebView? = webViews[activeId]

    fun updateTabById(id: Long, url: String? = null, title: String? = null, count: Int? = null) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i >= 0) {
            val t = tabs[i]
            tabs[i] = t.copy(url = url ?: t.url, title = title ?: t.title, blockedCount = count ?: t.blockedCount)
        }
    }

    // Snapshot of a tab's page, shown on its card in the tab overview.
    fun captureThumb(id: Long) {
        val wv = webViews[id] ?: return
        val tab = tabs.firstOrNull { it.id == id } ?: return
        if (tab.url == "swift://home" || wv.width <= 0 || wv.height <= 0) return
        runCatching {
            val w = 360
            val scale = w / wv.width.toFloat()
            val h = (wv.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.scale(scale, scale)
            wv.draw(c)
            ThumbStore[id] = bmp
        }
    }

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
            // Blank the tab's WebView so no old page can show through later.
            activeWebView()?.let { it.loadUrl("about:blank"); it.clearHistory() }
            loadProgress = 100
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
        loadProgress = 5
        activeWebView()?.loadUrl(url)
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

    fun newTabInGroup(gid: Long) {
        val id = idCounter.get()
        newTab()
        tabGroups[id] = gid
        groupUpdated[gid] = System.currentTimeMillis()
    }

    fun selectTab(tab: BrowserTab) {
        activeId = tab.id
        pageUrl = tab.url
        address = if (tab.url == "swift://home") "" else tab.url
        pageTitle = tab.title
        blockedCount = tab.blockedCount
        showTabs = false
        showGroupSheet = false
        loadProgress = 100
        canGoBack = webViews[tab.id]?.canGoBack() == true
    }

    fun closeTab(tab: BrowserTab, fromOverview: Boolean = true) {
        if (tabs.size == 1) {
            tabs[0] = BrowserTab(tab.id)
            tabGroups.clear()
            webViews[tab.id]?.let { it.loadUrl("about:blank"); it.clearHistory() }
            ThumbStore.remove(tab.id)
            FaviconStore.remove(tab.id)
            selectTab(tabs[0])
            return
        }
        val index = tabs.indexOfFirst { it.id == tab.id }
        tabs.removeAll { it.id == tab.id }
        tabGroups.remove(tab.id)
        if (activeId == tab.id) {
            val next = tabs.getOrNull((index - 1).coerceAtLeast(0)) ?: tabs.first()
            selectTab(next)
            // Closing a tab from the overview must not leave the overview.
            if (fromOverview) showTabs = true
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
                pendingEditGroup = gid
            }
            // Group onto a loose tab: pull that tab into the group.
            sourceGroup != null && targetGroup == null ->
                tabGroups[targetKey] = sourceGroup
        }
        val resultGid = tabGroups[targetKey] ?: targetKey.takeIf { it >= GROUP_ID_BASE }
        if (resultGid != null) groupUpdated[resultGid] = System.currentTimeMillis()
    }

    // Dropped on empty space: drag a tab out of its group / dissolve a group.
    fun handleDropOutside(key: Long) {
        if (key >= GROUP_ID_BASE) {
            tabGroups.keys.filter { tabGroups[it] == key }.forEach { tabGroups.remove(it) }
            groupNames.remove(key)
            GroupColorStore.remove(key)
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
        groupUpdated[gid] = System.currentTimeMillis()
        pendingEditGroup = gid
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
        canGoBack = activeWebView()?.canGoBack() == true
        canGoForward = activeWebView()?.canGoForward() == true
    }

    // Builds the WebView for one tab. Callbacks only touch the shared UI state
    // when this tab is the active one; otherwise they update just this tab.
    fun createWebView(ctx: Context, tabId: Long): WebView {
        return WebView(ctx).apply {
            // Dark background so a not-yet-painted page never flashes white.
            setBackgroundColor(android.graphics.Color.rgb(16, 17, 20))
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
                    val t = title ?: return
                    if (view?.url?.startsWith("about:") == true) return
                    updateTabById(tabId, title = t)
                    if (tabId == activeId) pageTitle = t
                }

                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    if (icon != null) FaviconStore[tabId] = icon
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 100) refreshLayouts[tabId]?.endRefresh()
                    if (tabId == activeId && view?.url?.startsWith("about:") != true) {
                        loadProgress = newProgress
                    }
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

                    if (currentBlockingEnabled && blocker.shouldBlock(url, firstParty[tabId] ?: "")) {
                        Handler(Looper.getMainLooper()).post {
                            totalBlocked++
                            val i = tabs.indexOfFirst { it.id == tabId }
                            if (i >= 0) {
                                val c = tabs[i].blockedCount + 1
                                tabs[i] = tabs[i].copy(blockedCount = c)
                                if (tabId == activeId) blockedCount = c
                            }
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
                    if (url == null || url.startsWith("about:")) return
                    firstParty[tabId] = url
                    val oldHost = hostOf(tabs.firstOrNull { it.id == tabId }?.url ?: "")
                    if (hostOf(url) != oldHost) FaviconStore.remove(tabId)
                    updateTabById(tabId, url = url)
                    if (tabId == activeId) {
                        pageUrl = url
                        address = url
                        showSuggestions = false
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null || url.startsWith("about:")) return
                    updateTabById(tabId, url = url)
                    refreshLayouts[tabId]?.endRefresh()
                    val title = tabs.firstOrNull { it.id == tabId }?.title ?: url
                    if (tabId == activeId) {
                        pageUrl = url
                        address = url
                        loadProgress = 100
                        refreshNavigation()
                    }
                    if (!currentPrivateMode) store.addHistory(title, url)
                    Handler(Looper.getMainLooper()).postDelayed({ captureThumb(tabId) }, 700)
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

            webViews[tabId] = this
            val t = tabs.firstOrNull { it.id == tabId }
            if (t != null && t.url.startsWith("http")) loadUrl(t.url)
        }
    }

    BackHandler(enabled = showGroupSheet || showShield || showTabs || showSuggestions || (canGoBack && !isHome)) {
        when {
            showGroupSheet -> showGroupSheet = false
            showShield -> showShield = false
            showTabs -> showTabs = false
            showSuggestions -> showSuggestions = false
            else -> {
                activeWebView()?.goBack()
                refreshNavigation()
            }
        }
    }

    // Settings menu, shared by the normal bottom bar and the tab-overview bar.
    @Composable
    fun SettingsMenuBox(dots: Boolean = false) {
        Box {
            if (dots) {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.MoreVert, "Menu", tint = TextMain)
                }
            } else {
                ToolbarButton(
                    Icons.Default.Settings,
                    "Settings",
                    selected = showMenu
                ) {
                    showMenu = true
                }
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(Surface)
            ) {
                if (showTabs) {
                    BrowserMenuItem("Group all tabs", Icons.Default.CreateNewFolder) {
                        showMenu = false
                        groupAllTabs()
                    }
                    BrowserMenuItem("Close all tabs", Icons.Default.DeleteSweep) {
                        showMenu = false
                        closeAllTabs()
                    }
                }
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
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Browser top bar: just the address pill, app logo inside it
            // (like Brave's lion inside its omnibox).
            // Hidden in the tab overview — Brave has no address bar there.
            if (!showTabs) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(24.dp))
                        .background(if (addressFocused) Surface3 else Surface2)
                        .padding(start = 8.dp, end = 14.dp),
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
                        value = if (addressFocused) address else prettyAddress(address),
                        onValueChange = {
                            address = it
                            showSuggestions = it.isNotBlank()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(addressFocusRequester)
                            .onFocusChanged {
                                val wasFocused = addressFocused
                                addressFocused = it.isFocused
                                // Editing a search page shows just the query.
                                if (it.isFocused && !wasFocused) {
                                    searchQueryOf(address)?.let { q -> address = q }
                                }
                                // Leaving the field without navigating restores the link.
                                if (!it.isFocused && wasFocused && pageUrl.startsWith("http")) {
                                    address = pageUrl
                                }
                            },
                        singleLine = true,
                        textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { load(address) }),
                        decorationBox = { inner ->
                            if (address.isEmpty()) {
                                Text("Search or type URL", color = TextMuted, fontSize = 13.sp)
                            }
                            inner()
                        }
                    )
                    // Only a clear button while typing; otherwise it is just the link.
                    if (addressFocused && address.isNotEmpty()) {
                        IconButton(
                            onClick = { address = ""; showSuggestions = false },
                            modifier = Modifier.size(31.dp)
                        ) {
                            Icon(Icons.Default.Close, "Clear address", tint = TextMuted, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
            }

            // Small protection strip opens the detailed shield dashboard.
            // Hidden in the tabs overview, like Brave.
            if (!showTabs) {
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
            }

            // Thin page-load progress line, like Brave.
            if (!showTabs && !isHome && loadProgress in 1..99) {
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    Box(
                        Modifier.fillMaxWidth(loadProgress / 100f).fillMaxHeight().background(Accent)
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                tabs.forEach { tab ->
                    key(tab.id) {
                        val isCurrent = tab.id == activeId
                        val pageVisible = isCurrent && !isHome && !showTabs &&
                            address != "swift://bookmarks" && address != "swift://history"
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    // Brave-style: the page blurs behind the omnibox
                                    // while typing (no-op below Android 12).
                                    if (isCurrent && showSuggestions && !isHome) Modifier.blur(14.dp) else Modifier
                                )
                                .alpha(if (pageVisible) 1f else 0f),
                            factory = { ctx ->
                                val wv = createWebView(ctx, tab.id)
                                PullRefreshLayout(ctx).apply {
                                    addView(
                                        wv,
                                        android.widget.FrameLayout.LayoutParams(
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                    onRefresh = {
                                        val u = wv.url
                                        if (u != null && !u.startsWith("about:")) wv.reload() else endRefresh()
                                    }
                                    refreshLayouts[tab.id] = this
                                }
                            },
                            update = { layout ->
                                layout.visibility = if (isCurrent) android.view.View.VISIBLE else android.view.View.INVISIBLE
                                webViews[tab.id]?.let { wv ->
                                    if (isCurrent) wv.onResume() else wv.onPause()
                                }
                            }
                        )
                        DisposableEffect(tab.id) {
                            onDispose {
                                refreshLayouts.remove(tab.id)
                                webViews.remove(tab.id)?.let { wv ->
                                    wv.stopLoading()
                                    (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                                    wv.destroy()
                                }
                                FaviconStore.remove(tab.id)
                                ThumbStore.remove(tab.id)
                            }
                        }
                    }
                }

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
                        onShowHistory = { address = "swift://history" },
                        totalBlocked = totalBlocked
                    )
                }

                // Tapping the search bar dims everything below it — on every
                // screen (home, results) and every Android version.
                // The wallpaper blur on home adds on top of this.
                androidx.compose.animation.AnimatedVisibility(
                    visible = addressFocused && !showTabs,
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
                    visible = showSuggestions && address.isNotBlank() && !showTabs,
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
                        groupNames = groupNames,
                        groupUpdated = groupUpdated,
                        onRenameGroup = { gid, name ->
                            if (name.isBlank()) {
                                groupNames.remove(gid)
                            } else {
                                groupNames[gid] = name
                            }
                            groupUpdated[gid] = System.currentTimeMillis()
                        },
                        onNewTabInGroup = { newTabInGroup(it) },
                        openGroupRequest = pendingOpenGroup,
                        onOpenGroupConsumed = { pendingOpenGroup = null },
                        editGroupRequest = pendingEditGroup,
                        onEditConsumed = { pendingEditGroup = null },
                        onSearchWeb = { load(it) },
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

            // Brave-style group strip: when the open tab belongs to a group, a
            // slim bar lets you jump between that group's tabs.
            val activeGid = tabGroups[activeId]
            if (!showTabs && activeGid != null) {
                val members = tabs.filter { tabGroups[it.id] == activeGid }
                val gColor = groupColorOf(activeGid)
                Row(
                    Modifier.fillMaxWidth().background(Bg).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            captureThumb(activeId)
                            showGroupSheet = true
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, "Open group", tint = TextMain)
                    }
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        members.forEach { m ->
                            val isActive = m.id == activeId
                            Box(Modifier.size(40.dp)) {
                                Box(
                                    Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape)
                                        .background(Surface2)
                                        .then(if (isActive) Modifier.border(2.dp, gColor, CircleShape) else Modifier)
                                        .clickable { if (!isActive) selectTab(m) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    SiteBadge(m, 20.dp)
                                }
                                if (isActive) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).size(15.dp).clip(CircleShape)
                                            .background(gColor)
                                            .clickable { closeTab(m, false) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Close, "Close tab", tint = GroupInk, modifier = Modifier.size(10.dp))
                                    }
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = { newTabInGroup(activeGid) },
                        modifier = Modifier.size(34.dp).border(1.5.dp, TextMuted, CircleShape)
                    ) {
                        Icon(Icons.Default.Add, "New tab in group", tint = TextMain)
                    }
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
                    // Brave-style "+" pill: light background, dark icon.
                    Row(
                        Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFFE8E8EC))
                            .clickable { newTab() }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, "New tab", tint = Color(0xFF16181C), modifier = Modifier.size(22.dp))
                    }
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        SettingsMenuBox(dots = true)
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
                    if (!showTabs) captureThumb(activeId)
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

        // Brave: the group's tabs open as a popup right over the page.
        val sheetGid = tabGroups[activeId]
        if (showGroupSheet && !showTabs && sheetGid != null) {
            val members = tabs.filter { tabGroups[it.id] == sheetGid }
            GroupPopupOverlay(
                entry = TabEntry(sheetGid, true, members),
                activeId = activeId,
                label = groupLabel(groupNames, sheetGid, members.size),
                color = groupColorOf(sheetGid),
                onDismiss = { showGroupSheet = false },
                onNewTab = { showGroupSheet = false; newTabInGroup(sheetGid) },
                onEdit = { editGroupKey = sheetGid },
                onUngroup = { showGroupSheet = false; handleDropOutside(sheetGid) },
                onCloseGroup = {
                    showGroupSheet = false
                    members.toList().forEach { closeTab(it, false) }
                },
                onSelect = { showGroupSheet = false; selectTab(it) },
                onCloseTab = { closeTab(it, false) },
                onRemoveFromGroup = { handleDropOutside(it.id) }
            )
        }
        editGroupKey?.let { gk ->
            GroupEditDialog(
                key = gk,
                initialName = groupNames[gk] ?: "",
                onSave = { name ->
                    if (name.isBlank()) {
                        groupNames.remove(gk)
                    } else {
                        groupNames[gk] = name
                    }
                    groupUpdated[gk] = System.currentTimeMillis()
                    editGroupKey = null
                },
                onDismiss = { editGroupKey = null }
            )
        }
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF))))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(24.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun HomeSectionLabel(text: String) {
    Text(
        text,
        color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
    )
}

@Composable
private fun HomeStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 10.sp, lineHeight = 13.sp)
    }
}

private fun formatKb(kb: Long): String =
    if (kb < 1024) "$kb KB" else String.format("%.1f MB", kb / 1024.0)

private fun formatSeconds(sec: Double): String =
    if (sec < 60) String.format("%.0f s", sec) else String.format("%.1f min", sec / 60.0)

@Composable
private fun HomeScreen(
    searchActive: Boolean,
    onFocusSearch: () -> Unit,
    onDismissKeyboard: () -> Unit,
    onOpen: (String) -> Unit,
    bookmarks: List<Pair<String, String>>,
    recent: List<Pair<String, String>>,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    totalBlocked: Int
) {
    val greeting = remember {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when {
            h < 5 -> "GOOD NIGHT"
            h < 12 -> "GOOD MORNING"
            h < 17 -> "GOOD AFTERNOON"
            else -> "GOOD EVENING"
        }
    }
    val defaults = listOf(
        "YouTube" to "https://m.youtube.com",
        "Wikipedia" to "https://www.wikipedia.org",
        "GitHub" to "https://github.com",
        "Gmail" to "https://mail.google.com",
        "Maps" to "https://www.google.com/maps",
        "Reddit" to "https://www.reddit.com",
        "X" to "https://x.com",
        "News" to "https://news.google.com"
    )
    val shortcuts = (bookmarks + defaults).distinctBy { it.second }.take(8)

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
                    listOf(Color(0x22000000), Color(0x66000000))
                )
            )
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 26.dp, bottom = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        greeting,
                        color = Accent, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Browse freely.",
                        color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "A faster, quieter web starts here.",
                        color = TextMuted, fontSize = 13.sp
                    )
                }
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(Accent, Color(0xFFB83F25)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(29.dp))
                }
            }

            Spacer(Modifier.height(24.dp))

            HomeSectionLabel("PRIVACY STATS")
            GlassCard {
                Row(Modifier.fillMaxWidth()) {
                    HomeStat(totalBlocked.toString(), "Trackers & ads\nblocked", Modifier.weight(1f))
                    HomeStat(formatKb(totalBlocked * 40L), "Est. data\nsaved", Modifier.weight(1f))
                    HomeStat(formatSeconds(totalBlocked * 0.05), "Est. time\nsaved", Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(20.dp))

            HomeSectionLabel("QUICK ACCESS")
            GlassCard {
                shortcuts.chunked(4).forEachIndexed { rowIndex, rowItems ->
                    if (rowIndex > 0) Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        rowItems.forEach { (name, url) ->
                            val label = hostOf(url).removePrefix("m.").substringBefore('.')
                                .ifBlank { name }
                                .replaceFirstChar { it.uppercaseChar() }
                            val c = SiteColors[Math.abs(label.hashCode()) % SiteColors.size]
                            Column(
                                Modifier.weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onOpen(url) }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier.size(52.dp).clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.6f)))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label.first().toString(),
                                        color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    label,
                                    color = TextMain, fontSize = 11.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                HomeSectionLabel("RECENTLY VISITED")
                GlassCard {
                    recent.forEach { (title, url) ->
                        HomeLinkRow(title.ifBlank { url }, url, Icons.Default.History, onOpen)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
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

// A normal (single) tab card: favicon + title + close, then the page thumbnail.
@Composable
private fun SingleTabCard(
    tab: BrowserTab,
    isActive: Boolean,
    hovered: Boolean,
    ringColor: Color?,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onRemoveFromGroup: (() -> Unit)?,
    showActiveLabel: Boolean = false,
    modifier: Modifier = Modifier
) {
    val label = when {
        tab.url == "swift://home" -> "New tab"
        tab.title.isBlank() || tab.title.startsWith("http") -> hostOf(tab.url).ifBlank { "New tab" }
        else -> tab.title
    }
    Column(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isActive) Color(0xFF2A2119) else Surface)
            .border(
                width = when {
                    hovered -> 2.dp
                    isActive -> 1.5.dp
                    else -> 1.dp
                },
                color = when {
                    hovered -> Color.White
                    isActive -> Accent
                    else -> ringColor?.copy(alpha = 0.6f) ?: Color(0x14FFFFFF)
                },
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onSelect() }
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                SiteBadge(tab, 18.dp)
            }
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                color = TextMain, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onRemoveFromGroup != null) {
                IconButton(onClick = onRemoveFromGroup, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.RemoveCircleOutline, "Remove from group", tint = TextMuted, modifier = Modifier.size(14.dp))
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Close, "Close tab", tint = TextMuted, modifier = Modifier.size(15.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        PagePreview(tab, Modifier.weight(1f).fillMaxWidth())
    }
}

// Brave-style group card: dark card tinted with the group's color, colored dot +
// name + menu, and a 2x2 grid of large page thumbnails.
@Composable
private fun GroupCard(
    entry: TabEntry,
    color: Color,
    name: String,
    onRename: () -> Unit,
    hovered: Boolean,
    onOpen: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val count = entry.tabs.size

    Column(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(groupTint(color))
            .then(
                if (hovered) Modifier.border(2.dp, Color.White, RoundedCornerShape(20.dp)) else Modifier
            )
            .clickable { onOpen() }
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp)
    ) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(20.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(9.dp))
            Text(
                name,
                color = TextMain, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, "Group options", tint = TextMain, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(Surface)
                ) {
                    BrowserMenuItem("Edit group", Icons.Default.Edit) {
                        menuOpen = false
                        onRename()
                    }
                    BrowserMenuItem("Ungroup", Icons.Default.FolderOff) {
                        menuOpen = false
                        onUngroup()
                    }
                    BrowserMenuItem("Close group", Icons.Default.Close) {
                        menuOpen = false
                        onCloseGroup()
                    }
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(2) { row ->
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    repeat(2) { col ->
                        val idx = row * 2 + col
                        val member = entry.tabs.getOrNull(idx)
                        val overflow = idx == 3 && count > 4
                        if (member != null && !overflow) {
                            PagePreview(
                                member, Modifier.weight(1f).fillMaxHeight(),
                                compact = true, tint = Color(0xFF23262C)
                            )
                        } else {
                            Box(
                                Modifier.weight(1f).fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x59000000)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (overflow) {
                                    Text("+${count - 3}", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
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
    onCloseAll: () -> Unit,
    groupNames: Map<Long, String>,
    groupUpdated: Map<Long, Long>,
    onRenameGroup: (Long, String) -> Unit,
    onNewTabInGroup: (Long) -> Unit,
    openGroupRequest: Long?,
    onOpenGroupConsumed: () -> Unit,
    editGroupRequest: Long?,
    onEditConsumed: () -> Unit,
    onSearchWeb: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var openGroupKey by remember { mutableStateOf(openGroupRequest) }
    var renameKey by remember { mutableStateOf<Long?>(null) }
    var settling by remember { mutableStateOf<Settle?>(null) }
    val settleAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { onOpenGroupConsumed() }
    var searching by remember { mutableStateOf(false) }
    // A freshly created group asks for its name and color (like Brave).
    LaunchedEffect(editGroupRequest) {
        if (editGroupRequest != null) {
            renameKey = editGroupRequest
            onEditConsumed()
        }
    }
    var section by remember { mutableIntStateOf(0) } // 0 = tabs, 1 = groups
    var dragKey by remember { mutableStateOf<Long?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dragDistance by remember { mutableStateOf(0f) }
    var hoverKey by remember { mutableStateOf<Long?>(null) }
    val cardBounds = remember { mutableStateMapOf<Long, Rect>() }
    var gridTopLeft by remember { mutableStateOf(Offset.Zero) }

    // Build grid entries: single tabs plus one card per group.
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

    val sectionEntries = if (section == 1) entries.filter { it.isGroup } else entries
    val filtered = sectionEntries

    val openEntry = openGroupKey?.let { k -> entries.firstOrNull { it.key == k && it.isGroup } }

    // If the opened group disappeared (ungrouped / all tabs closed), go back to the grid.
    LaunchedEffect(entries) {
        if (openGroupKey != null && entries.none { it.key == openGroupKey && it.isGroup }) {
            openGroupKey = null
        }
    }

    // Back button inside an opened group returns to the grid first.
    BackHandler(enabled = openEntry != null) { openGroupKey = null }

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

    fun groupColorFor(key: Long): Color = groupColorOf(key)

    // ---------- Main tab grid ----------
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        // Brave-style header: centered switch between Tabs and Tab groups.
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Surface2)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier.width(58.dp).height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (section == 0) Surface3 else Color.Transparent)
                        .clickable { section = 0 },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size(22.dp).border(2.dp, TextMain, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(tabs.size.toString(), color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    Modifier.width(58.dp).height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (section == 1) Surface3 else Color.Transparent)
                        .clickable { section = 1 },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GridView, "Tab groups", tint = TextMain, modifier = Modifier.size(22.dp))
                }
            }
            IconButton(
                onClick = onCloseAll,
                modifier = Modifier.align(Alignment.CenterEnd).size(42.dp)
            ) {
                Icon(Icons.Default.DeleteSweep, "Close all tabs", tint = TextMuted, modifier = Modifier.size(22.dp))
            }
        }

        // "Search your tabs" opens a full search screen (tabs list + web search), like Brave.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp)
                .padding(bottom = 12.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Surface2)
                .clickable { searching = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Tab, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Text("Search your tabs", color = TextMuted, fontSize = 13.sp)
        }

        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { gridTopLeft = it.positionInRoot() }) {
            if (section == 1) GroupListSection(
                entries = filtered,
                names = groupNames,
                updated = groupUpdated,
                onOpen = { openGroupKey = it },
                onRename = { renameKey = it },
                onUngroup = { onDropOutside(it) },
                onCloseGroup = { e -> e.tabs.toList().forEach { onClose(it) } }
            ) else LazyVerticalGrid(
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

                    // Brave: the card under the dragged tab shrinks a little to
                    // "absorb" it.
                    val absorbing = isHovered || settling?.target == entry.key
                    val cardScale by animateFloatAsState(
                        targetValue = if (absorbing) 0.9f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "absorb"
                    )

                    // Drag behaviour shared by tab cards and group cards. The
                    // original card is hidden while the held copy moves.
                    val dragModifier = Modifier
                        .alpha(if (dragKey == entry.key) 0f else 1f)
                        .onGloballyPositioned { cardBounds[entry.key] = it.boundsInRoot() }
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
                                    val target = if (dragDistance > 40f) targetUnder(dragPos, entry.key) else null
                                    val targetRect = target?.let { cardBounds[it] }
                                    if (dragDistance > 40f && target != null && target != NEW_TAB_KEY && targetRect != null) {
                                        // Fly the held card into the target, then merge.
                                        val src = entry.key
                                        settling = Settle(src, target, dragPos, targetRect.center)
                                        hoverKey = null
                                        scope.launch {
                                            settleAnim.snapTo(0f)
                                            settleAnim.animateTo(1f, tween(240))
                                            onGroup(src, target)
                                            settling = null
                                            dragKey = null
                                            dragDistance = 0f
                                        }
                                    } else {
                                        if (dragDistance > 40f && target == null) onDropOutside(entry.key)
                                        dragKey = null
                                        hoverKey = null
                                        dragDistance = 0f
                                    }
                                },
                                onDragCancel = {
                                    dragKey = null
                                    hoverKey = null
                                    dragDistance = 0f
                                }
                            )
                        }

                    Box(
                        Modifier.fillMaxWidth().height(196.dp)
                            .graphicsLayer { scaleX = cardScale; scaleY = cardScale }
                    ) {
                        if (entry.isGroup) {
                            GroupCard(
                                entry = entry,
                                color = groupColorFor(entry.key),
                                name = groupLabel(groupNames, entry.key, entry.tabs.size),
                                onRename = { renameKey = entry.key },
                                hovered = false,
                                onOpen = { openGroupKey = entry.key },
                                onUngroup = { onDropOutside(entry.key) },
                                onCloseGroup = { entry.tabs.toList().forEach { onClose(it) } },
                                modifier = dragModifier
                            )
                        } else {
                            SingleTabCard(
                                tab = activeMember,
                                isActive = isActiveEntry,
                                hovered = false,
                                ringColor = null,
                                onSelect = { onSelect(activeMember) },
                                onClose = { onClose(activeMember) },
                                onRemoveFromGroup = null,
                                modifier = dragModifier
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No tab groups yet",
                        color = TextMuted, fontSize = 13.sp
                    )
                }
            }

            // The real card (same size) is held under the finger while dragging.
            // On drop it shrinks and flies into the target.
            dragKey?.let { key ->
                val dragged = entries.firstOrNull { it.key == key }
                val rect = cardBounds[key]
                if (dragged != null && rect != null) {
                    val density = LocalDensity.current
                    val wDp = with(density) { rect.width.toDp() }
                    val hDp = with(density) { rect.height.toDp() }
                    val st = settling
                    val p = settleAnim.value
                    val pos = if (st != null) {
                        Offset(
                            st.from.x + (st.to.x - st.from.x) * p,
                            st.from.y + (st.to.y - st.from.y) * p
                        )
                    } else dragPos
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (pos.x - gridTopLeft.x - rect.width / 2f).roundToInt(),
                                    (pos.y - gridTopLeft.y - rect.height / 2f).roundToInt()
                                )
                            }
                            .size(wDp, hDp)
                            .graphicsLayer {
                                val sc = if (st != null) 1f - 0.7f * p else 1.04f
                                scaleX = sc
                                scaleY = sc
                                alpha = if (st != null) 1f - 0.9f * p else 0.96f
                            }
                    ) {
                        if (dragged.isGroup) {
                            GroupCard(
                                entry = dragged,
                                color = groupColorFor(dragged.key),
                                name = groupLabel(groupNames, dragged.key, dragged.tabs.size),
                                onRename = {},
                                hovered = false,
                                onOpen = {}, onUngroup = {}, onCloseGroup = {}
                            )
                        } else {
                            val t = dragged.tabs.first()
                            SingleTabCard(
                                tab = t, isActive = t.id == activeId, hovered = false,
                                ringColor = null, onSelect = {}, onClose = {},
                                onRemoveFromGroup = null
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- Group popup (Brave: a small sheet over the dimmed grid) ----------
    if (openEntry != null) {
        GroupPopupOverlay(
            entry = openEntry,
            activeId = activeId,
            label = groupLabel(groupNames, openEntry.key, openEntry.tabs.size),
            color = groupColorFor(openEntry.key),
            onDismiss = { openGroupKey = null },
            onNewTab = { onNewTabInGroup(openEntry.key) },
            onEdit = { renameKey = openEntry.key },
            onUngroup = { onDropOutside(openEntry.key) },
            onCloseGroup = { openEntry.tabs.toList().forEach { onClose(it) } },
            onSelect = onSelect,
            onCloseTab = onClose,
            onRemoveFromGroup = { onDropOutside(it.id) }
        )
    }

    // ---------- Search screen: matching tabs list + web search ----------
    if (searching) {
        BackHandler { searching = false }
        TabSearchScreen(
            tabs = tabs,
            groups = groups,
            groupNames = groupNames,
            onBack = { searching = false },
            onSelectTab = { onSelect(it) },
            onOpenGroup = { searching = false; openGroupKey = it },
            onSearchWeb = onSearchWeb
        )
    }

    renameKey?.let { rk ->
        GroupEditDialog(
            key = rk,
            initialName = groupNames[rk] ?: "",
            onSave = { name -> onRenameGroup(rk, name); renameKey = null },
            onDismiss = { renameKey = null }
        )
    }
    }
}

// The group's tabs as a rounded popup over a dimmed screen. Used both in the
// tab overview and directly over a web page (from the group bar).
@Composable
private fun GroupPopupOverlay(
    entry: TabEntry,
    activeId: Long,
    label: String,
    color: Color,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onEdit: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit,
    onSelect: (BrowserTab) -> Unit,
    onCloseTab: (BrowserTab) -> Unit,
    onRemoveFromGroup: (BrowserTab) -> Unit
) {
    var popupMenu by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize()
            .background(Color(0xB3000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
            enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.92f, animationSpec = tween(170)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color(0xFF202226))
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(top = 8.dp, bottom = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = TextMain)
                    }
                    Box(Modifier.size(22.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        color = TextMain, fontSize = 22.sp, maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onEdit() }
                            .drawBehind {
                                drawLine(
                                    TextMuted,
                                    Offset(0f, size.height),
                                    Offset(size.width, size.height),
                                    1.dp.toPx()
                                )
                            }
                            .padding(vertical = 4.dp)
                    )
                    IconButton(onClick = onNewTab) {
                        Icon(Icons.Default.Add, "New tab in group", tint = TextMain)
                    }
                    Box {
                        IconButton(onClick = { popupMenu = true }) {
                            Icon(Icons.Default.MoreVert, "Group options", tint = TextMain)
                        }
                        DropdownMenu(
                            expanded = popupMenu,
                            onDismissRequest = { popupMenu = false },
                            modifier = Modifier.background(Surface)
                        ) {
                            BrowserMenuItem("Edit group", Icons.Default.Edit) {
                                popupMenu = false
                                onEdit()
                            }
                            BrowserMenuItem("Ungroup", Icons.Default.FolderOff) {
                                popupMenu = false
                                onUngroup()
                            }
                            BrowserMenuItem("Close group", Icons.Default.Close) {
                                popupMenu = false
                                onCloseGroup()
                            }
                        }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    gridItems(entry.tabs, key = { it.id }) { tab ->
                        Box(Modifier.fillMaxWidth().height(176.dp)) {
                            SingleTabCard(
                                tab = tab,
                                isActive = tab.id == activeId,
                                hovered = false,
                                ringColor = color,
                                onSelect = { onSelect(tab) },
                                onClose = { onCloseTab(tab) },
                                onRemoveFromGroup = { onRemoveFromGroup(tab) },
                                showActiveLabel = false
                            )
                        }
                    }
                }
            }
        }
    }
}

// Name + color picker for a group (opens when a group is created or edited).
@Composable
private fun GroupEditDialog(
    key: Long,
    initialName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(key) { mutableStateOf(initialName) }
    var selected by remember(key) {
        mutableIntStateOf(GroupColorStore[key] ?: (key % GroupColors.size).toInt())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Edit group", color = TextMain) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("Group name") }
                )
                Spacer(Modifier.height(16.dp))
                Text("Color", color = TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                GroupColors.indices.chunked(5).forEach { rowIdx ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 10.dp)
                    ) {
                        rowIdx.forEach { i ->
                            Box(
                                Modifier.size(34.dp).clip(CircleShape)
                                    .background(GroupColors[i])
                                    .clickable { selected = i },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected == i) {
                                    Icon(Icons.Default.Check, null, tint = GroupInk, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                GroupColorStore[key] = selected
                onSave(text.trim())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun highlight(text: String, term: String): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        val i = if (term.isEmpty()) -1 else text.indexOf(term, ignoreCase = true)
        if (i < 0) {
            append(text)
        } else {
            append(text.substring(0, i))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i, i + term.length)) }
            append(text.substring(i + term.length))
        }
    }

private fun suggestionText(s: String, term: String): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        if (s.startsWith(term, ignoreCase = true)) {
            append(s.substring(0, term.length))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(term.length)) }
        } else {
            append(s)
        }
    }

// Search-engine suggestions for the typed text (best effort; empty on any failure).
private fun fetchSuggestions(q: String): List<String> = runCatching {
    val url = java.net.URL("https://search.brave.com/api/suggest?q=" + Uri.encode(q) + "&rich=false")
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 3000
    conn.readTimeout = 3000
    conn.setRequestProperty("Accept", "application/json")
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    val list = org.json.JSONArray(body).optJSONArray(1) ?: return@runCatching emptyList<String>()
    (0 until list.length()).map { list.optString(it) }
        .filter { it.isNotBlank() && !it.equals(q, ignoreCase = true) }
        .take(5)
}.getOrDefault(emptyList())

@Composable
private fun SearchResultRow(
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: androidx.compose.ui.text.AnnotatedString,
    subtitle: androidx.compose.ui.text.AnnotatedString? = null
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, color = TextMain, fontSize = 17.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle, color = Color(0xFFA9B4FF), fontSize = 13.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

// Brave-style tab search: a list of matching tabs/groups, then web search entries.
@Composable
private fun TabSearchScreen(
    tabs: List<BrowserTab>,
    groups: Map<Long, Long>,
    groupNames: Map<Long, String>,
    onBack: () -> Unit,
    onSelectTab: (BrowserTab) -> Unit,
    onOpenGroup: (Long) -> Unit,
    onSearchWeb: (String) -> Unit
) {
    var q by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(q) {
        suggestions = emptyList()
        if (q.isNotBlank()) {
            delay(220)
            suggestions = withContext(Dispatchers.IO) { fetchSuggestions(q.trim()) }
        }
    }
    val term = q.trim()
    val tabMatches = if (term.isEmpty()) emptyList() else tabs.filter {
        it.title.contains(term, true) || it.url.contains(term, true)
    }
    val groupMatches = if (term.isEmpty()) emptyList() else tabs.mapNotNull { groups[it.id] }.distinct()
        .filter { gid -> groupNames[gid]?.contains(term, true) == true }
    val cardBg = Color(0xFF1B1D21)

    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFF25282E))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .padding(horizontal = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp).height(52.dp)
                .clip(RoundedCornerShape(26.dp)).background(Color(0xFF15171A)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextMain) }
            BasicTextField(
                value = q,
                onValueChange = { q = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                textStyle = TextStyle(color = TextMain, fontSize = 18.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (term.isNotEmpty()) onSearchWeb(term) })
            )
            if (q.isNotEmpty()) {
                IconButton(onClick = { q = "" }) { Icon(Icons.Default.Close, "Clear", tint = TextMain) }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (tabMatches.isNotEmpty() || groupMatches.isNotEmpty()) {
                item {
                    Text(
                        "Tabs and tab groups", color = TextMuted, fontSize = 15.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                    )
                }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg)) {
                        groupMatches.forEach { gid ->
                            val count = tabs.count { groups[it.id] == gid }
                            SearchResultRow(
                                onClick = { onOpenGroup(gid) },
                                leading = {
                                    Box(Modifier.size(22.dp).clip(CircleShape).background(groupColorOf(gid)))
                                },
                                title = highlight(groupLabel(groupNames, gid, count), term),
                                subtitle = buildAnnotatedString { append(if (count == 1) "1 tab" else "$count tabs") }
                            )
                        }
                        tabMatches.forEach { t ->
                            val label = when {
                                t.url == "swift://home" -> "New tab"
                                t.title.isBlank() -> hostOf(t.url)
                                else -> t.title
                            }
                            SearchResultRow(
                                onClick = { onSelectTab(t) },
                                leading = { SiteBadge(t, 32.dp) },
                                title = highlight(label, term),
                                subtitle = highlight(prettyAddress(t.url), term)
                            )
                        }
                    }
                }
            }
            if (term.isNotEmpty()) {
                item {
                    Text(
                        "Search the web", color = TextMuted, fontSize = 15.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)
                    )
                }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg)) {
                        SearchResultRow(
                            onClick = { onSearchWeb(term) },
                            leading = { Icon(Icons.Default.Search, null, tint = TextMain, modifier = Modifier.size(26.dp)) },
                            title = buildAnnotatedString { append(term) }
                        )
                        suggestions.forEach { s ->
                            SearchResultRow(
                                onClick = { onSearchWeb(s) },
                                leading = { Icon(Icons.Default.Search, null, tint = TextMain, modifier = Modifier.size(26.dp)) },
                                title = suggestionText(s, term)
                            )
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
        Icon(
            icon, contentDescription = label,
            tint = if (enabled) (if (selected) Accent else TextMain) else TextMuted.copy(alpha = .35f),
            modifier = Modifier.size(21.dp)
        )
        Text(label, color = if (selected) Accent else TextMuted, fontSize = 9.sp, maxLines = 1)
    }
}

private data class Settle(val source: Long, val target: Long, val from: Offset, val to: Offset)

private fun groupColorOf(key: Long): Color =
    GroupColors[(GroupColorStore[key] ?: (key % GroupColors.size).toInt()).coerceIn(0, GroupColors.size - 1)]

// Dark card background tinted with the group's color (Brave style).
private fun groupTint(c: Color): Color = lerp(Color(0xFF16181C), c, 0.34f)

private fun groupLabel(names: Map<Long, String>, key: Long, count: Int): String =
    names[key]?.takeIf { it.isNotBlank() } ?: if (count == 1) "1 tab" else "$count tabs"

private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return "Updated just now"
    val m = (System.currentTimeMillis() - ts) / 60000
    fun ago(n: Long, unit: String) = "Updated $n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        m < 1 -> "Updated just now"
        m < 60 -> ago(m, "minute")
        m < 1440 -> ago(m / 60, "hour")
        m < 10080 -> ago(m / 1440, "day")
        else -> ago(m / 10080, "week")
    }
}

// Site icon: the page's real favicon when known, else a colored letter,
// or a home icon for the start page.
@Composable
private fun SiteBadge(tab: BrowserTab, size: androidx.compose.ui.unit.Dp) {
    val host = hostOf(tab.url)
    val fav = FaviconStore[tab.id]
    if (tab.url == "swift://home" || host.isEmpty()) {
        Icon(Icons.Default.Home, null, tint = TextMuted, modifier = Modifier.size(size * 0.65f))
    } else if (fav != null) {
        val img = remember(fav) { fav.asImageBitmap() }
        Image(
            bitmap = img,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size).clip(RoundedCornerShape(size / 5))
        )
    } else {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(SiteColors[Math.abs(host.hashCode()) % SiteColors.size]),
            contentAlignment = Alignment.Center
        ) {
            Text(
                host.first().uppercaseChar().toString(),
                color = Color.White, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

// "Tab groups" section: one row per group — preview, color dot, name, age.
@Composable
private fun GroupListSection(
    entries: List<TabEntry>,
    names: Map<Long, String>,
    updated: Map<Long, Long>,
    onOpen: (Long) -> Unit,
    onRename: (Long) -> Unit,
    onUngroup: (Long) -> Unit,
    onCloseGroup: (TabEntry) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 17.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        items(entries, key = { it.key }) { entry ->
            var menu by remember { mutableStateOf(false) }
            val count = entry.tabs.size
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface)
                    .clickable { onOpen(entry.key) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier.size(80.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Bg)
                        .border(1.dp, Surface3, RoundedCornerShape(18.dp))
                        .padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(2) { row ->
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(2) { col ->
                                val idx = row * 2 + col
                                val member = entry.tabs.getOrNull(idx)
                                val overflow = idx == 3 && count > 4
                                Box(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Surface2),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (overflow) {
                                        Text("+${count - 3}", color = TextMain, fontSize = 12.sp)
                                    } else if (member != null) {
                                        SiteBadge(member, 22.dp)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(groupColorOf(entry.key)))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            groupLabel(names, entry.key, count),
                            color = TextMain, fontSize = 19.sp, maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(relativeTime(updated[entry.key] ?: 0L), color = TextMuted, fontSize = 13.sp)
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, "Group options", tint = TextMain)
                    }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        modifier = Modifier.background(Surface)
                    ) {
                        BrowserMenuItem("Rename", Icons.Default.Edit) { menu = false; onRename(entry.key) }
                        BrowserMenuItem("Ungroup", Icons.Default.FolderOff) { menu = false; onUngroup(entry.key) }
                        BrowserMenuItem("Close group", Icons.Default.Close) { menu = false; onCloseGroup(entry) }
                    }
                }
            }
        }
    }
}

private val SiteColors = listOf(
    Color(0xFF5B8DEF), Color(0xFF65C98A), Color(0xFFE8765A),
    Color(0xFFC98BDE), Color(0xFFD9A93C), Color(0xFF3FB6BF)
)

private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: ""

// Query of a Brave Search results URL, or null for any other URL.
private fun searchQueryOf(url: String): String? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    if (uri.host != "search.brave.com" || uri.path?.startsWith("/search") != true) return null
    return uri.getQueryParameter("q")
}

// What the address bar shows when not editing: a clean link, with the
// search engine's address replaced by Swift's own name.
private fun prettyAddress(url: String): String {
    searchQueryOf(url)?.let { return "swift://search/$it" }
    if (url.startsWith("http://") || url.startsWith("https://")) {
        return url.substringAfter("://").removePrefix("www.").trimEnd('/')
    }
    return url
}

// Page thumbnail (real snapshot of the page); falls back to favicon + site
// name until a snapshot exists.
@Composable
private fun PagePreview(
    tab: BrowserTab,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    tint: Color = Surface2
) {
    val host = hostOf(tab.url)
    val thumb = ThumbStore[tab.id]
    Box(
        modifier.clip(RoundedCornerShape(if (compact) 10.dp else 12.dp)).background(tint),
        contentAlignment = Alignment.Center
    ) {
        if (tab.url == "swift://home" || host.isEmpty()) {
            Icon(
                Icons.Default.Home, null,
                tint = TextMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(if (compact) 18.dp else 32.dp)
            )
        } else if (thumb != null) {
            val img = remember(thumb) { thumb.asImageBitmap() }
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
            if (compact) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp)
                        .clip(CircleShape).background(Color(0xCC1A1C20)),
                    contentAlignment = Alignment.Center
                ) {
                    SiteBadge(tab, 15.dp)
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(4.dp)
            ) {
                SiteBadge(tab, if (compact) 22.dp else 40.dp)
                Spacer(Modifier.height(if (compact) 3.dp else 8.dp))
                Text(
                    host,
                    color = if (compact) TextMain else TextMuted,
                    fontSize = if (compact) 8.sp else 11.sp,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
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
