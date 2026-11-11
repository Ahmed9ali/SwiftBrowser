package com.swift.browser

import android.annotation.SuppressLint
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
import java.io.File
import android.os.Parcel
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import kotlinx.coroutines.Job
import androidx.compose.animation.core.VectorConverter
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

// Everything needed to bring the browser back exactly as it was left.
private class SavedSession(
    val tabs: List<BrowserTab>,
    val activeId: Long,
    val nextId: Long,
    val groupCounter: Long,
    val groups: Map<Long, Long>,
    val names: Map<Long, String>,
    val colors: Map<Long, Int>,
    val updated: Map<Long, Long>
)

private fun loadSession(context: Context): SavedSession? {
    return try {
        val raw = context.getSharedPreferences("swift_session", Context.MODE_PRIVATE)
            .getString("session", null) ?: return null
        val o = org.json.JSONObject(raw)
        val arr = o.getJSONArray("tabs")
        val tabs = (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            BrowserTab(t.getLong("id")).copy(url = t.getString("url"), title = t.getString("title"))
        }
        if (tabs.isEmpty()) return null
        val ids = tabs.map { it.id }.toSet()
        fun longMap(name: String): Map<Long, Long> {
            val m = o.optJSONObject(name) ?: return emptyMap()
            return m.keys().asSequence().associate { it.toLong() to m.getLong(it) }
        }
        val activeId = o.optLong("active", tabs.first().id).let { if (it in ids) it else tabs.first().id }
        val namesJson = o.optJSONObject("names")
        val colorsJson = o.optJSONObject("colors")
        SavedSession(
            tabs = tabs,
            activeId = activeId,
            nextId = maxOf(o.optLong("next", 2L), (tabs.maxOf { it.id }) + 1),
            groupCounter = o.optLong("gc", GROUP_ID_BASE + 1),
            groups = longMap("groups").filterKeys { it in ids },
            names = namesJson?.keys()?.asSequence()?.associate { it.toLong() to namesJson.getString(it) } ?: emptyMap(),
            colors = colorsJson?.keys()?.asSequence()?.associate { it.toLong() to colorsJson.getInt(it) } ?: emptyMap(),
            updated = longMap("updated")
        )
    } catch (e: Exception) {
        null
    }
}

// Bookmarks kept in this app's own storage (first run imports any old ones).
private class BookmarkStore(context: Context, legacy: () -> List<Pair<String, String>>) {
    private val prefs = context.getSharedPreferences("swift_bookmarks", Context.MODE_PRIVATE)

    init {
        if (prefs.getString("items", null) == null) {
            val old = try { legacy() } catch (e: Exception) { emptyList() }
            if (old.isNotEmpty()) save(old)
        }
    }

    private fun key(u: String) = u.trim().trimEnd('/')

    fun list(): List<Pair<String, String>> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return try {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                o.optString("title") to o.getString("url")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isBookmarked(url: String): Boolean = list().any { key(it.second) == key(url) }

    fun add(title: String, url: String) {
        if (isBookmarked(url)) return
        save(listOf(title.ifBlank { url } to url) + list())
    }

    fun remove(url: String) = save(list().filterNot { key(it.second) == key(url) })

    private fun save(items: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        items.forEach { (t, u) -> arr.put(org.json.JSONObject().put("title", t).put("url", u)) }
        prefs.edit().putString("items", arr.toString()).commit()
    }
}

// Last snapshot of the homepage: every home tab shows it in the tab overview.
private var HomeThumbStore by mutableStateOf<Bitmap?>(null)

// Desktop user agent: same Chrome version as this phone's WebView (a stale version
// next to modern features makes some sites distrust the browser and show errors).
private fun desktopUserAgent(context: Context): String {
    val def = android.webkit.WebSettings.getDefaultUserAgent(context)
    val major = Regex("Chrome/(\\d+)").find(def)?.groupValues?.get(1) ?: "124"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
}

// Only well-known mobile hosts are swapped for their desktop host. Every other site is
// simply reloaded at the same address with the desktop user agent (like Chrome does);
// guessing "www." for unknown sites sent some of them to a 404 page.
private fun desktopEquivalent(url: String, desktop: Boolean): String {
    if (!desktop) return url
    return runCatching {
        val uri = Uri.parse(url)
        val host = uri.host ?: return url
        val newHost = when {
            host == "m.youtube.com" -> "www.youtube.com"
            host.endsWith(".m.wikipedia.org") -> host.replace(".m.wikipedia.org", ".wikipedia.org")
            host == "m.facebook.com" || host == "mbasic.facebook.com" || host == "touch.facebook.com" -> "www.facebook.com"
            host == "mobile.twitter.com" -> "twitter.com"
            host == "mobile.x.com" -> "x.com"
            host == "m.imdb.com" -> "www.imdb.com"
            host == "m.reddit.com" -> "www.reddit.com"
            else -> host
        }
        if (newHost == host) url else uri.buildUpon().authority(newHost).build().toString()
    }.getOrDefault(url)
}

// Page language detection (for the auto translate offer): html lang first, then
// the dominant non-Latin script of the visible text.
private const val LANG_JS = """
(function(){
 var l=(document.documentElement.lang||'').toLowerCase().split('-')[0].split('_')[0];
 var t=((document.body&&document.body.innerText)||'').slice(0,9000);
 // Letters only: spaces, digits and punctuation must not dilute the share of a script.
 var letters=t.replace(/[\s\d!-\/:-@\[-`{-~\u2000-\u206f\u3000-\u303f\uff00-\uff0f]/g,'').length||1;
 var sc=[['zh',/[\u4e00-\u9fff]/g,0.06,30],['ja',/[\u3040-\u30ff]/g,0.04,30],['ko',/[\uac00-\ud7af]/g,0.06,30],['ru',/[\u0400-\u04ff]/g,0.15,20],['ar',/[\u0600-\u06ff]/g,0.15,20],['hi',/[\u0900-\u097f]/g,0.15,20],['th',/[\u0e00-\u0e7f]/g,0.15,20],['he',/[\u0590-\u05ff]/g,0.15,20],['el',/[\u0370-\u03ff]/g,0.15,20]];
 var best='',bn=0;
 sc.forEach(function(s){var m=t.match(s[1]);var n=m?m.length:0;if(n>=s[3]&&n/letters>=s[2]&&n>bn){bn=n;best=s[0];}});
 if(best){ if(best==='zh'&&/[\u3040-\u30ff]/.test(t)) best='ja'; return best; }
 return (l&&l.length>=2&&l.length<=3)?l:'';
})()
"""

// Long-press on a video: finds the <video> under the finger (also inside shadow DOM, like Reddit's
// player) and its direct file URL when the page exposes one.
private const val VIDEO_PROBE_JS = """
(function(x,y){
 var all=[];
 function deep(root){try{root.querySelectorAll('video').forEach(function(v){all.push(v);});
  root.querySelectorAll('*').forEach(function(n){if(n.shadowRoot)deep(n.shadowRoot);});}catch(e){}}
 deep(document);
 var v=null,area=1e12;
 all.forEach(function(e){var r=e.getBoundingClientRect();
  if(x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom&&r.width*r.height<area){v=e;area=r.width*r.height;}});
 if(!v) return '';
 window.__swiftLpVideo=v;
 var src=v.currentSrc||v.src||'';
 if(!src||src.indexOf('blob:')===0||/\.m3u8|\.mpd/i.test(src)){
  var found='';
  var ss=v.querySelectorAll('source');
  for(var j=0;j<ss.length;j++){if(/^https?:/.test(ss[j].src)&&!/\.m3u8|\.mpd/i.test(ss[j].src)){found=ss[j].src;break;}}
  if(!found){try{var rs=performance.getEntriesByType('resource');
   for(var k=rs.length-1;k>=0;k--){var n=rs[k].name;
    if(/\.(mp4|webm|m4v|mov)(\?|#|$)/i.test(n)||/\/DASH_\d+/i.test(n)){found=n;break;}}}catch(e){}}
  src=found;
 }
 return JSON.stringify({src:src});
})(__X__,__Y__)
"""

private const val VIDEO_FRAME_JS = """
(function(){var v=window.__swiftLpVideo;if(!v||!v.videoWidth)return '';
 try{var s=Math.min(1,1600/v.videoWidth);var c=document.createElement('canvas');
  c.width=Math.round(v.videoWidth*s);c.height=Math.round(v.videoHeight*s);
  c.getContext('2d').drawImage(v,0,0,c.width,c.height);return c.toDataURL('image/jpeg',0.92);}
 catch(e){return '';}})()
"""

private const val VIDEO_FULLSCREEN_JS = """
(function(){var v=window.__swiftLpVideo;if(!v)return;
 try{if(v.paused)v.play();}catch(e){}
 var f=v.requestFullscreen||v.webkitRequestFullscreen||v.webkitEnterFullscreen;
 if(f){try{f.call(v);}catch(e){}}})()
"""

// Desktop site: Chrome-style. Forces a wide layout viewport (pages that ask for
// width=device-width otherwise snap back to the phone layout) and hides every
// "I'm a phone" signal sites check from JavaScript: touch, screen size, pointer media queries.
private const val DESKTOP_JS = """
(function(){
 try{
  var d=function(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v;},configurable:true});}catch(e){}};
  d(Navigator.prototype,'maxTouchPoints',0);
  d(Navigator.prototype,'userAgentData',undefined);
  d(Navigator.prototype,'platform','Linux x86_64');
  d(Navigator.prototype,'vendor','Google Inc.');
  d(Screen.prototype,'width',1920);
  d(Screen.prototype,'height',1080);
  d(Screen.prototype,'availWidth',1920);
  d(Screen.prototype,'availHeight',1040);
  d(window,'orientation',undefined);
  try{delete window.ontouchstart;}catch(e){}
  if(!window.__swiftMM){
   window.__swiftMM=true;
   var mm=window.matchMedia.bind(window);
   window.matchMedia=function(q){
    try{
     if(/(any-)?pointer\s*:\s*(coarse|none)|(any-)?hover\s*:\s*none/.test(q)) return mm('not all');
     if(/(any-)?pointer\s*:\s*fine|(any-)?hover\s*:\s*hover/.test(q)) return mm('all');
    }catch(e){}
    return mm(q);
   };
  }
 }catch(e){}
 function fix(){
  var h=document.head||document.documentElement; if(!h) return;
  var ms=document.querySelectorAll('meta[name=viewport]');
  if(!ms.length){var m=document.createElement('meta');m.name='viewport';m.content='width=1280';h.appendChild(m);return;}
  for(var i=0;i<ms.length;i++){ if(ms[i].content!=='width=1280') ms[i].content='width=1280'; }
 }
 fix();
 if(!window.__swiftDeskObs){
  window.__swiftDeskObs=new MutationObserver(fix);
  window.__swiftDeskObs.observe(document.head||document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});
 }
})();
"""

// Page translation script lives in TranslateJs.kt (TRANSLATE_JS).

private data class PendingDownload(
    val url: String, val userAgent: String?, val disposition: String?,
    val mime: String?, val referer: String?, val filename: String
)

private fun canAutoOpenMime(m: String?): Boolean {
    val t = m ?: ""
    return t.startsWith("image/") || t.startsWith("video/") || t.startsWith("audio/") ||
        t.startsWith("text/") || t == "application/pdf" ||
        t.contains("officedocument") || t == "application/msword"
}

// Per-tab favicons and page thumbnails (shown in tab cards and the group bar).
private val FaviconStore = mutableStateMapOf<Long, Bitmap>()
// Favicons by site, for the quick-access tiles under the address bar.
private val HostFavicons = mutableStateMapOf<String, Bitmap>()

private fun fetchFavicon(host: String): Bitmap? = runCatching {
    val c = java.net.URL("https://www.google.com/s2/favicons?sz=64&domain=$host").openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 4000; c.readTimeout = 4000
    c.inputStream.use { BitmapFactory.decodeStream(it) }
}.getOrNull()
private val ThumbStore = mutableStateMapOf<Long, Bitmap>()

// Tab groups (Brave-style drag-to-group). Group ids live far above tab ids.
private const val GROUP_ID_BASE = 1_000_000L
private const val NEW_TAB_KEY = -1L

// Brave/Chrome tab-group colors (grey, blue, red, yellow, green, pink, purple, cyan, orange).
private val GroupColors = listOf(
    Color(0xFF9AA0A6), Color(0xFF8AB4F8), Color(0xFFF28B82),
    Color(0xFFFDD663), Color(0xFF81C995), Color(0xFFFF8BCB),
    Color(0xFFC58AF9), Color(0xFF78D9EC), Color(0xFFFCAD70),
    Color(0xFF4DB6AC), Color(0xFFD4E157), Color(0xFF7986CB)
)
// Colors the user picked (group key -> palette index); others use a default.
private val GroupColorStore = mutableStateMapOf<Long, Int>()
private val GroupInk = Color(0xFF1B1D22)
private val PrivateColor = Color(0xFF8E5BFF)

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
    companion object {
        // Set by the UI; called when the app stops so tabs survive being closed.
        var saver: (() -> Unit)? = null

        // A link opened from outside (or a pinned shortcut): opened in a new tab.
        var pendingUrl by mutableStateOf<String?>(null)
    }

    private fun handleIntent(i: Intent?) {
        val u = i?.dataString
        if (i?.action == Intent.ACTION_VIEW && u != null && u.startsWith("http")) pendingUrl = u
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        saver?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // Download progress notifications need this on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        window.statusBarColor = android.graphics.Color.rgb(16, 17, 20)
        window.navigationBarColor = android.graphics.Color.rgb(16, 17, 20)
        setContent {
            // Slightly smaller text overall, closer to Brave's proportions.
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(d.density, d.fontScale * 0.92f)
            ) { SwiftBrowserApp() }
        }
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
    val appSettings = remember { AppSettings(context) }
    val uiScope = rememberCoroutineScope()
    val blocker = remember { TrackerBlocker(context) }
    val session = remember { loadSession(context) }
    val startTab = session?.let { sess -> sess.tabs.firstOrNull { it.id == sess.activeId } }
    val idCounter = remember { AtomicLong(session?.nextId ?: 2L) }
    val firstParty = remember { java.util.concurrent.ConcurrentHashMap<Long, String>() }
    val tabs = remember {
        mutableStateListOf<BrowserTab>().apply { addAll(session?.tabs ?: listOf(BrowserTab(1))) }
    }
    var activeId by remember { mutableLongStateOf(session?.activeId ?: 1L) }
    var address by remember { mutableStateOf(startTab?.url?.takeIf { it.startsWith("http") } ?: "") }
    var pageTitle by remember { mutableStateOf(startTab?.title ?: "Swift Browser") }
    var pageUrl by remember { mutableStateOf(startTab?.url ?: "swift://home") }
    var blockedCount by remember { mutableIntStateOf(0) }
    var blockingEnabled by remember { mutableStateOf(appSettings.blockTrackers) }
    var privateMode by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // 0 = tabs, 1 = tab groups, 2 = private tabs (shown only while a private tab exists)
    var tabSection by remember { mutableIntStateOf(0) }
    var showShield by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    var addressFocused by remember { mutableStateOf(false) }
    val tabGroups = remember { mutableStateMapOf<Long, Long>().apply { putAll(session?.groups ?: emptyMap()) } }
    val groupNames = remember { mutableStateMapOf<Long, String>().apply { putAll(session?.names ?: emptyMap()) } }
    val groupUpdated = remember { mutableStateMapOf<Long, Long>().apply { putAll(session?.updated ?: emptyMap()) } }
    var pendingOpenGroup by remember { mutableStateOf<Long?>(null) }
    var pendingEditGroup by remember { mutableStateOf<Long?>(null) }
    var showGroupSheet by remember { mutableStateOf(false) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    // Closing all only touches the kind of tab you are looking at (private vs normal).
    val closeAllPrivate = showTabs && tabSection == 2
    var editGroupKey by remember { mutableStateOf<Long?>(null) }
    var colorPickKey by remember { mutableStateOf<Long?>(null) }
    var showAddToGroup by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var findMatches by remember { mutableIntStateOf(0) }
    var findIndex by remember { mutableIntStateOf(0) }
    val desktopSites = remember { mutableStateMapOf<Long, Boolean>() }
    var translateOffer by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var translateDone by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var translateMenu by remember { mutableStateOf(false) }
    // Page whose translate banner the user swiped away: a translation still in
    // flight must not pop the "translated" card straight back afterwards.
    var translateDismissedUrl by remember { mutableStateOf<String?>(null) }
    var contextMenu by remember { mutableStateOf<ContextTarget?>(null) }
    // Long-press on a video: Brave-style menu (download, frame, picture-in-picture).
    var videoTarget by remember { mutableStateOf<VideoTarget?>(null) }
    var videoTabId by remember { mutableLongStateOf(0L) }
    // Full screen video (WebView custom view).
    var fullscreenView by remember { mutableStateOf<android.view.View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    // Swipe on the address bar: the page turns like a book page to the next / previous tab.
    var swipeActive by remember { mutableStateOf(false) }
    var swipeP by remember { mutableFloatStateOf(0f) } // -1..1, negative = dragging left (next tab)
    var swipeCur by remember { mutableStateOf<Bitmap?>(null) }
    var swipeTarget by remember { mutableStateOf<BrowserTab?>(null) } // frozen once the swipe is committed
    val swipeGen = remember { IntArray(1) }
    // Page each tab was on when the user went back to the home page: Forward returns to it.
    val homeForward = remember { mutableStateMapOf<Long, String>() }
    var pendingLogin by remember { mutableStateOf<PendingLogin?>(null) }
    val passwordStore = remember { PasswordStore(context) }
    var settingsEntry by remember { mutableStateOf("main") }
    var downloadNoticeAt by remember { mutableLongStateOf(0L) }
    var downloadNoticeVisible by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    var selecting by remember { mutableStateOf(false) }
    val selectedTabs = remember { mutableStateListOf<Long>() }
    var showClearData by remember { mutableStateOf(false) }
    var webSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    // "Show the download card for a few seconds" (like Brave's Downloading file... card).
    LaunchedEffect(downloadNoticeAt) {
        if (downloadNoticeAt != 0L) {
            downloadNoticeVisible = true
            delay(4500)
            downloadNoticeVisible = false
        }
    }

    // Our own download engine: opens finished downloads when the Download settings ask for it.
    DisposableEffect(Unit) {
        DownloadEngine.init(context)
        DownloadEngine.onCompleted = { item ->
            val isPdf = item.mime == "application/pdf"
            if ((isPdf && appSettings.autoOpenPdf) || (appSettings.autoOpenDownloads && canAutoOpenMime(item.mime))) {
                DownloadActions.open(context, item)
            }
        }
        onDispose { DownloadEngine.onCompleted = null }
    }
    SideEffect { DownloadEngine.notificationsEnabled = appSettings.downloadNotifications }

    val recentClosed = remember { mutableStateListOf<Pair<String, String>>() }
    val pendingLoads = remember { HashMap<Long, String>() }
    val webArea = remember { android.graphics.Rect() }
    var groupCounter by remember { mutableLongStateOf(session?.groupCounter ?: (GROUP_ID_BASE + 1)) }
    // One WebView per tab: pages, history and network errors never leak between tabs.
    val webViews = remember { HashMap<Long, WebView>() }
    val refreshLayouts = remember { HashMap<Long, PullRefreshLayout>() }
    // Tabs that were restored but not opened yet load lazily, like Brave.
    val unloaded = remember {
        HashSet<Long>().apply {
            session?.tabs?.filter { it.url.startsWith("http") }?.forEach { add(it.id) }
        }
    }
    val savedThumbs = remember { HashMap<Long, Bitmap>() }
    val savedFavicons = remember { HashMap<Long, Bitmap>() }
    val savedHome = remember { arrayOfNulls<Bitmap>(1) }
    remember {
        FaviconStore.clear(); ThumbStore.clear(); GroupColorStore.clear()
        HomeThumbStore = BitmapFactory.decodeFile(File(context.filesDir, "home_thumb.jpg").path)
        savedHome[0] = HomeThumbStore
        session?.colors?.forEach { (k, v) -> GroupColorStore[k] = v }
        session?.tabs?.forEach { t ->
            BitmapFactory.decodeFile(File(context.filesDir, "thumb_${t.id}.jpg").path)?.let {
                ThumbStore[t.id] = it
                savedThumbs[t.id] = it
            }
            BitmapFactory.decodeFile(File(context.filesDir, "fav_${t.id}.png").path)?.let {
                FaviconStore[t.id] = it
                savedFavicons[t.id] = it
            }
        }
        0
    }
    var loadProgress by remember { mutableIntStateOf(100) }
    var totalBlocked by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val currentBlockingEnabled by rememberUpdatedState(blockingEnabled)
    val currentPrivateMode by rememberUpdatedState(privateMode)
    val isHome = pageUrl == "swift://home"
    var bookmarkRefresh by remember { mutableIntStateOf(0) }
    val bookmarkStore = remember { BookmarkStore(context) { store.bookmarks() } }
    val isBookmarked = remember(pageUrl, bookmarkRefresh) { bookmarkStore.isBookmarked(pageUrl) }

    fun activeWebView(): WebView? = webViews[activeId]

    fun updateTabById(id: Long, url: String? = null, title: String? = null, count: Int? = null) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i >= 0) {
            val t = tabs[i]
            tabs[i] = t.copy(url = url ?: t.url, title = title ?: t.title, blockedCount = count ?: t.blockedCount)
        }
    }

    // Snapshot of the homepage exactly as it is on screen (needs Android 8+).
    fun captureHomeThumb(id: Long) {
        if (Build.VERSION.SDK_INT < 26) return
        val activity = context as? android.app.Activity ?: return
        if (webArea.isEmpty) return
        try {
            val src = android.graphics.Rect(webArea)
            val full = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(
                activity.window, src, full,
                android.view.PixelCopy.OnPixelCopyFinishedListener { result ->
                    if (result == android.view.PixelCopy.SUCCESS) {
                        val w = 360
                        val h = (src.height() * w / src.width().toFloat()).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(full, w, h, true)
                        ThumbStore[id] = scaled
                        HomeThumbStore = scaled
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            // Thumbnails are best effort.
        }
    }

    // Snapshot of a tab's page, shown on its card in the tab overview.
    fun captureThumb(id: Long) {
        val tab0 = tabs.firstOrNull { it.id == id } ?: return
        if (tab0.url == "swift://home") {
            captureHomeThumb(id)
            return
        }
        val wv = webViews[id] ?: return
        val tab = tab0
        if (wv.width <= 0 || wv.height <= 0) return
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

    // Brings a restored tab's page back (with its back/forward history when possible).
    fun loadTabContent(id: Long, wv: WebView) {
        var restored = false
        val f = File(context.filesDir, "tab_$id.bin")
        if (f.exists()) {
            try {
                val bytes = f.readBytes()
                val p = Parcel.obtain()
                p.unmarshall(bytes, 0, bytes.size)
                p.setDataPosition(0)
                val b = p.readBundle(WebView::class.java.classLoader)
                p.recycle()
                restored = b != null && wv.restoreState(b) != null
            } catch (e: Exception) {
                restored = false
            }
        }
        if (!restored) {
            val u = tabs.firstOrNull { it.id == id }?.url
            if (u != null && u.startsWith("http")) wv.loadUrl(u)
        }
    }

    // Saves tabs, groups, page history, thumbnails and icons so that closing
    // the app and reopening it brings everything back.
    fun saveSession() {
        try {
            val saveTabs = tabs.filter { !it.isPrivate } // private tabs are never written to disk
            val o = org.json.JSONObject()
            o.put("active", activeId)
            o.put("next", idCounter.get())
            o.put("gc", groupCounter)
            val arr = org.json.JSONArray()
            saveTabs.forEach { t ->
                arr.put(org.json.JSONObject().put("id", t.id).put("url", t.url).put("title", t.title))
            }
            o.put("tabs", arr)
            fun mapJson(m: Map<Long, Any>): org.json.JSONObject {
                val j = org.json.JSONObject()
                m.forEach { (k, v) -> j.put(k.toString(), v) }
                return j
            }
            o.put("groups", mapJson(tabGroups.toMap()))
            o.put("names", mapJson(groupNames.toMap()))
            o.put("colors", mapJson(GroupColorStore.toMap()))
            o.put("updated", mapJson(groupUpdated.toMap()))
            context.getSharedPreferences("swift_session", Context.MODE_PRIVATE)
                .edit().putString("session", o.toString()).commit()

            val dir = context.filesDir
            val ids = saveTabs.map { it.id }.toSet()
            saveTabs.forEach { t ->
                val stateFile = File(dir, "tab_${t.id}.bin")
                val wv = webViews[t.id]
                if (wv != null && t.id !in unloaded && t.url.startsWith("http")) {
                    val b = Bundle()
                    if (wv.saveState(b) != null) {
                        val p = Parcel.obtain()
                        p.writeBundle(b)
                        val bytes = p.marshall()
                        p.recycle()
                        stateFile.writeBytes(bytes)
                    }
                } else if (!t.url.startsWith("http")) {
                    stateFile.delete()
                }
                ThumbStore[t.id]?.let { bmp ->
                    if (savedThumbs[t.id] !== bmp) {
                        java.io.FileOutputStream(File(dir, "thumb_${t.id}.jpg")).use {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 70, it)
                        }
                        savedThumbs[t.id] = bmp
                    }
                }
                FaviconStore[t.id]?.let { bmp ->
                    if (savedFavicons[t.id] !== bmp) {
                        java.io.FileOutputStream(File(dir, "fav_${t.id}.png")).use {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        savedFavicons[t.id] = bmp
                    }
                }
            }
            HomeThumbStore?.let { bmp ->
                if (savedHome[0] !== bmp) {
                    java.io.FileOutputStream(File(dir, "home_thumb.jpg")).use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 70, it)
                    }
                    savedHome[0] = bmp
                }
            }
            // Remove files of tabs that no longer exist.
            val pattern = Regex("^(tab|thumb|fav)_(\\d+)\\.")
            dir.listFiles()?.forEach { f ->
                val m = pattern.find(f.name)
                if (m != null && (m.groupValues[2].toLongOrNull() ?: -1L) !in ids) f.delete()
            }
        } catch (e: Exception) {
            // Saving is best effort; never crash the browser because of it.
        }
    }

    DisposableEffect(Unit) {
        MainActivity.saver = { saveSession() }
        onDispose { MainActivity.saver = null }
    }

    val saveHandler = remember { Handler(Looper.getMainLooper()) }
    val saveTask = remember { Runnable { MainActivity.saver?.invoke() } }
    fun scheduleSave() {
        saveHandler.removeCallbacks(saveTask)
        saveHandler.postDelayed(saveTask, 4000)
    }

    fun updateActiveTab(url: String = pageUrl, title: String = pageTitle, count: Int = blockedCount) {
        val index = tabs.indexOfFirst { it.id == activeId }
        if (index >= 0) tabs[index] = tabs[index].copy(url = url, title = title, blockedCount = count)
    }

    // ---- HTTPS first (like Brave): try https for http links, fall back if the site has none ----
    val httpsUpgrades = remember { HashMap<String, String>() }   // https url we tried -> original http url
    val noUpgradeHosts = remember { HashSet<String>() }
    val lastUpgradeAt = remember { HashMap<String, Long>() }

    fun httpsFirst(url: String): String {
        if (!url.startsWith("http://")) return url
        val uri = Uri.parse(url)
        val host = uri.host ?: return url
        if (uri.port != -1 || host in noUpgradeHosts || host == "localhost" || host.endsWith(".local") ||
            host.contains(':') || Regex("^[0-9.]+$").matches(host)
        ) return url
        val now = System.currentTimeMillis()
        // The site sent us straight back to http: stop upgrading it.
        if (now - (lastUpgradeAt[host] ?: 0L) < 6000L) { noUpgradeHosts.add(host); return url }
        lastUpgradeAt[host] = now
        val https = "https://" + url.removePrefix("http://")
        httpsUpgrades[https] = url
        return https
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
            return appSettings.searchUrl(value)
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
            if (value == "swift://home" && pageUrl.startsWith("http")) homeForward[activeId] = pageUrl
            pageUrl = value
            address = ""
            pageTitle = if (value == "swift://home") "New tab" else "Swift Browser"
            blockedCount = 0
            updateActiveTab(pageUrl, pageTitle, 0)
            // Blank the tab's WebView so no old page can show through later.
            activeWebView()?.let { it.loadUrl("about:blank"); it.clearHistory() }
            ThumbStore.remove(activeId)
            findOpen = false
            loadProgress = 100
            showSuggestions = false
            showTabs = false
            keyboardController?.hide()
            focusManager.clearFocus()
            return
        }

        homeForward.remove(activeId) // a new page was opened: nothing to go forward to any more
        val url = httpsFirst(normalize(value))
        if (url == appSettings.searchUrl(value) && tabs.firstOrNull { it.id == activeId }?.isPrivate != true) {
            store.addQuery(value) // typed searches feed the suggestions
        }
        pageUrl = url
        address = url
        blockedCount = 0
        pageTitle = url
        updateActiveTab(url, pageTitle, 0)
        unloaded.remove(activeId)
        loadProgress = 5
        activeWebView()?.loadUrl(url)
        showSuggestions = false
        showTabs = false
        // Hide the keyboard so the loaded page / search results are
        // immediately visible — like Brave.
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    fun newTab(private: Boolean = false) {
        val tab = BrowserTab(idCounter.getAndIncrement(), isPrivate = private)
        tabs.add(tab)
        activeId = tab.id
        pageUrl = "swift://home"
        address = ""
        pageTitle = "New tab"
        blockedCount = 0
        showTabs = false
    }

    // "+" in the Groups section: a brand-new group that holds one fresh tab.
    fun newGroupWithTab() {
        val gid = groupCounter++
        val id = idCounter.get()
        newTab()
        tabGroups[id] = gid
        groupUpdated[gid] = System.currentTimeMillis()
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
        findOpen = false
        loadProgress = 100
        if (unloaded.remove(tab.id)) webViews[tab.id]?.let { loadTabContent(tab.id, it) }
        canGoBack = webViews[tab.id]?.canGoBack() == true
    }

    fun recordClosed(tab: BrowserTab) {
        if (tab.isPrivate || !tab.url.startsWith("http")) return
        recentClosed.removeAll { it.second == tab.url }
        recentClosed.add(0, tab.title to tab.url)
        while (recentClosed.size > 15) recentClosed.removeAt(recentClosed.size - 1)
    }

    fun closeTab(tab: BrowserTab, fromOverview: Boolean = true) {
        recordClosed(tab)
        if (tabs.size == 1) {
            tabs[0] = BrowserTab(tab.id)
            tabGroups.clear()
            unloaded.remove(tab.id)
            webViews[tab.id]?.let { it.loadUrl("about:blank"); it.clearHistory() }
            ThumbStore.remove(tab.id)
            FaviconStore.remove(tab.id)
            selectTab(tabs[0])
            return
        }
        val index = tabs.indexOfFirst { it.id == tab.id }
        // Closing a tab inside a group goes to the neighbouring tab of the same group;
        // only when the group is empty does it go to the previous tab or group.
        val closedGroup = tabGroups[tab.id]
        val groupMates = if (closedGroup != null) tabs.filter { tabGroups[it.id] == closedGroup } else emptyList()
        val matePos = groupMates.indexOfFirst { it.id == tab.id }
        val remainingMates = groupMates.filter { it.id != tab.id }
        tabs.removeAll { it.id == tab.id }
        tabGroups.remove(tab.id)
        // There is always at least one normal tab; private tabs live beside them.
        if (tabs.none { !it.isPrivate }) tabs.add(0, BrowserTab(idCounter.getAndIncrement()))
        if (activeId == tab.id) {
            val sameKind = tabs.filter { it.isPrivate == tab.isPrivate }
            val next = remainingMates.getOrNull(matePos) ?: remainingMates.lastOrNull()
                ?: sameKind.getOrNull((index - 1).coerceAtLeast(0).coerceAtMost(sameKind.size - 1))
                ?: tabs.lastOrNull { !it.isPrivate } ?: tabs.first()
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

    // Reorder: move a tab (or a whole group) before/after another tab or group.
    fun moveEntry(sourceKey: Long, targetKey: Long, after: Boolean) {
        if (sourceKey == targetKey) return
        fun idsOf(key: Long): List<Long> =
            if (key >= GROUP_ID_BASE) tabs.filter { tabGroups[it.id] == key }.map { it.id } else listOf(key)
        val srcIds = idsOf(sourceKey).toSet()
        val tgtIds = idsOf(targetKey).toSet()
        if (srcIds.isEmpty() || tgtIds.isEmpty() || srcIds.any { it in tgtIds }) return
        val moving = tabs.filter { it.id in srcIds }
        val rest = tabs.filter { it.id !in srcIds }.toMutableList()
        val idx = if (after) rest.indexOfLast { it.id in tgtIds } + 1 else rest.indexOfFirst { it.id in tgtIds }
        if (idx < 0) return
        rest.addAll(idx.coerceIn(0, rest.size), moving)
        // Called live while dragging: ignore moves that change nothing.
        if (rest.map { it.id } == tabs.map { it.id }) return
        tabs.clear()
        tabs.addAll(rest)
    }

    // Group-icon action: collect every loose tab into one group.
    fun groupAllTabs() {
        val loose = tabs.filter { tabGroups[it.id] == null && !it.isPrivate }
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
        // Closes the tabs of the section you are looking at (normal or private).
        val priv = showTabs && tabSection == 2
        tabs.filter { it.isPrivate == priv }.forEach { recordClosed(it) }
        tabs.removeAll { it.isPrivate == priv }
        if (!priv) {
            tabGroups.clear()
            tabs.add(0, BrowserTab(idCounter.getAndIncrement()))
        }
        tabSection = 0
        selectTab(tabs.last { !it.isPrivate })
        showTabs = false
        Toast.makeText(context, if (priv) "Private tabs closed" else "All tabs closed", Toast.LENGTH_SHORT).show()
    }

    // Failed first loads: how often each URL was retried, and which URLs just errored.
    val retryCounts = remember { HashMap<String, Int>() }
    val erroredUrls = remember { HashSet<String>() }

    // ---- Swipe on the address bar: book-style page turn to the next / previous tab ----
    // (same kind only: normal tabs stay with normal tabs, private with private)
    fun neighborFor(dir: Int): BrowserTab? {
        val cur = tabs.firstOrNull { it.id == activeId } ?: return null
        val pool = tabs.filter { it.isPrivate == cur.isPrivate }
        val i = pool.indexOfFirst { it.id == activeId }
        return pool.getOrNull(i + dir)
    }

    fun beginSwipe() {
        val activity = context as? android.app.Activity ?: return
        if (Build.VERSION.SDK_INT < 26 || webArea.isEmpty) return
        val gen = ++swipeGen[0]
        swipeP = 0f
        val src = android.graphics.Rect(webArea)
        val bmp = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        try {
            // A full-size picture of the page, so it looks identical while it turns.
            android.view.PixelCopy.request(
                activity.window, src, bmp,
                android.view.PixelCopy.OnPixelCopyFinishedListener { r ->
                    if (r == android.view.PixelCopy.SUCCESS && swipeGen[0] == gen) {
                        swipeCur = bmp
                        swipeActive = true
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) { /* no animation, the swipe just won't switch */ }
    }

    fun updateSwipe(totalPx: Float) {
        val w = context.resources.displayMetrics.widthPixels.toFloat()
        var p = (totalPx / w).coerceIn(-1f, 1f)
        // No tab on that side: only a small elastic nudge.
        if (neighborFor(if (p < 0) 1 else -1) == null) p = (p * 0.15f)
        swipeP = p
    }

    fun endSwipe(velX: Float) {
        swipeGen[0]++
        val p = swipeP
        val nb = neighborFor(if (p < 0) 1 else -1)
        val flung = kotlin.math.abs(velX) > 900f && (velX < 0) == (p < 0)
        val commit = nb != null && (kotlin.math.abs(p) > 0.3f || flung)
        if (!swipeActive) {
            // No picture to animate (capture failed / not ready yet): still switch, just without the slide.
            swipeP = 0f
            if (commit && nb != null) selectTab(nb)
            return
        }
        uiScope.launch {
            // Freeze the revealed tab: selectTab() changes the active tab, which would otherwise
            // make the overlay show the NEXT neighbour (the 'blink') until it is removed.
            swipeTarget = if (commit) nb else null
            animate(p, if (commit) (if (p < 0) -1f else 1f) else 0f, animationSpec = tween(if (commit) 200 else 170)) { v, _ -> swipeP = v }
            if (commit && nb != null) {
                selectTab(nb)
                delay(120) // let the real page paint before the picture goes away
            }
            swipeActive = false
            swipeP = 0f
            swipeCur = null
            swipeTarget = null
        }
    }

    // ---- Video: long-press menu, frame grab, picture-in-picture, fullscreen ----
    fun probeVideoAt(wv: WebView, tabId: Long, px: Float, py: Float) {
        @Suppress("DEPRECATION")
        val sc = wv.scale.takeIf { it > 0f } ?: context.resources.displayMetrics.density
        val js = VIDEO_PROBE_JS.replace("__X__", (px / sc).toString()).replace("__Y__", (py / sc).toString())
        wv.evaluateJavascript(js) { raw ->
            val str = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            if (str.startsWith("{")) {
                val src = runCatching { org.json.JSONObject(str).optString("src") }.getOrNull()
                    ?.takeIf { it.startsWith("http") }
                videoTabId = tabId
                videoTarget = VideoTarget(src, if (src != null) URLUtil.guessFileName(src, null, "video/mp4") else "Video")
                wv.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                wv.evaluateJavascript("(function(){var s=window.getSelection&&window.getSelection();if(s)s.removeAllRanges();})()", null)
            }
        }
    }

    fun captureVideoFrame(onBitmap: (Bitmap?) -> Unit) {
        val wv = webViews[videoTabId]
        if (wv == null) { onBitmap(null); return }
        wv.evaluateJavascript(VIDEO_FRAME_JS) { raw ->
            val str = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            if (!str.startsWith("data:image")) { onBitmap(null); return@evaluateJavascript }
            val bytes = runCatching { android.util.Base64.decode(str.substringAfter(','), android.util.Base64.DEFAULT) }.getOrNull()
            onBitmap(bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) })
        }
    }

    fun copyVideoFrame() {
        captureVideoFrame { bmp ->
            if (bmp == null) {
                Toast.makeText(context, "Can't capture a frame from this video", Toast.LENGTH_SHORT).show()
                return@captureVideoFrame
            }
            runCatching {
                val dir = File(context.cacheDir, "video_frames").apply { mkdirs() }
                val f = File(dir, "frame.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "Video frame", uri))
                Toast.makeText(context, "Video frame copied", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Couldn't copy the frame", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveVideoFrame() {
        captureVideoFrame { bmp ->
            if (bmp == null) {
                Toast.makeText(context, "Can't capture a frame from this video", Toast.LENGTH_SHORT).show()
                return@captureVideoFrame
            }
            runCatching {
                val name = "video_frame_" + System.currentTimeMillis() + ".jpg"
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SwiftBrowser")
                    }
                    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                        ?: throw java.io.IOException("insert failed")
                    context.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                } else {
                    val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.filesDir
                    File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                }
                Toast.makeText(context, "Frame saved to Pictures", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Couldn't save the frame", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exitFullscreen() {
        val v = fullscreenView ?: return
        val act = context as? android.app.Activity
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        if (act != null) {
            androidx.core.view.WindowCompat.getInsetsController(act.window, act.window.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Picture-in-Picture: the video goes full screen, then the window shrinks to the video.
    fun startPictureInPicture() {
        val wv = webViews[videoTabId] ?: return
        wv.evaluateJavascript(VIDEO_FULLSCREEN_JS, null)
        uiScope.launch {
            delay(600)
            val act = context as? android.app.Activity
            if (fullscreenView != null && act != null && Build.VERSION.SDK_INT >= 26) {
                runCatching {
                    act.enterPictureInPictureMode(
                        android.app.PictureInPictureParams.Builder()
                            .setAspectRatio(android.util.Rational(16, 9)).build()
                    )
                }.onFailure { Toast.makeText(context, "Picture-in-Picture isn't available", Toast.LENGTH_SHORT).show() }
            } else {
                Toast.makeText(context, "Picture-in-Picture isn't available for this video", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deviceLang(): String = java.util.Locale.getDefault().language

    fun applyDesktopJs(view: WebView?, tabId: Long) {
        if (desktopSites[tabId] == true) view?.evaluateJavascript(DESKTOP_JS, null)
    }

    // Translates the open page on the device (ML Kit): checks the language packs,
    // downloads what is missing (with clear errors when offline / Wi-Fi only),
    // then injects the page script that rewrites the text in place.
    fun translatePageInPlace(knownSource: String? = null) {
        val wv = activeWebView() ?: return
        val startTab = activeId
        val startUrl = pageUrl
        val offered = knownSource ?: translateOffer?.second.orEmpty()
        translateOffer = null
        translateDone = null
        translateMenu = false
        translateDismissedUrl = null

        fun startTranslation(rawSource: String) {
            val src = TranslateEngine.normalize(rawSource)
            val tgt = appSettings.translateTarget
            if (src == null) {
                Toast.makeText(context, "Offline translation doesn't support this page's language", Toast.LENGTH_LONG).show()
                return
            }
            if (src == tgt) {
                Toast.makeText(context, "This page is already in " + TranslateEngine.displayName(tgt), Toast.LENGTH_SHORT).show()
                return
            }
            uiScope.launch {
                try {
                    TranslateEngine.ensureModels(context, src, tgt, appSettings.wifiOnlyModels) {
                        Toast.makeText(
                            context,
                            "Downloading language pack (about " + TranslateEngine.MODEL_MB + " MB)...",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (activeId == startTab && pageUrl == startUrl) {
                        wv.evaluateJavascript(TRANSLATE_JS + "\nwindow.__swiftTr.start('" + src + "');", null)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "Translation failed", Toast.LENGTH_LONG).show()
                }
            }
        }

        if (offered.isNotEmpty()) {
            startTranslation(offered)
        } else {
            wv.evaluateJavascript(LANG_JS) { raw ->
                val detected = raw?.trim('"')?.lowercase().orEmpty().let { if (it == "null") "" else it }
                startTranslation(if (detected.isEmpty()) "en" else detected)
            }
        }
    }

    // Like Brave: after a page loads, offer to translate it if it isn't in your language
    // (or translate it straight away when the language pack is already downloaded).
    fun offerTranslateIfNeeded(view: WebView?, tabId: Long, url: String) {
        if (!appSettings.translateOffer && !appSettings.translateAuto) return
        val host = hostOf(url)
        if (host.endsWith("translate.goog") || host.contains("translate.google")) return
        val never = context.getSharedPreferences("swift_translate", Context.MODE_PRIVATE)
            .getStringSet("never", emptySet()) ?: emptySet()
        if (host in never) return
        view?.evaluateJavascript(LANG_JS) { raw ->
            val code = raw?.trim('"')?.lowercase().orEmpty()
            val src = if (code.isEmpty() || code == "null") null else TranslateEngine.normalize(code)
            val tgt = appSettings.translateTarget
            if (src != null && src != tgt && tabId == activeId) {
                if (appSettings.translateAuto) {
                    uiScope.launch {
                        val have = runCatching { TranslateEngine.downloadedLanguages() }.getOrDefault(emptySet())
                        if (src in have && (tgt == "en" || tgt in have)) {
                            if (tabId == activeId) translatePageInPlace(src)
                        } else if (appSettings.translateOffer && tabId == activeId) {
                            translateOffer = tabId to src
                        }
                    }
                } else if (appSettings.translateOffer) {
                    translateOffer = tabId to src
                }
            }
        }
    }

    // Puts the original text back in the page itself, no reload needed.
    fun undoTranslate() {
        val wv = activeWebView() ?: return
        translateDone = null
        wv.evaluateJavascript("window.__swiftTr&&window.__swiftTr.restore();", null)
    }

    fun refreshNavigation() {
        canGoBack = activeWebView()?.canGoBack() == true
        canGoForward = activeWebView()?.canGoForward() == true
    }

    // Builds the WebView for one tab. Callbacks only touch the shared UI state
    // when this tab is the active one; otherwise they update just this tab.
    // Every download (page links, long-press menu) goes through here so the Download
    // settings (ask first, notifications) and the "Downloading file..." card always apply.
    fun enqueueDownload(d: PendingDownload) {
        val ua = d.userAgent ?: android.webkit.WebSettings.getDefaultUserAgent(context)
        val cookie = CookieManager.getInstance().getCookie(d.url)
        DownloadEngine.enqueue(d.url, d.filename, d.mime, ua, d.referer, cookie)
        downloadNoticeAt = System.currentTimeMillis()
    }

    fun startDownload(url: String, userAgent: String?, disposition: String?, mime: String?, referer: String?) {
        if (!url.startsWith("http")) {
            Toast.makeText(context, "This link can't be downloaded", Toast.LENGTH_SHORT).show()
            return
        }
        val d = PendingDownload(url, userAgent, disposition, mime, referer, URLUtil.guessFileName(url, disposition, mime))
        if (appSettings.askBeforeDownload) pendingDownload = d else enqueueDownload(d)
    }

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
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            settings.mediaPlaybackRequiresUserGesture = true
            setFindListener(
                WebView.FindListener { active, count, _ ->
                    if (tabId == activeId) {
                        findMatches = count
                        findIndex = if (count > 0) active + 1 else 0
                    }
                }
            )
            CookieManager.getInstance().setAcceptCookie(true)

            // On-device translation bridge (see WebAppInterface.kt / TranslateJs.kt)
            addJavascriptInterface(WebAppInterface(
                webView = this,
                getTarget = { appSettings.translateTarget },
                onFirstApplied = { sourceLang ->
                    if (tabId == activeId && translateDismissedUrl != pageUrl) translateDone = activeId to sourceLang
                },
                onError = { error ->
                    Toast.makeText(ctx, error, Toast.LENGTH_LONG).show()
                }
            ), "SwiftTranslator")

            // Saved settings: text size, JavaScript, images, Safe Browsing, third-party cookies.
            appSettings.applyTo(this)
            if (tabs.firstOrNull { it.id == tabId }?.isPrivate == true) {
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                settings.saveFormData = false
            }

            // Password Manager: the page reports sign-in submits (never reads anything back).
            addJavascriptInterface(PasswordBridge(this) { host, user, pass ->
                if (host.isNotEmpty() && passwordStore.offerToSave && !currentPrivateMode && tabs.firstOrNull { it.id == tabId }?.isPrivate != true && !passwordStore.isNever(host)) {
                    val existing = passwordStore.find(host, user)
                    if (existing == null || existing.password != pass) {
                        pendingLogin = PendingLogin(host, user, pass, existing != null)
                    }
                }
            }, "SwiftPasswords")

            // Remember where the finger went down (needed to find a video under a long press).
            val lastTouch = FloatArray(2)
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { lastTouch[0] = ev.x; lastTouch[1] = ev.y }
                false
            }

            // Press and hold on a link or image: Brave-style menu.
            setOnLongClickListener { v ->
                val wv = v as WebView
                val hit = wv.hitTestResult
                val type = hit.type
                val extra = hit.extra
                val isLink = type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                val isImage = type == WebView.HitTestResult.IMAGE_TYPE ||
                    type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                if ((!isLink && !isImage) || extra == null || extra.startsWith("javascript:")) {
                    // Maybe a video: look under the finger (normal text selection still works).
                    if (!isLink && !isImage) probeVideoAt(wv, tabId, lastTouch[0], lastTouch[1])
                    return@setOnLongClickListener false
                }
                wv.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                val msg = Handler(Looper.getMainLooper()) { m ->
                    val href = m.data?.getString("url")?.takeIf { it.isNotBlank() }
                    val title = m.data?.getString("title")?.takeIf { it.isNotBlank() }
                    val linkUrl = if (isLink) (href ?: if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) extra else null) else null
                    val imageUrl = if (isImage) extra else null
                    if (linkUrl != null || imageUrl != null) contextMenu = ContextTarget(linkUrl, title, imageUrl)
                    true
                }.obtainMessage()
                wv.requestFocusNodeHref(msg)
                true
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: android.view.View?, callback: WebChromeClient.CustomViewCallback?) {
                    val act = ctx as? android.app.Activity ?: (context as? android.app.Activity)
                    if (view == null || act == null) { callback?.onCustomViewHidden(); return }
                    if (fullscreenView != null) exitFullscreen()
                    view.setBackgroundColor(android.graphics.Color.BLACK)
                    (act.window.decorView as android.view.ViewGroup).addView(
                        view,
                        android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    fullscreenView = view
                    fullscreenCallback = callback
                    androidx.core.view.WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
                        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    }
                    act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }

                override fun onHideCustomView() { exitFullscreen() }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val t = title ?: return
                    if (view?.url?.startsWith("about:") == true) return
                    updateTabById(tabId, title = t)
                    if (tabId == activeId) pageTitle = t
                }

                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    if (icon != null) {
                        FaviconStore[tabId] = icon
                        view?.url?.let { u -> HostFavicons[hostOf(u).removePrefix("www.")] = icon }
                    }
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress in 15..95) applyDesktopJs(view, tabId)
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
                    if (uri.scheme == "http" && request.isForMainFrame && view != null) {
                        val up = httpsFirst(uri.toString())
                        if (up != uri.toString()) {
                            view.loadUrl(up)
                            return true
                        }
                    }
                    return uri.scheme !in listOf("http", "https")
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    handler?.cancel()
                    val failed = error?.url ?: return
                    // The https upgrade didn't work out: go back to the plain http page.
                    httpsUpgrades.remove(failed)?.let { http ->
                        noUpgradeHosts.add(hostOf(http))
                        view?.post { view.loadUrl(http) }
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val req = request ?: return null
                    val url = req.url.toString()

                    val h = req.url.host ?: ""
                    if (h == "translate.google.com" || h == "translate.googleapis.com" ||
                        h.endsWith(".translate.goog") ||
                        (h == "www.gstatic.com" && url.contains("/translate/"))
                    ) return null

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

                // First loads often fail with a DNS / connection error and work a moment later
                // (cold radio, slow resolver). Retry the page quietly a couple of times first.
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame != true || view == null) return
                    val code = error?.errorCode ?: return
                    val retryable = code == WebViewClient.ERROR_HOST_LOOKUP || code == WebViewClient.ERROR_CONNECT ||
                        code == WebViewClient.ERROR_TIMEOUT || code == WebViewClient.ERROR_IO
                    val failed = request.url.toString()
                    // https-first didn't work (site has no https): load the http page instead.
                    if (code != WebViewClient.ERROR_HOST_LOOKUP) {
                        httpsUpgrades.remove(failed)?.let { http ->
                            noUpgradeHosts.add(hostOf(http))
                            view.post { view.loadUrl(http) }
                            return
                        }
                    }
                    erroredUrls.add(failed)
                    val tries = retryCounts[failed] ?: 0
                    if (retryable && tries < 3) {
                        retryCounts[failed] = tries + 1
                        view.postDelayed({
                            if (webViews[tabId] === view && (view.url == failed || view.originalUrl == failed)) view.loadUrl(failed)
                        }, 500L * (tries + 1))
                    }
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    if (url == null || url.startsWith("about:")) return
                    if (translateOffer?.first == tabId) translateOffer = null
                    if (translateDone?.first == tabId) translateDone = null
                    if (tabId == activeId) translateDismissedUrl = null
                    applyDesktopJs(view, tabId)
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

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    applyDesktopJs(view, tabId)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null || url.startsWith("about:")) return
                    if (url in erroredUrls) erroredUrls.remove(url) else retryCounts.remove(url)
                    applyDesktopJs(view, tabId)
                    updateTabById(tabId, url = url)
                    refreshLayouts[tabId]?.endRefresh()
                    val title = tabs.firstOrNull { it.id == tabId }?.title ?: url
                    if (tabId == activeId) {
                        pageUrl = url
                        address = url
                        loadProgress = 100
                        refreshNavigation()
                    }
                    if (!currentPrivateMode && tabs.firstOrNull { it.id == tabId }?.isPrivate != true) store.addHistory(title, url)
                    Handler(Looper.getMainLooper()).postDelayed({ captureThumb(tabId) }, 700)
                    if (tabId == activeId && url.startsWith("http")) offerTranslateIfNeeded(view, tabId, url)
                    if (url.startsWith("http")) {
                        if (passwordStore.offerToSave) view?.evaluateJavascript(PASSWORD_CAPTURE_JS, null)
                        if (passwordStore.autoFill && url.startsWith("https://")) {
                            val saved = passwordStore.loginsFor(PasswordStore.hostKey(url)).maxByOrNull { it.updated }
                            if (saved != null) {
                                val fill = passwordFillJs(saved.username, saved.password)
                                view?.evaluateJavascript(fill, null)
                                Handler(Looper.getMainLooper()).postDelayed({ view?.evaluateJavascript(fill, null) }, 1200)
                            }
                        }
                    }
                    scheduleSave()
                }
            }

            setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
                startDownload(downloadUrl, userAgent, contentDisposition, mimeType, this.url)
            }

            webViews[tabId] = this
            // The tab that was open when the app closed comes back right away;
            // other restored tabs load when you first open them.
            if (tabId == activeId && unloaded.remove(tabId)) loadTabContent(tabId, this)
            pendingLoads.remove(tabId)?.let { loadUrl(it) }
        }
    }

    fun bookmarksNow(): List<Pair<String, String>> {
        val version = bookmarkRefresh // re-read whenever a bookmark changes
        return if (version >= 0) bookmarkStore.list() else emptyList()
    }

    fun toggleBookmark() {
        if (!pageUrl.startsWith("http")) return
        val title = pageTitle.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: hostOf(pageUrl)
        if (bookmarkStore.isBookmarked(pageUrl)) {
            bookmarkStore.remove(pageUrl)
            Toast.makeText(context, "Bookmark removed", Toast.LENGTH_SHORT).show()
        } else {
            bookmarkStore.add(title, pageUrl)
            Toast.makeText(context, "Bookmark saved", Toast.LENGTH_SHORT).show()
        }
        bookmarkRefresh++
    }

    // Back only counts as "history" when the previous entry is a real page.
    fun canGoBackReal(): Boolean {
        val wv = activeWebView() ?: return false
        if (!wv.canGoBack()) return false
        val list = wv.copyBackForwardList()
        if (list.currentIndex <= 0) return false
        val prev = list.getItemAtIndex(list.currentIndex - 1) ?: return false
        return !(prev.url ?: "").startsWith("about:")
    }

    BackHandler(enabled = true) {
        when {
            fullscreenView != null -> exitFullscreen()
            showMenu -> showMenu = false
            findOpen -> {
                findOpen = false
                activeWebView()?.clearMatches()
            }
            showGroupSheet -> showGroupSheet = false
            showShield -> showShield = false
            showTabs -> showTabs = false
            showSuggestions -> showSuggestions = false
            address == "swift://bookmarks" || address == "swift://history" || address == "swift://downloads" ||
                address == "swift://settings" ->
                address = if (isHome) "" else pageUrl
            canGoBackReal() -> {
                activeWebView()?.goBack()
                refreshNavigation()
            }
            !isHome -> load("swift://home")
            // Keep the app (and all tabs) alive instead of closing it.
            else -> (context as? android.app.Activity)?.moveTaskToBack(true)
        }
    }

    fun openUrlInNewTab(u: String) {
        newTab()
        pendingLoads[activeId] = u
        load(u)
    }

    // Opens next to the current tab and groups the two (joins the group it is in, if any).
    fun openUrlInNewTabInGroup(u: String) {
        val source = activeId
        openUrlInNewTab(u)
        handleGroupDrop(activeId, source)
    }

    fun openUrlInPrivateTab(u: String) {
        newTab(private = true)
        pendingLoads[activeId] = u
        load(u)
    }

    // Downloads a link or image straight from the long-press menu.
    fun downloadFromUrl(u: String) {
        startDownload(u, null, null, null, pageUrl)
    }

    LaunchedEffect(MainActivity.pendingUrl) {
        val u = MainActivity.pendingUrl
        if (u != null) {
            openUrlInNewTab(u)
            MainActivity.pendingUrl = null
        }
    }

    fun startPrivateTab() {
        newTab(private = true)
        Toast.makeText(context, "Private tab: history isn't saved", Toast.LENGTH_SHORT).show()
    }

    fun toggleDesktopSite() {
        val wv = activeWebView() ?: return
        val on = !(desktopSites[activeId] ?: false)
        desktopSites[activeId] = on
        // Setting the UA to null does not restore the phone UA on WebView, so
        // switch back to the real default explicitly.
        wv.settings.userAgentString =
            if (on) desktopUserAgent(context) else android.webkit.WebSettings.getDefaultUserAgent(context)
        wv.settings.useWideViewPort = on
        wv.settings.loadWithOverviewMode = on
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.clearCache(true)
        wv.loadUrl(desktopEquivalent(wv.url ?: pageUrl, on))
    }

    // Clears cookies, storage and cache of the current site and closes its tabs.
    fun shredSiteData() {
        val url = pageUrl
        val host = hostOf(url)
        if (host.isEmpty()) return
        val cm = CookieManager.getInstance()
        cm.getCookie(url)?.split(";")?.forEach { c ->
            val name = c.substringBefore("=").trim()
            if (name.isNotEmpty()) {
                listOf("", "; Domain=$host", "; Domain=.$host").forEach { d ->
                    cm.setCookie(url, "$name=; Max-Age=0; Path=/$d")
                }
            }
        }
        cm.flush()
        val uri = Uri.parse(url)
        android.webkit.WebStorage.getInstance().deleteOrigin((uri.scheme ?: "https") + "://" + (uri.host ?: host))
        activeWebView()?.clearCache(true)
        tabs.filter { hostOf(it.url) == host }.forEach { closeTab(it, false) }
        recentClosed.removeAll { hostOf(it.second) == host }
        Toast.makeText(context, "Site data shredded", Toast.LENGTH_SHORT).show()
    }

    fun installShortcut() {
        if (Build.VERSION.SDK_INT < 26 || !pageUrl.startsWith("http")) {
            Toast.makeText(context, "Shortcuts aren't supported here", Toast.LENGTH_SHORT).show()
            return
        }
        val sm = context.getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(context, "Your launcher can't pin shortcuts", Toast.LENGTH_SHORT).show()
            return
        }
        val launch = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(pageUrl))
        val icon = FaviconStore[activeId]?.let { android.graphics.drawable.Icon.createWithBitmap(it) }
            ?: android.graphics.drawable.Icon.createWithResource(context, context.applicationInfo.icon)
        val info = android.content.pm.ShortcutInfo.Builder(context, "page_" + pageUrl.hashCode())
            .setShortLabel(pageTitle.ifBlank { hostOf(pageUrl) }.take(24))
            .setIcon(icon)
            .setIntent(launch)
            .build()
        sm.requestPinShortcut(info, null)
    }

    fun saveCurrentPage() {
        val wv = activeWebView() ?: return
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(dir, hostOf(pageUrl).ifBlank { "page" } + "-" + System.currentTimeMillis() + ".mht")
        wv.saveWebArchive(file.absolutePath, false) { saved ->
            Toast.makeText(
                context,
                if (saved != null) "Page saved: " + file.name else "Couldn't save this page",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Menu button, shared by the normal bottom bar and the tab-overview bar.
    // The menu itself is drawn as an overlay at the end of this screen.
    @Composable
    fun SettingsMenuBox(dots: Boolean = false) {
        Box {
            if (dots) {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.MoreVert, "Menu", tint = TextMain)
                }
            } else {
                ToolbarButton(
                    Icons.Default.MoreVert,
                    "Menu",
                    selected = showMenu
                ) {
                    showMenu = true
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, background = Bg, surface = Surface,
            onBackground = TextMain, onSurface = TextMain
        ),
        typography = MaterialTheme.typography.run {
            val f = androidx.compose.ui.text.font.FontFamily.SansSerif
            copy(
                bodyLarge = bodyLarge.copy(fontFamily = f, fontWeight = FontWeight.Normal),
                bodyMedium = bodyMedium.copy(fontFamily = f, fontWeight = FontWeight.Normal),
                bodySmall = bodySmall.copy(fontFamily = f, fontWeight = FontWeight.Normal),
                labelLarge = labelLarge.copy(fontFamily = f, fontWeight = FontWeight.Medium),
                titleMedium = titleMedium.copy(fontFamily = f, fontWeight = FontWeight.Medium)
            )
        }
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
            if (!showTabs && address != "swift://settings" && address != "swift://downloads") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(24.dp))
                        .background(if (addressFocused) Surface3 else Surface2)
                ) {
                Row(
                    Modifier.fillMaxSize()
                        .padding(start = 8.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // App logo inside the search bar.
                    Box(
                        Modifier.size(29.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Accent, Color(0xFFE94D25)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("S", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
                                    val q = searchQueryOf(address)
                                    if (q != null) address = q
                                    // Brave: an empty field with the current page shown as a card below it.
                                    else if (pageUrl.startsWith("http")) address = ""
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
                // Not editing: this layer owns every touch on the bar, so the address text can
                // never scroll under the finger. Tap = start typing, horizontal drag = switch tab.
                if (!addressFocused) {
                    Box(
                        Modifier.matchParentSize().pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val tracker = VelocityTracker()
                                tracker.addPosition(down.uptimeMillis, down.position)
                                var total = 0f
                                var totalY = 0f
                                var dragging = false
                                var cancelled = false
                                val slop = viewConfiguration.touchSlop
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) {
                                        if (dragging) endSwipe(tracker.calculateVelocity().x)
                                        else if (!cancelled) {
                                            addressFocusRequester.requestFocus()
                                            keyboardController?.show()
                                        }
                                        break
                                    }
                                    tracker.addPosition(ch.uptimeMillis, ch.position)
                                    val d = ch.positionChange()
                                    total += d.x
                                    totalY += d.y
                                    if (!dragging && !cancelled) {
                                        if (kotlin.math.abs(total) > slop && kotlin.math.abs(total) > kotlin.math.abs(totalY)) {
                                            dragging = true
                                            beginSwipe()
                                        } else if (kotlin.math.abs(totalY) > slop) {
                                            cancelled = true
                                        }
                                    }
                                    if (dragging) {
                                        ch.consume()
                                        updateSwipe(total)
                                    }
                                }
                            }
                        }
                    )
                }
                }
            }
            }

            // Find in page bar (menu > Find in page).
            if (findOpen && !showTabs) {
                val findFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { findFocus.requestFocus() }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(46.dp)
                        .clip(RoundedCornerShape(24.dp)).background(Surface2).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = findText,
                        onValueChange = {
                            findText = it
                            if (it.isBlank()) {
                                activeWebView()?.clearMatches()
                                findMatches = 0
                                findIndex = 0
                            } else {
                                activeWebView()?.findAllAsync(it)
                            }
                        },
                        modifier = Modifier.weight(1f).padding(start = 12.dp).focusRequester(findFocus),
                        singleLine = true,
                        textStyle = TextStyle(color = TextMain, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { activeWebView()?.findNext(true) }),
                        decorationBox = { inner ->
                            if (findText.isEmpty()) Text("Find in page", color = TextMuted, fontSize = 15.sp)
                            inner()
                        }
                    )
                    Text(
                        if (findMatches > 0) "$findIndex/$findMatches" else if (findText.isNotBlank()) "0/0" else "",
                        color = TextMuted, fontSize = 13.sp
                    )
                    IconButton(onClick = { activeWebView()?.findNext(false) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Previous", tint = TextMain)
                    }
                    IconButton(onClick = { activeWebView()?.findNext(true) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Next", tint = TextMain)
                    }
                    IconButton(
                        onClick = {
                            activeWebView()?.clearMatches()
                            findOpen = false
                            findText = ""
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Close, "Close", tint = TextMain)
                    }
                }
            }

            // Small protection strip opens the detailed shield dashboard.
            // Hidden in the tabs overview, like Brave, and while the address card is open.
            if (!showTabs && address != "swift://settings" && address != "swift://downloads" &&
                !(addressFocused && address.isBlank() && pageUrl.startsWith("http"))) {
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
                    Text("SHIELDS", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(15.dp))
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

            Box(
                Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                    val pos = it.positionInWindow()
                    webArea.set(
                        pos.x.toInt(), pos.y.toInt(),
                        (pos.x + it.size.width).toInt(), (pos.y + it.size.height).toInt()
                    )
                }
            ) {
                tabs.forEach { tab ->
                    key(tab.id) {
                        val isCurrent = tab.id == activeId
                        val pageVisible = isCurrent && !isHome && !showTabs &&
                            address != "swift://bookmarks" && address != "swift://history" &&
                            address != "swift://downloads" && address != "swift://settings"
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
                    visible = isHome && !showTabs && address != "swift://bookmarks" && address != "swift://history" &&
                        address != "swift://downloads" && address != "swift://settings",
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
                        bookmarks = bookmarksNow().take(5),
                        recent = store.history().take(4),
                        onShowBookmarks = { address = "swift://bookmarks" },
                        onShowHistory = { address = "swift://history" },
                        totalBlocked = totalBlocked
                    )
                }

                // Brave-style slide while swiping the address bar: the current page and the
                // neighbouring tab sit side by side and move together with the finger.
                // The address bar itself never moves.
                if (swipeActive) {
                    val p = swipeP
                    val nb = swipeTarget ?: neighborFor(if (p < 0) 1 else -1)
                    Box(Modifier.fillMaxSize().clipToBounds().background(Bg)) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            // p<0: next tab arrives from the right; p>0: previous tab from the left.
                            translationX = (if (p < 0) 1f else -1f) * size.width * (1f - kotlin.math.abs(p))
                        }) { SwipeTabPreview(nb) }
                        swipeCur?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(), contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize().graphicsLayer { translationX = p * size.width }
                            )
                        }
                    }
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
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color(0x73000000))
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                })
                            }
                    )
                }

                // Tapping the address bar on a page: the page as a card (share / copy / edit)
                // with quick-access sites under it, like Brave.
                androidx.compose.animation.AnimatedVisibility(
                    visible = addressFocused && address.isBlank() && pageUrl.startsWith("http") && !showTabs,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(80)),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    val fav = FaviconStore[activeId]
                    val quick = remember(pageUrl) {
                        (bookmarksNow() + store.history()).distinctBy { hostOf(it.second).removePrefix("www.") }
                            .filter { it.second.startsWith("http") }.take(10)
                    }
                    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Surface2)
                                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Surface3), contentAlignment = Alignment.Center) {
                                if (fav != null) Image(bitmap = fav.asImageBitmap(), contentDescription = null, modifier = Modifier.size(22.dp))
                                else Icon(Icons.Default.Public, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    pageTitle.ifBlank { hostOf(pageUrl) }, color = TextMain, fontSize = 15.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    prettyAddress(pageUrl), color = TextMuted, fontSize = 12.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { sharePage(context, pageUrl) }, modifier = Modifier.size(42.dp)) {
                                Icon(Icons.Default.Share, "Share", tint = TextMain, modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Link", pageUrl))
                                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(42.dp)
                            ) { Icon(Icons.Default.ContentCopy, "Copy link", tint = TextMain, modifier = Modifier.size(20.dp)) }
                            IconButton(
                                onClick = { address = pageUrl; showSuggestions = false },
                                modifier = Modifier.size(42.dp)
                            ) { Icon(Icons.Default.Edit, "Edit link", tint = TextMain, modifier = Modifier.size(20.dp)) }
                        }
                        if (quick.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 14.dp)
                                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                            ) {
                                quick.forEach { (title, url) ->
                                    Column(
                                        Modifier.width(84.dp).clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                load(url)
                                            }
                                            .padding(vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        val h = hostOf(url).removePrefix("www.")
                                        LaunchedEffect(h) {
                                            if (HostFavicons[h] == null) {
                                                withContext(Dispatchers.IO) { fetchFavicon(h) }?.let { HostFavicons[h] = it }
                                            }
                                        }
                                        Box(Modifier.size(56.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
                                            val ic = HostFavicons[h]
                                            if (ic != null) Image(bitmap = ic.asImageBitmap(), contentDescription = null, modifier = Modifier.size(30.dp))
                                            else Text(h.take(1).uppercase(), color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            title.ifBlank { hostOf(url) }, color = TextMuted, fontSize = 11.sp, maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                        entries = if (isBookmarks) bookmarksNow() else store.history(),
                        icon = if (isBookmarks) Icons.Default.Bookmark else Icons.Default.History,
                        onOpen = { load(it) },
                        onClose = { address = if (isHome) "" else pageUrl }
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = address == "swift://downloads",
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(100)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    DownloadsScreen(
                        onClose = { address = if (isHome) "" else pageUrl },
                        onOpenSettings = {
                            settingsEntry = "downloads"
                            address = "swift://settings"
                        }
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = address == "swift://settings",
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(100)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    SettingsScreen(
                        settings = appSettings,
                        passwords = passwordStore,
                        blockingEnabled = blockingEnabled,
                        onBlockingChange = { blockingEnabled = it; appSettings.putBlockTrackers(it) },
                        privateMode = privateMode,
                        onPrivateChange = { enable ->
                            privateMode = enable
                            if (enable) {
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                            }
                        },
                        onWebSettingsChanged = { webViews.values.forEach { appSettings.applyTo(it) } },
                        onClearCache = { webViews.values.forEach { it.clearCache(true) } },
                        // Wire your history store's clear function here to show "Browsing history"
                        // in the Clear browsing data dialog, e.g. { store.clearHistory() }.
                        onClearHistory = null,
                        onOpenPrivacyDashboard = {
                            address = if (isHome) "" else pageUrl
                            showShield = true
                        },
                        startPage = settingsEntry,
                        onExitStart = { address = "swift://downloads" },
                        onSetDefaultBrowser = {
                            runCatching {
                                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                            }
                        },
                        onOpenAutofillSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                                        .setData(Uri.parse("package:" + context.packageName))
                                )
                            }.onFailure {
                                Toast.makeText(context, "Autofill settings aren't available on this device", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onClose = { address = if (isHome) "" else pageUrl }
                    )
                }

                contextMenu?.let { target ->
                    PageContextMenu(
                        target = target,
                        referer = pageUrl,
                        onDismiss = { contextMenu = null },
                        onOpenInNewTab = { openUrlInNewTab(it) },
                        onOpenInGroup = { openUrlInNewTabInGroup(it) },
                        onOpenPrivate = { openUrlInPrivateTab(it) },
                        onDownload = { downloadFromUrl(it) },
                        onAddBookmark = { title, link ->
                            bookmarkStore.add(title, link)
                            bookmarkRefresh++
                            Toast.makeText(context, "Added to bookmarks", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                videoTarget?.let { vt ->
                    VideoContextMenu(
                        target = vt,
                        onDismiss = { videoTarget = null },
                        onDownloadVideo = { vt.src?.let { downloadFromUrl(it) } },
                        onCopyFrame = { copyVideoFrame() },
                        onDownloadFrame = { saveVideoFrame() },
                        onPictureInPicture = { startPictureInPicture() }
                    )
                }
                pendingLogin?.let { pending ->
                    SavePasswordDialog(
                        pending = pending,
                        onSave = {
                            passwordStore.save(pending.host, pending.username, pending.password)
                            pendingLogin = null
                            Toast.makeText(context, "Password saved", Toast.LENGTH_SHORT).show()
                        },
                        onNever = { passwordStore.addNever(pending.host); pendingLogin = null },
                        onDismiss = { pendingLogin = null }
                    )
                }

                pendingDownload?.let { d ->
                    AlertDialog(
                        onDismissRequest = { pendingDownload = null },
                        containerColor = Surface,
                        title = { Text("Download file?", color = TextMain) },
                        text = {
                            Column {
                                Text(d.filename, color = TextMain, fontSize = 15.sp)
                                Text(
                                    runCatching { Uri.parse(d.url).host }.getOrNull().orEmpty().removePrefix("www."),
                                    color = TextMuted, fontSize = 13.sp
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { pendingDownload = null; enqueueDownload(d) }) { Text("Download", color = Accent) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDownload = null }) { Text("Cancel", color = TextMain) }
                        }
                    )
                }

                // "Downloading file..." card under the address bar, like Brave.
                androidx.compose.animation.AnimatedVisibility(
                    visible = downloadNoticeVisible && !showTabs &&
                        address != "swift://downloads" && address != "swift://settings",
                    enter = fadeIn(tween(120)) + slideInVertically(initialOffsetY = { -it }),
                    exit = fadeOut(tween(120)) + slideOutVertically(targetOffsetY = { -it }),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(18.dp)).background(Surface)
                            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Download, null, tint = TextMuted, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            val activeDl = DownloadEngine.items.count { it.state == DlState.RUNNING || it.state == DlState.QUEUED }
                            Text(
                                if (activeDl > 1) "Downloading $activeDl files\u2026" else "Downloading file\u2026",
                                color = TextMain, fontSize = 17.sp
                            )
                            Text(
                                if (appSettings.downloadNotifications) "See notification for download status"
                                else "See Downloads for download status",
                                color = TextMuted, fontSize = 13.sp
                            )
                        }
                        TextButton(onClick = { downloadNoticeVisible = false; address = "swift://downloads" }) {
                            Text("Details", color = Color(0xFFA9B4FF), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Brave-style translate card: "Translate page?" first, then "Page translated / Undo".
                // Goes away by itself, or swipe it up.
                val banner = translateDone ?: translateOffer
                val bannerDone = translateDone != null
                // Set when the card was swiped away: it is already off screen, so it must not
                // play the normal slide-out (that was the jump you saw).
                var bannerSwiped by remember { mutableStateOf(false) }
                LaunchedEffect(banner) {
                    if (banner != null) {
                        bannerSwiped = false
                        delay(if (bannerDone) 8000 else 9000)
                        if (translateDone == banner) translateDone = null
                        if (translateOffer == banner) translateOffer = null
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = banner != null && banner.first == activeId && !showTabs && !showSuggestions &&
                        address != "swift://bookmarks" && address != "swift://history" &&
                        address != "swift://downloads" && address != "swift://settings",
                    enter = fadeIn(tween(150)) + slideInVertically(initialOffsetY = { -it }),
                    exit = if (bannerSwiped) androidx.compose.animation.ExitTransition.None
                    else fadeOut(tween(120)) + slideOutVertically(targetOffsetY = { -it }),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    val code = banner?.second ?: ""
                    val fromName = java.util.Locale(code).getDisplayLanguage()
                    val from = fromName.ifBlank { "Detected language" }
                    val to = TranslateEngine.displayName(appSettings.translateTarget)
                    // The swipe offset is a plain state, so the card follows the finger exactly
                    // (no coroutine per drag step). Letting go animates it back or off the top.
                    var swipeY by remember(banner) { mutableFloatStateOf(0f) }
                    val bannerScope = rememberCoroutineScope()
                    var settleJob by remember(banner) { mutableStateOf<Job?>(null) }
                    fun dismissBanner() {
                        // Swiping the card away also silences the "translated"
                        // confirmation that would otherwise pop straight back.
                        translateDismissedUrl = pageUrl
                        translateOffer = null
                        translateDone = null
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                            .graphicsLayer {
                                translationY = swipeY
                                alpha = (1f + swipeY / 260f).coerceIn(0f, 1f)
                            }
                            .pointerInput(banner) {
                                detectDragGestures(
                                    onDragStart = { settleJob?.cancel() },
                                    onDragEnd = {
                                        settleJob = bannerScope.launch {
                                            if (swipeY < -80f) {
                                                animate(swipeY, -480f, animationSpec = tween(130)) { v, _ -> swipeY = v }
                                                bannerSwiped = true
                                                dismissBanner()
                                            } else {
                                                animate(
                                                    swipeY, 0f,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessMedium
                                                    )
                                                ) { v, _ -> swipeY = v }
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        settleJob = bannerScope.launch {
                                            animate(swipeY, 0f, animationSpec = tween(120)) { v, _ -> swipeY = v }
                                        }
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    swipeY = (swipeY + dragAmount.y).coerceAtMost(0f)
                                }
                            }
                            .clip(RoundedCornerShape(20.dp)).background(Surface)
                            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (bannerDone) "Page translated" else "Translate page?",
                                color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium
                            )
                            Text("$from to $to", color = TextMuted, fontSize = 12.sp)
                        }
                        Box {
                            IconButton(onClick = { translateMenu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Settings, "Translate options", tint = TextMuted)
                            }
                            DropdownMenu(expanded = translateMenu, onDismissRequest = { translateMenu = false }) {
                                BrowserMenuItem("Never translate this site", Icons.Default.Block) {
                                    translateMenu = false
                                    translateDismissedUrl = pageUrl
                                    translateOffer = null
                                    translateDone = null
                                    val sp = context.getSharedPreferences("swift_translate", Context.MODE_PRIVATE)
                                    val set = (sp.getStringSet("never", emptySet()) ?: emptySet()).toMutableSet()
                                    set.add(hostOf(pageUrl))
                                    sp.edit().putStringSet("never", set).apply()
                                }
                                BrowserMenuItem("Dismiss", Icons.Default.Close) {
                                    translateMenu = false
                                    dismissBanner()
                                }
                            }
                        }
                        Box(Modifier.width(1.dp).height(28.dp).background(Surface3))
                        TextButton(onClick = { if (bannerDone) undoTranslate() else translatePageInPlace() }) {
                            Text(
                                if (bannerDone) "Undo" else "Translate",
                                color = Accent, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Web suggestions for what you type (from the engine chosen in Settings).
                LaunchedEffect(address, showSuggestions) {
                    val q = address.trim()
                    if (showSuggestions && q.isNotEmpty() && !q.startsWith("http") && !q.startsWith("swift://")) {
                        delay(120) // keep the old list on screen while the next one loads (no flicker)
                        webSuggestions = withContext(Dispatchers.IO) { fetchSuggestions(q, appSettings.searchEngine) }
                    } else {
                        webSuggestions = emptyList()
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showSuggestions && address.isNotBlank() && !showTabs,
                    enter = fadeIn(tween(100)),
                    exit = fadeOut(tween(80)),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    val term = address.trim()
                    val looksLikeUrl = !term.contains(' ') &&
                        (term.contains('.') || term.startsWith("localhost") || term.startsWith("http"))
                    val isPrivateNow = tabs.firstOrNull { it.id == activeId }?.isPrivate == true

                    // Sites you've been to / bookmarked: best matches first, at most 2 (so the keyboard never hides them).
                    val siteHits = remember(term, showSuggestions) {
                        val bm = bookmarksNow().map { Triple(it.first, it.second, true) }
                        val hist = store.history().map { Triple(it.first, it.second, false) }
                        (bm + hist).distinctBy { it.second }
                            .mapNotNull { h ->
                                val host = hostOf(h.second).removePrefix("www.")
                                val score = when {
                                    host.startsWith(term, true) -> 0
                                    h.first.startsWith(term, true) -> 1
                                    host.contains(term, true) -> 2
                                    h.first.contains(term, true) -> 3
                                    h.second.contains(term, true) -> 4
                                    else -> return@mapNotNull null
                                }
                                score to h
                            }
                            .sortedBy { it.first }.map { it.second }.take(2)
                    }
                    // Searches you typed before that match (not in private tabs).
                    val pastQueries = remember(term, showSuggestions) {
                        if (isPrivateNow) emptyList()
                        else store.queries().filter { it.startsWith(term, true) && !it.equals(term, true) }.take(2)
                    }
                    // 3 keyword suggestions: your past searches first, then the engine's.
                    val keywordRows = (pastQueries.map { it to true } +
                        webSuggestions.filter { w -> pastQueries.none { it.equals(w, true) } }.map { it to false })
                        .take(3)

                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(18.dp)).background(Surface).padding(vertical = 6.dp)
                    ) {
                        SuggestionRow(
                            icon = if (looksLikeUrl) Icons.Default.Public else Icons.Default.Search,
                            title = androidx.compose.ui.text.AnnotatedString(term)
                        ) { load(term) }
                        keywordRows.forEach { (sug, past) ->
                            SuggestionRow(
                                icon = if (past) Icons.Default.History else Icons.Default.Search,
                                title = suggestionText(sug, term)
                            ) {
                                if (!isPrivateNow) store.addQuery(sug)
                                load(appSettings.searchUrl(sug))
                            }
                        }
                        if (siteHits.isNotEmpty()) {
                            HorizontalDivider(color = Surface3.copy(alpha = .6f), modifier = Modifier.padding(vertical = 4.dp))
                        }
                        siteHits.forEach { (title, url, isBookmark) ->
                            SuggestionRow(
                                icon = if (isBookmark) Icons.Default.Star else Icons.Default.Language,
                                title = androidx.compose.ui.text.AnnotatedString(title.ifBlank { url }),
                                subtitle = prettyAddress(url)
                            ) { load(url) }
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
                        tabs = tabs.filter { !it.isPrivate },
                        privateTabs = tabs.filter { it.isPrivate },
                        section = tabSection,
                        onSectionChange = { tabSection = it },
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
                        onReorder = { src, tgt, after -> moveEntry(src, tgt, after) },
                        onSelect = { selectTab(it) },
                        onClose = { closeTab(it) },
                        onNew = { if (tabSection == 1) newGroupWithTab() else newTab(private = tabSection == 2) },
                        onGroup = { source, target -> handleGroupDrop(source, target) },
                        onDropOutside = { handleDropOutside(it) },
                        onGroupAll = { groupAllTabs() },
                        onCloseAll = { confirmCloseAll = true },
                        selecting = selecting,
                        selectedIds = selectedTabs,
                        onToggleSelect = { ids ->
                            if (ids.all { it in selectedTabs }) selectedTabs.removeAll(ids)
                            else ids.forEach { if (it !in selectedTabs) selectedTabs.add(it) }
                        }
                    )
                }

                if (showTabs && selecting) {
                    BackHandler { selecting = false; selectedTabs.clear() }
                    Row(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(72.dp)
                            .background(Bg).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selecting = false; selectedTabs.clear() }) {
                            Icon(Icons.Default.Close, "Cancel", tint = TextMain)
                        }
                        Text(
                            if (selectedTabs.isEmpty()) "Select tabs" else "${selectedTabs.size} selected",
                            color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                        TextButton(
                            enabled = selectedTabs.size >= 2,
                            onClick = {
                                val gid = groupCounter++
                                selectedTabs.forEach { tabGroups[it] = gid }
                                groupUpdated[gid] = System.currentTimeMillis()
                                pendingEditGroup = gid
                                selecting = false
                                selectedTabs.clear()
                            }
                        ) { Text("Group", color = if (selectedTabs.size >= 2) Accent else TextMuted, fontWeight = FontWeight.Medium) }
                        TextButton(
                            enabled = selectedTabs.isNotEmpty(),
                            onClick = {
                                selectedTabs.toList().forEach { id -> tabs.firstOrNull { it.id == id }?.let { closeTab(it) } }
                                selecting = false
                                selectedTabs.clear()
                            }
                        ) { Text("Close", color = if (selectedTabs.isNotEmpty()) Accent else TextMuted, fontWeight = FontWeight.Medium) }
                    }
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
                        onEnabledChange = { blockingEnabled = it; appSettings.putBlockTrackers(it) },
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
                // Plain boxes (not IconButton) so sizes are exact: IconButton
                // inflates itself to a 48dp touch target and distorts the bar.
                Row(
                    Modifier.fillMaxWidth().height(52.dp).background(Bg)
                        .padding(start = 8.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable {
                            captureThumb(activeId)
                            showGroupSheet = true
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, "Open group", tint = TextMain, modifier = Modifier.size(26.dp))
                    }
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        members.forEach { m ->
                            val isActive = m.id == activeId
                            Box(Modifier.size(44.dp)) {
                                Box(
                                    Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape)
                                        .background(Surface2)
                                        .then(if (isActive) Modifier.border(2.dp, gColor, CircleShape) else Modifier)
                                        .clickable { if (!isActive) selectTab(m) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (m.url == "swift://home" || hostOf(m.url).isEmpty()) {
                                        SiteBadge(m, 22.dp)
                                    } else {
                                        // White disc behind the icon (like Brave) so dark
                                        // logos stay visible on the dark theme.
                                        Box(
                                            Modifier.size(26.dp).clip(CircleShape).background(Color.White),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            SiteBadge(m, 17.dp)
                                        }
                                    }
                                }
                                if (isActive) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).size(17.dp).clip(CircleShape)
                                            .background(gColor)
                                            .clickable { closeTab(m, false) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Close, "Close tab", tint = GroupInk, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                    Box(
                        Modifier.size(34.dp).clip(CircleShape)
                            .border(1.5.dp, TextMuted, CircleShape)
                            .clickable { newTabInGroup(activeGid) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, "New tab in group", tint = TextMain, modifier = Modifier.size(24.dp))
                    }
                }
            }

            // Bottom bar — Brave-style. In the tab overview it becomes the
            // overview bar: "+" centered, settings at the right.
            if (address == "swift://settings" || address == "swift://downloads") {
                // Settings and Downloads are full screen pages: no toolbar, like Brave.
            } else if (showTabs) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Surface)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    // Brave-style "+" pill: light background, dark icon.
                    Row(
                        Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFFE8E8EC))
                            .clickable {
                                if (showTabs && tabSection == 1) newGroupWithTab() else newTab(private = showTabs && tabSection == 2)
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, "New tab", tint = Color(0xFF16181C), modifier = Modifier.size(20.dp))
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
                    .padding(horizontal = 12.dp, vertical = 2.dp),
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
                val canBookmarkPage = !isHome && pageUrl.startsWith("http")
                ToolbarButton(
                    if (canBookmarkPage && isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    "Bookmark this page",
                    selected = canBookmarkPage && isBookmarked
                ) {
                    if (canBookmarkPage) {
                        toggleBookmark()
                    } else {
                        // Not on a web page: nothing to save, so open the list instead.
                        address = "swift://bookmarks"
                        showTabs = false
                        showSuggestions = false
                    }
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
                val activeIsPrivate = tabs.firstOrNull { it.id == activeId }?.isPrivate == true
                TabCountButton(tabs.count { it.isPrivate == activeIsPrivate }, showTabs) {
                    if (!showTabs) {
                        captureThumb(activeId)
                        tabSection = if (activeIsPrivate) 2 else 0
                    }
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
                onPickColor = { colorPickKey = sheetGid },
                onUngroup = { showGroupSheet = false; handleDropOutside(sheetGid) },
                onCloseGroup = {
                    showGroupSheet = false
                    members.toList().forEach { closeTab(it, false) }
                },
                onSelect = { showGroupSheet = false; selectTab(it) },
                onCloseTab = { closeTab(it, false) },
                onRemoveFromGroup = { handleDropOutside(it.id) },
                onReorder = { src, tgt, after -> moveEntry(src, tgt, after) }
            )
        }
        // Tabs overview menu (like Brave): only tab actions.
        if (showMenu && showTabs) {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .background(Color(0x66000000))
                    .pointerInput(Unit) { detectTapGestures(onTap = { showMenu = false }) }
            ) {
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 10.dp, bottom = 12.dp)
                        .widthIn(max = 340.dp)
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF2F3137))
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .padding(vertical = 8.dp)
                ) {
                    MenuRow(Icons.Default.Add, "New tab") { showMenu = false; newTab() }
                    MenuRow(Icons.Default.VisibilityOff, "New Private tab") { showMenu = false; startPrivateTab() }
                    MenuRow(Icons.Default.GridView, "New tab group") {
                        showMenu = false
                        val gid = groupCounter++
                        val id = idCounter.get()
                        newTab()
                        tabGroups[id] = gid
                        groupUpdated[gid] = System.currentTimeMillis()
                        pendingEditGroup = gid
                        showTabs = true
                    }
                    MenuRow(Icons.Default.Close, "Close all tabs") { showMenu = false; confirmCloseAll = true }
                    MenuRow(Icons.Default.CheckCircle, "Select tabs") {
                        showMenu = false
                        selectedTabs.clear()
                        selecting = true
                    }
                    MenuRow(Icons.Default.DeleteSweep, "Delete browsing data") { showMenu = false; showClearData = true }
                    MenuRow(Icons.Default.Settings, "Settings") {
                        showMenu = false
                        showTabs = false
                        showShield = false
                        settingsEntry = "main"
                        address = "swift://settings"
                        showSuggestions = false
                    }
                }
            }
        }
        if (showClearData) {
            ClearDataDialog(
                hasHistory = false,
                onDismiss = { showClearData = false },
                onConfirm = { cookies, cache, _ ->
                    if (cookies) {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        android.webkit.WebStorage.getInstance().deleteAllData()
                    }
                    if (cache) webViews.values.forEach { it.clearCache(true) }
                    Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                    showClearData = false
                }
            )
        }
        if (showMenu && !showTabs) {
            val onPage = !showTabs && !isHome && pageUrl.startsWith("http")
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .background(Color(0x66000000))
                    .pointerInput(Unit) { detectTapGestures(onTap = { showMenu = false }) }
            ) {
                val maxMenuH = maxHeight * 0.86f
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 10.dp, bottom = 58.dp)
                        .widthIn(max = 320.dp)
                        .fillMaxWidth(0.84f)
                        .heightIn(max = maxMenuH)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF2F3137))
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                ) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp)
                    ) {
                        MenuRow(Icons.Default.Add, "New tab") { showMenu = false; newTab() }
                        MenuRow(Icons.Default.VisibilityOff, "New Private tab") { showMenu = false; startPrivateTab() }
                        MenuRow(Icons.Default.GridView, "Add tab to group") { showMenu = false; showAddToGroup = true }
                        if (showTabs) {
                            MenuRow(Icons.Default.CreateNewFolder, "Group all tabs") { showMenu = false; groupAllTabs() }
                            MenuRow(Icons.Default.DeleteSweep, "Close all tabs") { showMenu = false; confirmCloseAll = true }
                        }
                        MenuDivider()
                        MenuRow(Icons.Default.History, "History") {
                            showMenu = false
                            showTabs = false
                            address = "swift://history"
                            showSuggestions = false
                        }
                        MenuRow(Icons.Default.Download, "Downloads") {
                            showMenu = false
                            showTabs = false
                            showShield = false
                            address = "swift://downloads"
                            showSuggestions = false
                        }
                        MenuRow(Icons.Default.Bookmarks, "Bookmarks") {
                            showMenu = false
                            showTabs = false
                            showShield = false
                            address = "swift://bookmarks"
                            showSuggestions = false
                        }
                        MenuRow(Icons.Default.Tab, "Recent tabs") { showMenu = false; showRecent = true }
                        MenuDivider()
                        if (onPage) {
                            MenuRow(Icons.Default.Search, "Find in page") {
                                showMenu = false
                                findText = ""
                                findMatches = 0
                                findIndex = 0
                                findOpen = true
                            }
                            MenuRow(Icons.Default.Translate, "Translate\u2026") {
                                showMenu = false
                                translatePageInPlace()
                            }
                            MenuRow(Icons.Default.AddToHomeScreen, "Install and create shortcut") {
                                showMenu = false
                                installShortcut()
                            }
                            MenuRow(
                                Icons.Default.DesktopWindows, "Desktop site",
                                trailing = {
                                    Checkbox(checked = desktopSites[activeId] == true, onCheckedChange = null)
                                }
                            ) {
                                showMenu = false
                                toggleDesktopSite()
                            }
                            MenuRow(Icons.Default.DeleteForever, "Shred site data") {
                                showMenu = false
                                shredSiteData()
                            }
                            MenuDivider()
                        }
                        MenuRow(Icons.Default.Settings, "Settings") {
                            showMenu = false
                            showTabs = false
                            showShield = false
                            settingsEntry = "main"
                            address = "swift://settings"
                            showSuggestions = false
                        }
                        MenuRow(Icons.Default.Public, "Set as default browser") {
                            showMenu = false
                            runCatching {
                                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                            }
                        }
                        MenuRow(Icons.Default.PowerSettingsNew, "Exit") {
                            showMenu = false
                            (context as? android.app.Activity)?.finishAndRemoveTask()
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val homeFwd = isHome && homeForward[activeId] != null
                        MenuCircleButton(
                            Icons.Default.ArrowForward, "Forward",
                            enabled = homeFwd || (onPage && activeWebView()?.canGoForward() == true)
                        ) {
                            showMenu = false
                            if (homeFwd) homeForward[activeId]?.let { load(it) }
                            else activeWebView()?.goForward()
                        }
                        MenuCircleButton(Icons.Default.Share, "Share", enabled = onPage) {
                            showMenu = false
                            sharePage(context, pageUrl)
                        }
                        MenuCircleButton(Icons.Default.Download, "Download page", enabled = onPage) {
                            showMenu = false
                            saveCurrentPage()
                        }
                        MenuCircleButton(Icons.Default.Refresh, "Reload", enabled = onPage, dark = true) {
                            showMenu = false
                            activeWebView()?.reload()
                        }
                    }
                }
            }
        }

        if (showAddToGroup) {
            AlertDialog(
                onDismissRequest = { showAddToGroup = false },
                containerColor = Surface,
                title = { Text("Add tab to group", color = TextMain) },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    showAddToGroup = false
                                    val gid = groupCounter++
                                    tabGroups[activeId] = gid
                                    groupUpdated[gid] = System.currentTimeMillis()
                                    editGroupKey = gid
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, null, tint = TextMain)
                            Spacer(Modifier.width(14.dp))
                            Text("New group", color = TextMain, fontSize = 16.sp)
                        }
                        tabGroups.values.distinct().forEach { gid ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        showAddToGroup = false
                                        tabGroups[activeId] = gid
                                        groupUpdated[gid] = System.currentTimeMillis()
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(22.dp).clip(CircleShape).background(groupColorOf(gid)))
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    groupLabel(groupNames, gid, tabs.count { tabGroups[it.id] == gid }),
                                    color = TextMain, fontSize = 16.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showAddToGroup = false }) { Text("Cancel", color = TextMain) } }
            )
        }

        if (showRecent) {
            AlertDialog(
                onDismissRequest = { showRecent = false },
                containerColor = Surface,
                title = { Text("Recent tabs", color = TextMain) },
                text = {
                    if (recentClosed.isEmpty()) {
                        Text("No recently closed tabs", color = TextMuted)
                    } else {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            recentClosed.toList().forEach { (t, u) ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            showRecent = false
                                            openUrlInNewTab(u)
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(t.ifBlank { u }, color = TextMain, fontSize = 15.sp, maxLines = 1)
                                    Text(prettyAddress(u), color = TextMuted, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { recentClosed.clear(); showRecent = false }) { Text("Clear", color = Accent) }
                },
                dismissButton = { TextButton(onClick = { showRecent = false }) { Text("Close", color = TextMain) } }
            )
        }

        colorPickKey?.let { ck -> GroupColorPickerDialog(ck) { colorPickKey = null } }

        if (confirmCloseAll) {
            AlertDialog(
                onDismissRequest = { confirmCloseAll = false },
                containerColor = Surface,
                title = { Text(if (closeAllPrivate) "Close all private tabs?" else "Close all tabs?", color = TextMain) },
                text = {
                    Text(
                        if (closeAllPrivate) "You are about to close ${tabs.count { it.isPrivate }} private tabs. Your normal tabs stay open."
                        else "You are about to close ${tabs.count { !it.isPrivate }} open tabs. Your private tabs stay open. This can't be undone.",
                        color = TextMuted
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmCloseAll = false
                        closeAllTabs()
                    }) { Text("OK", color = Accent) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmCloseAll = false }) { Text("Cancel", color = TextMain) }
                }
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
        color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
    )
}

@Composable
private fun HomeStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Medium)
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
                        fontWeight = FontWeight.Medium, letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Browse freely.",
                        color = TextMain, fontSize = 29.sp, fontWeight = FontWeight.Medium
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
                                        color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Medium
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
                Text("Privacy dashboard", color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Medium)
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
        Text(value, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(label, color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Medium)
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
                fontWeight = FontWeight.Medium, maxLines = 1,
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
    onPickColor: () -> Unit = {},
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
            Box(Modifier.size(20.dp).clip(CircleShape).background(color).clickable { onPickColor() })
            Spacer(Modifier.width(9.dp))
            Text(
                name,
                color = TextMain, fontSize = 17.sp,
                fontWeight = FontWeight.Medium, maxLines = 1,
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
                                    Text("+${count - 3}", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Medium)
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
    privateTabs: List<BrowserTab>,
    section: Int,
    onSectionChange: (Int) -> Unit,
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
    onSearchWeb: (String) -> Unit,
    onReorder: (Long, Long, Boolean) -> Unit,
    selecting: Boolean = false,
    selectedIds: List<Long> = emptyList(),
    onToggleSelect: (List<Long>) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var openGroupKey by remember { mutableStateOf(openGroupRequest) }
    var renameKey by remember { mutableStateOf<Long?>(null) }
    var colorKey by remember { mutableStateOf<Long?>(null) }
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
    // Private section disappears with its last private tab.
    LaunchedEffect(privateTabs.isEmpty(), section) { if (section == 2 && privateTabs.isEmpty()) onSectionChange(0) }
    var dragKey by remember { mutableStateOf<Long?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dragDistance by remember { mutableStateOf(0f) }
    var hoverKey by remember { mutableStateOf<Long?>(null) }
    val cardBounds = remember { mutableStateMapOf<Long, Rect>() }
    var gridTopLeft by remember { mutableStateOf(Offset.Zero) }
    var hoverZone by remember { mutableIntStateOf(0) } // -1 = before target, 0 = merge, 1 = after target
    var dragSizePx by remember { mutableStateOf(Offset.Zero) }
    // Vector from the finger to the held card's centre at pick-up, so the card
    // stays exactly where it was and simply shrinks around the finger (Brave).
    var grabDelta by remember { mutableStateOf(Offset.Zero) }
    var bandKey by remember { mutableStateOf<Long?>(null) }
    var bandSince by remember { mutableLongStateOf(0L) }
    val holdScale by animateFloatAsState(
        targetValue = if (dragKey != null) 0.84f else 1f,
        animationSpec = tween(140),
        label = "hold"
    )
    var gridHeight by remember { mutableIntStateOf(0) }
    var gridWidth by remember { mutableIntStateOf(0) }
    var lastMoveSig by remember { mutableStateOf("") }
    var lastMoveAt by remember { mutableLongStateOf(0L) }
    var moveCooling by remember { mutableStateOf(false) }
    val lastPos = remember { HashMap<Long, Offset>() }
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val slackPx = with(density) { 7.dp.toPx() }

    // Build grid entries: single tabs plus one card per group.
    val entries = run {
        val list = ArrayList<TabEntry>()
        val seen = mutableSetOf<Long>()
        if (section == 2) privateTabs.forEach { list.add(TabEntry(it.id, false, listOf(it))) }
        else tabs.forEach { tab ->
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
            if (key != exclude && rect.inflate(slackPx).contains(pos)) {
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

    // The finger / held card may travel a little past the list on every side, so a
    // tab can be dragged all the way to the left, top, right or bottom edge.
    fun clampToGrid(p: Offset): Offset {
        if (gridWidth <= 0 || gridHeight <= 0) return p
        val m = with(density) { 64.dp.toPx() }
        return Offset(
            p.x.coerceIn(gridTopLeft.x - m, gridTopLeft.x + gridWidth + m),
            p.y.coerceIn(gridTopLeft.y - m, gridTopLeft.y + gridHeight + m)
        )
    }

    fun heldCenter(p: Offset): Offset {
        val hw = dragSizePx.x / 2f
        val hh = dragSizePx.y / 2f
        val overX = hw * 0.7f
        val overY = hh * 0.7f
        fun cl(v: Float, lo: Float, hi: Float) = if (hi < lo) lo else v.coerceIn(lo, hi)
        return Offset(
            cl(p.x, gridTopLeft.x + hw - overX, gridTopLeft.x + gridWidth - hw + overX),
            cl(p.y, gridTopLeft.y + hh - overY, gridTopLeft.y + gridHeight - hh + overY)
        )
    }

    // Where the (shrunk) held card is drawn: shrinks toward the finger, no jump.
    fun heldVisualCenter(): Offset =
        heldCenter(dragPos + grabDelta) - grabDelta * (1f - holdScale)

    // Moves the held card to a new slot right away; the others shift to make room.
    fun liveMove(src: Long, target: Long, after: Boolean) {
        val sig = "$src>$target>$after"
        val now = System.currentTimeMillis()
        if (sig == lastMoveSig || now - lastMoveAt < 220) return
        lastMoveSig = sig
        lastMoveAt = now
        moveCooling = true
        onReorder(src, target, after)
        scope.launch {
            delay(180)
            moveCooling = false
        }
    }

    // Held card dragged past the edge of the list (left, top, right or bottom) takes the
    // first / last slot there. Only uses cards that are on screen, never stale positions.
    fun edgeSlot(src: Long): Pair<Long, Boolean>? {
        val onScreen = gridState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
        val rects = filtered.filter { it.key != src && it.key in onScreen }
            .mapNotNull { e -> cardBounds[e.key]?.let { e.key to it } }
        if (rects.isEmpty()) return null
        val vc = heldVisualCenter()
        val first = filtered.firstOrNull { it.key != src }
        val last = filtered.lastOrNull { it.key != src }
        val firstRect = first?.let { f -> rects.firstOrNull { it.first == f.key }?.second }
        val lastRect = last?.let { l -> rects.firstOrNull { it.first == l.key }?.second }
        if (first != null && firstRect != null && vc.y < firstRect.top) return first.key to false
        if (last != null && lastRect != null && vc.y > lastRect.bottom) return last.key to true
        val row = rects.filter { vc.y >= it.second.top && vc.y <= it.second.bottom }
        if (row.isNotEmpty()) {
            val left = row.minByOrNull { it.second.left }!!
            val right = row.maxByOrNull { it.second.right }!!
            if (vc.x < left.second.left) return left.first to false
            if (vc.x > right.second.right) return right.first to true
        }
        return null
    }

    // Edges of a card: the others shift and the held card takes that slot.
    // Middle of a card: merge into a group.
    fun updateHover(src: Long) {
        if (dragDistance <= 24f) {
            hoverKey = null
            hoverZone = 0
            return
        }
        val p = clampToGrid(dragPos)
        val edge = edgeSlot(src)
        if (edge != null) {
            hoverKey = null
            hoverZone = 0
            bandKey = null
            liveMove(src, edge.first, edge.second)
            return
        }
        val t = targetUnder(p, src)
        val r = t?.let { cardBounds[it] }
        if (t == null || r == null) {
            hoverKey = null
            hoverZone = 0
            // Empty space below the last card: move to the end.
            val last = filtered.lastOrNull()
            val lr = last?.let { cardBounds[it.key] }
            if (last != null && lr != null && last.key != src && p.y > lr.bottom) {
                liveMove(src, last.key, true)
            }
            return
        }
        // Brave-style: judge by how much the held card covers another card.
        // About half way over a card -> group them. Lingering at the edge -> reorder.
        val vc = heldVisualCenter()
        val hw = dragSizePx.x * holdScale / 2f
        val hh = dragSizePx.y * holdScale / 2f
        val held = Rect(vc.x - hw, vc.y - hh, vc.x + hw, vc.y + hh)
        var bestKey: Long? = null
        var bestRatio = 0f
        cardBounds.forEach { (key, rect) ->
            if (key == src || key == NEW_TAB_KEY) return@forEach
            val w = minOf(held.right, rect.right) - maxOf(held.left, rect.left)
            val h = minOf(held.bottom, rect.bottom) - maxOf(held.top, rect.top)
            if (w > 0f && h > 0f) {
                val ratio = (w * h) / (rect.width * rect.height)
                if (ratio > bestRatio) { bestRatio = ratio; bestKey = key }
            }
        }
        val bk = bestKey
        val br = bk?.let { cardBounds[it] }
        if (bk != null && br != null && bestRatio >= 0.40f) {
            hoverKey = bk
            hoverZone = 0
            bandKey = null
            lastMoveSig = ""
            return
        }
        hoverKey = null
        hoverZone = 0
        if (bk != null && br != null && bestRatio >= 0.12f) {
            val now = System.currentTimeMillis()
            if (bandKey != bk) { bandKey = bk; bandSince = now }
            if (now - bandSince >= 300) {
                val dx = vc.x - br.center.x
                val dy = vc.y - br.center.y
                val after = if (kotlin.math.abs(dy) > br.height * 0.25f) dy > 0 else dx > 0
                liveMove(src, bk, after)
            }
        } else {
            bandKey = null
        }
    }

    fun finishDrag(key: Long) {
        val merge = hoverKey
        val mergeRect = merge?.let { cardBounds[it] }
        if (dragDistance > 40f && merge != null && merge != NEW_TAB_KEY && mergeRect != null) {
            // Merge: the held card flies into the target, then groups with it.
            settling = Settle(key, merge, heldVisualCenter(), mergeRect.center)
            hoverKey = null
            hoverZone = 0
            scope.launch {
                settleAnim.snapTo(0f)
                settleAnim.animateTo(1f, tween(240))
                onGroup(key, merge)
                settling = null
                dragKey = null
                dragDistance = 0f
            }
            return
        }
        dragKey = null
        hoverKey = null
        hoverZone = 0
        dragDistance = 0f
        lastMoveSig = ""
    }
    val latestFinish by rememberUpdatedState({ k: Long -> finishDrag(k) })
    val latestHover by rememberUpdatedState({ k: Long -> updateHover(k) })

    // Opening the overview shows the tab you are on, not the top of the list.
    LaunchedEffect(Unit) {
        val idx = filtered.indexOfFirst { e -> e.tabs.any { it.id == activeId } }
        if (idx > 0) gridState.scrollToItem(idx)
    }

    // Dragging near the top/bottom edge scrolls the list.
    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        val edge = with(density) { 96.dp.toPx() }
        while (true) {
            if (settling == null && !moveCooling) {
                val rel = dragPos.y - gridTopLeft.y
                val delta = when {
                    rel < edge -> -((edge - rel).coerceAtMost(edge) / edge) * 34f
                    rel > gridHeight - edge -> ((rel - (gridHeight - edge)).coerceAtMost(edge) / edge) * 34f
                    else -> 0f
                }
                if (delta != 0f) gridState.dispatchRawDelta(delta)
                dragKey?.let { latestHover(it) }
            }
            delay(16)
        }
    }

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
                        .clickable { onSectionChange(0) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size(22.dp).border(2.dp, TextMain, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(tabs.size.toString(), color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Box(
                    Modifier.width(58.dp).height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (section == 1) Surface3 else Color.Transparent)
                        .clickable { onSectionChange(1) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GridView, "Tab groups", tint = TextMain, modifier = Modifier.size(22.dp))
                }
                // Appears when the first private tab is opened, like Brave.
                if (privateTabs.isNotEmpty()) {
                    Box(
                        Modifier.width(58.dp).height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (section == 2) PrivateColor.copy(alpha = 0.55f) else Color.Transparent)
                            .clickable { onSectionChange(2) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.VisibilityOff, "Private tabs", tint = TextMain, modifier = Modifier.size(22.dp))
                    }
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
                .height(46.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Surface2)
                .clickable { searching = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Tab, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Text(if (section == 2) "Search your Private tabs" else "Search your tabs", color = TextMuted, fontSize = 13.sp)
        }

        Box(
            // Clipped to the list, so a dragged tab slides UNDER the search bar, not over it.
            Modifier.weight(1f).fillMaxWidth()
                .clip(androidx.compose.ui.graphics.RectangleShape)
                .onGloballyPositioned {
                    gridTopLeft = it.positionInRoot()
                    gridHeight = it.size.height
                    gridWidth = it.size.width
                }
                .pointerInput(Unit) {
                    // Follows the finger for the whole drag (even if the held card scrolls
                    // out of the list) and finishes the drag when the finger lifts.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val key = dragKey ?: continue
                            val change = event.changes.firstOrNull() ?: continue
                            if (settling != null) continue
                            val newPos = gridTopLeft + change.position
                            dragDistance += (newPos - dragPos).getDistance()
                            dragPos = newPos
                            latestHover(key)
                            if (!change.pressed) latestFinish(key)
                        }
                    }
                }
        ) {
            if (section == 1) GroupListSection(
                entries = filtered,
                names = groupNames,
                updated = groupUpdated,
                onOpen = { openGroupKey = it },
                onRename = { renameKey = it },
                onUngroup = { onDropOutside(it) },
                onCloseGroup = { e -> e.tabs.toList().forEach { onClose(it) } },
                onPickColor = { colorKey = it }
            ) else LazyVerticalGrid(
                state = gridState,
                userScrollEnabled = dragKey == null,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 17.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)
            ) {
                gridItems(filtered, key = { it.key }) { entry ->
                    val activeMember = entry.tabs.firstOrNull { it.id == activeId } ?: entry.tabs.first()
                    val isActiveEntry = activeMember.id == activeId
                    val isHovered = hoverKey == entry.key && dragKey != entry.key

                    // Brave: the card under the dragged tab shrinks a little to
                    // "absorb" it.
                    val absorbing = (isHovered && hoverZone == 0) || settling?.target == entry.key
                    val cardScale by animateFloatAsState(
                        targetValue = if (absorbing) 0.9f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "absorb"
                    )

                    // Items that scroll away must not leave stale drop targets behind.
                    DisposableEffect(entry.key) {
                        onDispose {
                            cardBounds.remove(entry.key)
                            lastPos.remove(entry.key)
                        }
                    }

                    // A long press only STARTS the drag here; the list itself follows the
                    // finger and finishes the drop, so dragging works across scrolling.
                    val dragModifier = Modifier
                        .alpha(if (dragKey == entry.key) 0f else 1f)
                        .pointerInput(entry.key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    if (section == 2) return@detectDragGesturesAfterLongPress
                                    val r = cardBounds[entry.key]
                                    dragKey = entry.key
                                    dragDistance = 0f
                                    dragSizePx = if (r != null) Offset(r.width, r.height) else Offset.Zero
                                    dragPos = if (r != null) Offset(r.left + offset.x, r.top + offset.y) else Offset.Zero
                                    grabDelta = if (r != null) r.center - dragPos else Offset.Zero
                                    bandKey = null
                                    hoverKey = null
                                    hoverZone = 0
                                },
                                onDrag = { _, _ -> },
                                onDragEnd = { },
                                onDragCancel = { }
                            )
                        }

                    // When cards shift to make room, each one slides from its old slot.
                    val moveAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
                    Box(
                        Modifier.fillMaxWidth().height(196.dp)
                            .onGloballyPositioned { c ->
                                val r = c.boundsInRoot()
                                cardBounds[entry.key] = r
                                val old = lastPos[entry.key]
                                lastPos[entry.key] = r.topLeft
                                if (moveCooling && old != null && (old - r.topLeft).getDistance() > 1f) {
                                    scope.launch {
                                        moveAnim.snapTo(old - r.topLeft)
                                        moveAnim.animateTo(Offset.Zero, tween(200))
                                    }
                                }
                            }
                            .graphicsLayer {
                                translationX = moveAnim.value.x
                                translationY = moveAnim.value.y
                                scaleX = cardScale
                                scaleY = cardScale
                            }
                    ) {
                        if (entry.isGroup) {
                            GroupCard(
                                entry = entry,
                                color = groupColorFor(entry.key),
                                name = groupLabel(groupNames, entry.key, entry.tabs.size),
                                onRename = { renameKey = entry.key },
                                onPickColor = { colorKey = entry.key },
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
                                ringColor = if (activeMember.isPrivate) PrivateColor else null,
                                onSelect = { onSelect(activeMember) },
                                onClose = { onClose(activeMember) },
                                onRemoveFromGroup = null,
                                modifier = dragModifier
                            )
                        }
                        if (selecting) {
                            val ids = entry.tabs.map { it.id }
                            val on = ids.all { it in selectedIds }
                            Box(
                                Modifier.matchParentSize()
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(if (on) Accent.copy(alpha = 0.22f) else Color(0x22000000))
                                    .clickable { onToggleSelect(ids) }
                            ) {
                                Icon(
                                    if (on) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                    tint = if (on) Accent else TextMain.copy(alpha = 0.85f),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(28.dp)
                                )
                            }
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
                if (dragged != null && dragSizePx.x > 0f) {
                    val wDp = with(density) { dragSizePx.x.toDp() }
                    val hDp = with(density) { dragSizePx.y.toDp() }
                    val st = settling
                    val p = settleAnim.value
                    val pos = if (st != null) {
                        Offset(
                            st.from.x + (st.to.x - st.from.x) * p,
                            st.from.y + (st.to.y - st.from.y) * p
                        )
                    } else heldVisualCenter()
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (pos.x - gridTopLeft.x - dragSizePx.x / 2f).roundToInt(),
                                    (pos.y - gridTopLeft.y - dragSizePx.y / 2f).roundToInt()
                                )
                            }
                            .size(wDp, hDp)
                            .graphicsLayer {
                                val sc = if (st != null) holdScale * (1f - 0.7f * p) else holdScale
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
            onPickColor = { colorKey = openEntry.key },
            onUngroup = { onDropOutside(openEntry.key) },
            onCloseGroup = { openEntry.tabs.toList().forEach { onClose(it) } },
            onSelect = onSelect,
            onCloseTab = onClose,
            onRemoveFromGroup = { onDropOutside(it.id) },
            onReorder = onReorder
        )
    }

    // ---------- Search screen: matching tabs list + web search ----------
    if (searching) {
        BackHandler { searching = false }
        TabSearchScreen(
            tabs = if (section == 2) privateTabs else tabs,
            groups = groups,
            groupNames = groupNames,
            onBack = { searching = false },
            onSelectTab = { onSelect(it) },
            onOpenGroup = { searching = false; openGroupKey = it },
            onSearchWeb = onSearchWeb
        )
    }

    colorKey?.let { ck -> GroupColorPickerDialog(ck) { colorKey = null } }

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
    onPickColor: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit,
    onSelect: (BrowserTab) -> Unit,
    onCloseTab: (BrowserTab) -> Unit,
    onRemoveFromGroup: (BrowserTab) -> Unit,
    onReorder: (Long, Long, Boolean) -> Unit = { _, _, _ -> }
) {
    var popupMenu by remember { mutableStateOf(false) }
    // Long-press a tab and drag it onto another one to swap places inside the group.
    var dragId by remember { mutableStateOf<Long?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }   // finger position in root coordinates
    var heldTab by remember { mutableStateOf<BrowserTab?>(null) }
    var heldSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var popupOrigin by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }     // finger offset inside the held card
    var lastSwap by remember { mutableLongStateOf(0L) }
    val popupBounds = remember { mutableStateMapOf<Long, Rect>() }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    val liveEntry by rememberUpdatedState(entry)
    val liveReorder by rememberUpdatedState(onReorder)
    val screenDensity = androidx.compose.ui.platform.LocalDensity.current.density

    // While a tab is held: scroll the grid when the finger is near its top/bottom edge.
    // Like Brave, nothing shifts while you are travelling: a tab only makes room once the
    // finger has rested on the slot (or past the first / last row) for a moment, and never
    // while the list is still scrolling. Only tabs that are really on screen count as targets.
    LaunchedEffect(dragId) {
        if (dragId == null) return@LaunchedEffect
        var dwellId: Long? = null
        var dwellAfter = false
        var dwellSince = 0L
        var lastScrollAt = 0L
        while (dragId != null) {
            val me = dragId ?: break
            val b = gridBounds
            val edge = 96f * screenDensity
            val maxStep = 16f * screenDensity
            val y = finger.y
            val dy = when {
                b.height <= 0f -> 0f
                y < b.top + edge -> -((b.top + edge - y).coerceAtMost(edge) / edge) * maxStep
                y > b.bottom - edge -> ((y - (b.bottom - edge)).coerceAtMost(edge) / edge) * maxStep
                else -> 0f
            }
            var scrolled = false
            if (dy != 0f) scrolled = kotlin.math.abs(gridState.scrollBy(dy)) > 0.5f // + = toward the end
            val now = System.currentTimeMillis()
            if (scrolled) lastScrollAt = now

            val tabsNow = liveEntry.tabs
            val visible = HashSet<Any>().apply { gridState.layoutInfo.visibleItemsInfo.forEach { add(it.key) } }
            fun rectOf(id: Long): Rect? = if (id in visible) popupBounds[id] else null
            val from = tabsNow.indexOfFirst { it.id == me }
            val firstT = tabsNow.firstOrNull()
            val lastT = tabsNow.lastOrNull()
            val firstR = firstT?.let { rectOf(it.id) }
            val lastR = lastT?.let { rectOf(it.id) }
            var target: Long? = null
            var after = false
            val hit = tabsNow.firstOrNull { t -> t.id != me && rectOf(t.id)?.contains(finger) == true }
            if (hit != null) {
                target = hit.id
                after = tabsNow.indexOfFirst { it.id == hit.id } > from
            } else if (firstT != null && firstT.id != me && firstR != null && finger.y < firstR.top) {
                target = firstT.id; after = false          // above the first row -> first slot
            } else if (lastT != null && lastT.id != me && lastR != null &&
                (finger.y > lastR.bottom || (finger.y >= lastR.top && finger.x > lastR.right))) {
                target = lastT.id; after = true            // below the last row -> last slot
            }

            val settled = now - lastScrollAt > 160 && now - lastSwap > 300
            if (target == null || !settled) {
                dwellId = null
            } else if (dwellId != target || dwellAfter != after) {
                dwellId = target; dwellAfter = after; dwellSince = now
            } else if (now - dwellSince >= 350) {
                lastSwap = now
                dwellId = null
                liveReorder(me, target, after)
            }
            androidx.compose.runtime.withFrameNanos { }
        }
    }
    Box(
        Modifier.fillMaxSize()
            .background(Color(0xB3000000))
            .onGloballyPositioned { popupOrigin = it.positionInRoot() }
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
                    Box(Modifier.size(22.dp).clip(CircleShape).background(color).clickable { onPickColor() })
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
                    state = gridState,
                    userScrollEnabled = dragId == null,
                    modifier = Modifier.heightIn(max = 520.dp)
                        .onGloballyPositioned { gridBounds = it.boundsInRoot() }
                        // Long-press + drag is handled here, not on a card: a card is thrown away by the
                        // lazy grid once it scrolls far off screen, which used to cancel the drag halfway.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val origin = gridBounds.topLeft
                                val startRoot = origin + down.position
                                val slop = viewConfiguration.touchSlop
                                // Wait for a long press without disturbing taps / scrolling.
                                var pressed = true
                                val lp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: run { pressed = false; return@withTimeoutOrNull }
                                        if (!ch.pressed || (ch.position - down.position).getDistance() > slop) { pressed = false; return@withTimeoutOrNull }
                                    }
                                }
                                if (lp != null || !pressed) return@awaitEachGesture
                                val hit = liveEntry.tabs.firstOrNull { t -> popupBounds[t.id]?.contains(startRoot) == true }
                                    ?: return@awaitEachGesture
                                val r = popupBounds[hit.id] ?: return@awaitEachGesture
                                heldTab = hit
                                heldSize = r.size
                                grab = startRoot - r.topLeft
                                finger = startRoot
                                dragId = hit.id
                                try {
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) { ch.consume(); break }
                                        finger += ch.positionChange()
                                        ch.consume()
                                    }
                                } finally {
                                    dragId = null
                                    heldTab = null
                                }
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    gridItems(entry.tabs, key = { it.id }) { tab ->
                        val held = dragId == tab.id
                        DisposableEffect(tab.id) { onDispose { popupBounds.remove(tab.id) } }
                        Box(
                            Modifier.fillMaxWidth().height(176.dp)
                                .then(if (held) Modifier else Modifier.animateItem())
                                .onGloballyPositioned { popupBounds[tab.id] = it.boundsInRoot() }
                                .zIndex(if (held) 1f else 0f)
                                .alpha(if (held) 0f else 1f)
                        ) {
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
        // The held tab follows the finger no matter how far the list scrolls, but is clipped
        // to the list, so pulling it up slides it UNDER the group header instead of over it.
        heldTab?.let { ht ->
            val dens = androidx.compose.ui.platform.LocalDensity.current
            val gb = gridBounds
            Box(
                Modifier.align(Alignment.TopStart)
                    .offset { androidx.compose.ui.unit.IntOffset(
                        (gb.left - popupOrigin.x).roundToInt(), (gb.top - popupOrigin.y).roundToInt()) }
                    .size(with(dens) { gb.width.toDp() }, with(dens) { gb.height.toDp() })
                    .clipToBounds().zIndex(10f)
            ) {
                Box(
                    Modifier.size(with(dens) { heldSize.width.toDp() }, with(dens) { heldSize.height.toDp() })
                        .graphicsLayer {
                            translationX = finger.x - grab.x - gb.left
                            translationY = finger.y - grab.y - gb.top
                            scaleX = 1.04f; scaleY = 1.04f
                        }
                ) {
                    SingleTabCard(
                        tab = ht, isActive = ht.id == activeId, hovered = false, ringColor = color,
                        onSelect = {}, onClose = {}, onRemoveFromGroup = {}, showActiveLabel = false
                    )
                }
            }
        }
    }
}

// Tap a group's color circle any time to pick another color.
@Composable
private fun GroupColorPickerDialog(key: Long, onDismiss: () -> Unit) {
    val current = GroupColorStore[key] ?: (key % GroupColors.size).toInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Group color", color = TextMain) },
        text = {
            Column {
                GroupColors.indices.chunked(4).forEach { rowIdx ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(bottom = 14.dp)
                    ) {
                        rowIdx.forEach { i ->
                            Box(
                                Modifier.size(46.dp).clip(CircleShape)
                                    .background(GroupColors[i])
                                    .clickable {
                                        GroupColorStore[key] = i
                                        onDismiss()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (current == i) {
                                    Icon(Icons.Default.Check, null, tint = GroupInk, modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMain) } }
    )
}

// Menu building blocks (Brave-style menu).
@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val tint = if (enabled) TextMain else TextMuted.copy(alpha = 0.5f)
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(22.dp))
        Text(label, color = tint, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = Surface3)
}

@Composable
private fun MenuCircleButton(
    icon: ImageVector,
    desc: String,
    enabled: Boolean,
    dark: Boolean = false,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(46.dp).clip(CircleShape)
            .background(if (dark) Color(0xFF15171A) else Color(0xFF3F4249))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, desc,
            tint = when {
                !enabled -> TextMuted.copy(alpha = 0.5f)
                highlight -> Accent
                else -> TextMain
            },
            modifier = Modifier.size(22.dp)
        )
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
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i, i + term.length)) }
            append(text.substring(i + term.length))
        }
    }

private fun suggestionText(s: String, term: String): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        if (s.startsWith(term, ignoreCase = true)) {
            append(s.substring(0, term.length))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(term.length)) }
        } else {
            append(s)
        }
    }

// Search-engine suggestions for the typed text (best effort; empty on any failure).
private fun fetchSuggestions(q: String, engine: String = "brave"): List<String> {
    val enc = Uri.encode(q)
    val primary = when (engine) {
        "google" -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$enc"
        "ddg" -> "https://duckduckgo.com/ac/?type=list&q=$enc"
        "bing" -> "https://api.bing.com/osjson.aspx?query=$enc"
        else -> "https://search.brave.com/api/suggest?q=$enc&rich=false"
    }
    // The chosen engine first; the others only if it returns nothing.
    val urls = listOf(
        primary,
        "https://duckduckgo.com/ac/?type=list&q=$enc",
        "https://suggestqueries.google.com/complete/search?client=firefox&q=$enc"
    ).distinct()
    for (u in urls) {
        val r = runCatching {
            val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "application/json")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val list = org.json.JSONArray(body).optJSONArray(1) ?: return@runCatching emptyList<String>()
            (0 until list.length()).map { list.optString(it) }
                .filter { it.isNotBlank() && !it.equals(q, ignoreCase = true) }
                .take(6)
        }.getOrDefault(emptyList())
        if (r.isNotEmpty()) return r
    }
    return emptyList()
}

@Composable
private fun SuggestionRow(
    icon: ImageVector,
    title: androidx.compose.ui.text.AnnotatedString,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, color = TextMain, fontSize = 15.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

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
            Text(title, color = TextMain, fontSize = 23.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
    Box(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (selected) AccentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription = label,
            tint = if (enabled) (if (selected) Accent else TextMain) else TextMuted.copy(alpha = .35f),
            modifier = Modifier.size(24.dp)
        )
    }
}

// Tabs button: a rounded box with the number of open tabs (like Brave).
@Composable
private fun TabCountButton(count: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (selected) AccentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(22.dp).border(2.dp, if (selected) Accent else TextMain, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (count > 99) ":D" else count.toString(),
                color = if (selected) Accent else TextMain,
                fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
        }
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
                color = Color.White, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Medium
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
    onCloseGroup: (TabEntry) -> Unit,
    onPickColor: (Long) -> Unit
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
                        Box(
                            Modifier.size(14.dp).clip(CircleShape).background(groupColorOf(entry.key))
                                .clickable { onPickColor(entry.key) }
                        )
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
    val thumb = ThumbStore[tab.id] ?: if (tab.url == "swift://home") HomeThumbStore else null
    Box(
        modifier.clip(RoundedCornerShape(if (compact) 10.dp else 12.dp)).background(tint),
        contentAlignment = Alignment.Center
    ) {
        if ((tab.url == "swift://home" || host.isEmpty()) && thumb == null) {
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

/** The tab waiting under the page while you swipe the address bar. */
@Composable
private fun SwipeTabPreview(tab: BrowserTab?) {
    val bmp = tab?.let { if (it.url == "swift://home") HomeThumbStore ?: ThumbStore[it.id] else ThumbStore[it.id] }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(), contentDescription = null,
            contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(Modifier.fillMaxSize().background(Surface), contentAlignment = Alignment.Center) {
            Text(tab?.title ?: "", color = TextMuted, fontSize = 16.sp)
        }
    }
}