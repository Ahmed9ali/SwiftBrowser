package com.swift.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

// Same palette as the browser (file-private there, so repeated here).
private val SBg = Color(0xFF101114)
private val SRowBg = Color(0xFF23262B)
private val SSurface = Color(0xFF1A1C20)
private val SSurface3 = Color(0xFF30343B)
private val SAccent = Color(0xFFFF6B35)
private val SText = Color(0xFFF4F4F5)
private val SMuted = Color(0xFF9A9DA5)
private val SIcon = Color(0xFFD7D9DE)

// =====================================================================
//  Saved settings. Every value is persisted and survives restarts.
// =====================================================================
class AppSettings(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("swift_settings", Context.MODE_PRIVATE)

    companion object {
        val ENGINES = listOf(
            "brave" to "Brave Search",
            "ddg" to "DuckDuckGo",
            "google" to "Google",
            "bing" to "Bing"
        )
    }

    var searchEngine by mutableStateOf(sp.getString("search", "brave") ?: "brave")
        private set
    var blockTrackers by mutableStateOf(sp.getBoolean("block", true))
        private set
    var blockThirdPartyCookies by mutableStateOf(sp.getBoolean("block3p", false))
        private set
    var safeBrowsing by mutableStateOf(sp.getBoolean("safe", true))
        private set
    var javascript by mutableStateOf(sp.getBoolean("js", true))
        private set
    var loadImages by mutableStateOf(sp.getBoolean("images", true))
        private set
    var blockAutoplay by mutableStateOf(sp.getBoolean("noAutoplay", true))
        private set
    var textZoom by mutableIntStateOf(sp.getInt("zoom", 100))
        private set
    var translateOffer by mutableStateOf(sp.getBoolean("tOffer", true))
        private set
    var translateAuto by mutableStateOf(sp.getBoolean("tAuto", false))
        private set
    var wifiOnlyModels by mutableStateOf(sp.getBoolean("tWifi", false))
        private set
    var translateTarget by mutableStateOf(
        sp.getString("tTarget", null) ?: (TranslateEngine.normalize(Locale.getDefault().language) ?: "en")
    )
        private set

    fun putSearchEngine(v: String) { searchEngine = v; sp.edit().putString("search", v).apply() }
    fun putBlockTrackers(v: Boolean) { blockTrackers = v; sp.edit().putBoolean("block", v).apply() }
    fun putBlockThirdPartyCookies(v: Boolean) { blockThirdPartyCookies = v; sp.edit().putBoolean("block3p", v).apply() }
    fun putSafeBrowsing(v: Boolean) { safeBrowsing = v; sp.edit().putBoolean("safe", v).apply() }
    fun putJavascript(v: Boolean) { javascript = v; sp.edit().putBoolean("js", v).apply() }
    fun putLoadImages(v: Boolean) { loadImages = v; sp.edit().putBoolean("images", v).apply() }
    fun putBlockAutoplay(v: Boolean) { blockAutoplay = v; sp.edit().putBoolean("noAutoplay", v).apply() }
    fun putTextZoom(v: Int) { textZoom = v; sp.edit().putInt("zoom", v).apply() }
    fun putTranslateOffer(v: Boolean) { translateOffer = v; sp.edit().putBoolean("tOffer", v).apply() }
    fun putTranslateAuto(v: Boolean) { translateAuto = v; sp.edit().putBoolean("tAuto", v).apply() }
    fun putWifiOnlyModels(v: Boolean) { wifiOnlyModels = v; sp.edit().putBoolean("tWifi", v).apply() }
    fun putTranslateTarget(v: String) { translateTarget = v; sp.edit().putString("tTarget", v).apply() }

    /** Search address for the chosen engine. */
    fun searchUrl(query: String): String {
        val q = android.net.Uri.encode(query)
        return when (searchEngine) {
            "ddg" -> "https://duckduckgo.com/?q=$q"
            "google" -> "https://www.google.com/search?q=$q"
            "bing" -> "https://www.bing.com/search?q=$q"
            else -> "https://search.brave.com/search?q=$q"
        }
    }

    /** Applies the web-related settings to one tab's WebView. */
    @SuppressLint("SetJavaScriptEnabled")
    fun applyTo(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = javascript
        s.loadsImagesAutomatically = loadImages
        s.textZoom = textZoom
        s.mediaPlaybackRequiresUserGesture = blockAutoplay
        if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = safeBrowsing
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !blockThirdPartyCookies)
    }
}

// =====================================================================
//  Settings search index (the "Search settings" bar at the top)
// =====================================================================
private data class SettingHit(
    val title: String,
    val where: String,
    val keywords: String,
    val target: String,
    val icon: ImageVector
)

private fun settingIndex(): List<SettingHit> = listOf(
    SettingHit("Search engine", "Settings", "search engine brave google duckduckgo bing", "dialog:search", Icons.Default.Search),
    SettingHit("Block ads and trackers", "Privacy and security", "ads trackers block shields", "privacy", Icons.Default.Shield),
    SettingHit("Private browsing", "Privacy and security", "private incognito history", "privacy", Icons.Default.VisibilityOff),
    SettingHit("Block third-party cookies", "Privacy and security", "cookies third party tracking", "privacy", Icons.Default.Lock),
    SettingHit("Safe Browsing", "Privacy and security", "safe browsing dangerous phishing malware", "privacy", Icons.Default.Shield),
    SettingHit("Clear browsing data", "Privacy and security", "clear delete cookies cache history data", "privacy", Icons.Default.DeleteSweep),
    SettingHit("JavaScript", "Site settings", "javascript js scripts", "site", Icons.Default.Code),
    SettingHit("Load images", "Site settings", "images pictures data saver", "site", Icons.Default.Image),
    SettingHit("Block autoplay", "Site settings", "autoplay media video audio sound", "site", Icons.Default.PlayArrow),
    SettingHit("Text size", "Accessibility", "text size zoom font accessibility", "access", Icons.Default.TextFields),
    SettingHit("Translate pages", "Languages", "translate translation offer automatic auto", "languages", Icons.Default.Translate),
    SettingHit("Download languages", "Languages", "language download delete offline pack model", "languages", Icons.Default.Download),
    SettingHit("Never translate these sites", "Languages", "never translate sites", "never", Icons.Default.Block),
    SettingHit("Password Manager", "Passwords and autofill", "password manager saved logins save passwords", "passwords", Icons.Default.Key),
    SettingHit("Autofill services", "Passwords and autofill", "autofill service bitwarden google", "action:autofill", Icons.Default.Key),
    SettingHit("Downloads", "Browser", "downloads files", "action:downloads", Icons.Default.Download),
    SettingHit("Set as default browser", "Browser", "default browser links", "action:default", Icons.Default.Public)
)

// =====================================================================
//  The Settings screen: a full page with categories, like Brave
// =====================================================================
@Composable
fun SettingsScreen(
    settings: AppSettings,
    passwords: PasswordStore,
    blockingEnabled: Boolean,
    onBlockingChange: (Boolean) -> Unit,
    privateMode: Boolean,
    onPrivateChange: (Boolean) -> Unit,
    onWebSettingsChanged: () -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: (() -> Unit)?,
    onOpenPrivacyDashboard: () -> Unit,
    onOpenDownloads: () -> Unit,
    onSetDefaultBrowser: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf("main") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val back = { page = if (page == "never") "languages" else "main" }
    BackHandler(enabled = page != "main") { back() }
    BackHandler(enabled = page == "main" && query.isNotEmpty()) { query = "" }

    fun go(target: String) {
        when {
            target.startsWith("dialog:") -> dialog = target.removePrefix("dialog:")
            target == "action:autofill" -> onOpenAutofillSettings()
            target == "action:downloads" -> onOpenDownloads()
            target == "action:default" -> onSetDefaultBrowser()
            else -> { page = target; query = "" }
        }
    }

    Box(Modifier.fillMaxSize().background(SBg)) {
        when (page) {
            "privacy" -> PrivacyPage(
                settings, blockingEnabled, onBlockingChange, privateMode, onPrivateChange,
                onWebSettingsChanged, onOpenPrivacyDashboard, { dialog = "clear" }, back
            )
            "site" -> SitePage(settings, onWebSettingsChanged, back)
            "access" -> AccessibilityPage(settings, onWebSettingsChanged, back)
            "languages" -> LanguagesPage(settings, { page = "never" }, { dialog = "target" }, back)
            "never" -> NeverTranslatePage(back)
            "passwords" -> PasswordsPage(passwords, back)
            else -> MainPage(
                settings = settings,
                passwords = passwords,
                query = query,
                onQuery = { query = it },
                onGo = { go(it) },
                onClose = onClose
            )
        }
    }

    when (dialog) {
        "search" -> ChoiceDialog(
            title = "Search engine",
            options = AppSettings.ENGINES,
            selected = settings.searchEngine,
            onSelect = { settings.putSearchEngine(it); dialog = null },
            onDismiss = { dialog = null }
        )
        "target" -> ChoiceDialog(
            title = "Translate pages to",
            options = remember {
                TranslateEngine.supportedLanguages().map { it to TranslateEngine.displayName(it) }.sortedBy { it.second }
            },
            selected = settings.translateTarget,
            onSelect = { settings.putTranslateTarget(it); dialog = null },
            onDismiss = { dialog = null }
        )
        "clear" -> ClearDataDialog(
            hasHistory = onClearHistory != null,
            onDismiss = { dialog = null },
            onConfirm = { cookies, cache, history ->
                if (cookies) {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                }
                if (cache) onClearCache()
                if (history) onClearHistory?.invoke()
                Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                dialog = null
            }
        )
    }
}

@Composable
private fun MainPage(
    settings: AppSettings,
    passwords: PasswordStore,
    query: String,
    onQuery: (String) -> Unit,
    onGo: (String) -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Back", tint = SText) }
            Spacer(Modifier.width(4.dp))
            Text("Settings", color = SText, fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = SText) }
        }

        // Search settings
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).height(52.dp)
                .clip(RoundedCornerShape(28.dp)).background(SRowBg).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = SIcon)
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search settings", color = SMuted, fontSize = 16.sp)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = SText, fontSize = 16.sp),
                    cursorBrush = SolidColor(SAccent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, "Clear", tint = SMuted)
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp).padding(top = 6.dp, bottom = 28.dp)
        ) {
            val q = query.trim().lowercase()
            if (q.isNotEmpty()) {
                val hits = settingIndex().filter { (it.title + " " + it.where + " " + it.keywords).lowercase().contains(q) }
                if (hits.isEmpty()) {
                    Text("No settings found", color = SMuted, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
                } else {
                    CardGroup {
                        hits.forEach { h ->
                            item { SRow(h.icon, h.title, h.where, onClick = { onGo(h.target) }) { Chevron() } }
                        }
                    }
                }
            } else {
                CardGroup {
                    item {
                        SRow(
                            Icons.Default.Search, "Search engine",
                            AppSettings.ENGINES.firstOrNull { it.first == settings.searchEngine }?.second ?: "Brave Search",
                            onClick = { onGo("dialog:search") }
                        ) { Chevron() }
                    }
                    item { SRow(Icons.Default.Shield, "Privacy and security", null, onClick = { onGo("privacy") }) { Chevron() } }
                    item { SRow(Icons.Default.Public, "Site settings", null, onClick = { onGo("site") }) { Chevron() } }
                    item { SRow(Icons.Default.Accessibility, "Accessibility", null, onClick = { onGo("access") }) { Chevron() } }
                    item {
                        SRow(
                            Icons.Default.Translate, "Languages",
                            "Translate pages to " + TranslateEngine.displayName(settings.translateTarget),
                            onClick = { onGo("languages") }
                        ) { Chevron() }
                    }
                }

                SectionLabel("Passwords and autofill")
                CardGroup {
                    item {
                        SRow(
                            Icons.Default.Key, "Password Manager",
                            if (passwords.logins.isEmpty()) "Nothing saved yet" else "${passwords.logins.size} saved",
                            onClick = { onGo("passwords") }
                        ) { Chevron() }
                    }
                    item {
                        SRow(Icons.Default.Edit, "Autofill services", "Use another password app", onClick = { onGo("action:autofill") }) { Chevron() }
                    }
                }

                SectionLabel("Browser")
                CardGroup {
                    item { SRow(Icons.Default.Download, "Downloads", null, onClick = { onGo("action:downloads") }) { Chevron() } }
                    item { SRow(Icons.Default.Public, "Set as default browser", null, onClick = { onGo("action:default") }) { Chevron() } }
                    item { AboutRow() }
                }
            }
        }
    }
}

@Composable
private fun AboutRow() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }
    SRow(Icons.Default.Info, "About Swift Browser", "Version $version") {}
}

// =====================================================================
//  Category pages
// =====================================================================
@Composable
private fun PrivacyPage(
    settings: AppSettings,
    blockingEnabled: Boolean,
    onBlockingChange: (Boolean) -> Unit,
    privateMode: Boolean,
    onPrivateChange: (Boolean) -> Unit,
    onWebSettingsChanged: () -> Unit,
    onOpenPrivacyDashboard: () -> Unit,
    onClearData: () -> Unit,
    onBack: () -> Unit
) {
    PageScaffold("Privacy and security", onBack) {
        CardGroup {
            item { SRow(Icons.Default.Shield, "Privacy dashboard", "Trackers blocked and protection status", onClick = onOpenPrivacyDashboard) { Chevron() } }
            item { SRow(Icons.Default.Block, "Block ads and trackers", "Blocks known tracking domains") { SSwitch(blockingEnabled) { onBlockingChange(it) } } }
            item { SRow(Icons.Default.VisibilityOff, "Private browsing", "Doesn't save history; clears cookies when turned on") { SSwitch(privateMode) { onPrivateChange(it) } } }
            item {
                SRow(Icons.Default.Lock, "Block third-party cookies", "Stops cross-site tracking cookies") {
                    SSwitch(settings.blockThirdPartyCookies) { settings.putBlockThirdPartyCookies(it); onWebSettingsChanged() }
                }
            }
            item {
                SRow(Icons.Default.Shield, "Safe Browsing", "Warns about dangerous sites") {
                    SSwitch(settings.safeBrowsing) { settings.putSafeBrowsing(it); onWebSettingsChanged() }
                }
            }
            item { SRow(Icons.Default.DeleteSweep, "Clear browsing data", "Cookies, site data, cache", onClick = onClearData) { Chevron() } }
        }
    }
}

@Composable
private fun SitePage(settings: AppSettings, onWebSettingsChanged: () -> Unit, onBack: () -> Unit) {
    PageScaffold("Site settings", onBack) {
        CardGroup {
            item {
                SRow(Icons.Default.Code, "JavaScript", "Turning this off breaks most sites and page translation") {
                    SSwitch(settings.javascript) { settings.putJavascript(it); onWebSettingsChanged() }
                }
            }
            item {
                SRow(Icons.Default.Image, "Load images", "Turn off to save data") {
                    SSwitch(settings.loadImages) { settings.putLoadImages(it); onWebSettingsChanged() }
                }
            }
            item {
                SRow(Icons.Default.PlayArrow, "Block autoplay", "Video and audio start only when you tap play") {
                    SSwitch(settings.blockAutoplay) { settings.putBlockAutoplay(it); onWebSettingsChanged() }
                }
            }
        }
    }
}

@Composable
private fun AccessibilityPage(settings: AppSettings, onWebSettingsChanged: () -> Unit, onBack: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(settings.textZoom.toFloat()) }
    PageScaffold("Accessibility", onBack) {
        CardGroup {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TextFields, null, tint = SIcon)
                        Spacer(Modifier.width(16.dp))
                        Text("Text size", color = SText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("${zoom.roundToInt()}%", color = SMuted, fontSize = 14.sp)
                    }
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        onValueChangeFinished = {
                            settings.putTextZoom(zoom.roundToInt())
                            onWebSettingsChanged()
                        },
                        valueRange = 50f..200f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = SAccent, activeTrackColor = SAccent, inactiveTrackColor = SSurface3
                        )
                    )
                    Text(
                        "This is how page text will look.",
                        color = SText, fontSize = (16f * zoom / 100f).sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
//  Languages: translation options + download / delete offline languages
// ---------------------------------------------------------------------
@Composable
private fun LanguagesPage(
    settings: AppSettings,
    openNever: () -> Unit,
    openTarget: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloaded by remember { mutableStateOf<Set<String>?>(null) }
    val busy = remember { mutableStateMapOf<String, String>() }
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val neverCount = remember {
        (context.getSharedPreferences("swift_translate", Context.MODE_PRIVATE)
            .getStringSet("never", emptySet()) ?: emptySet()).size
    }

    LaunchedEffect(Unit) {
        downloaded = runCatching { TranslateEngine.downloadedLanguages() }.getOrDefault(emptySet())
    }

    // English is the built-in bridge language; it is not listed or deletable.
    val all = remember {
        TranslateEngine.supportedLanguages().filter { it != "en" }
            .map { it to TranslateEngine.displayName(it) }.sortedBy { it.second }
    }
    val have = downloaded ?: emptySet()
    val haveList = all.filter { it.first in have }
    val availableList = all.filter { it.first !in have }
    val matches = all.filter { it.second.contains(query.trim(), ignoreCase = true) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun download(lang: String) {
        if (!TranslateEngine.isOnline(context)) { toast("No internet connection"); return }
        if (settings.wifiOnlyModels && !TranslateEngine.isOnWifi(context)) {
            toast("Wi-Fi only is on. Connect to Wi-Fi or turn it off in Languages settings.")
            return
        }
        scope.launch {
            busy[lang] = "download"
            try {
                TranslateEngine.downloadWithDependencies(lang, settings.translateTarget)
                downloaded = runCatching { TranslateEngine.downloadedLanguages() }.getOrDefault((downloaded ?: emptySet()) + lang)
                toast("${TranslateEngine.displayName(lang)} is ready offline")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Download failed")
            } finally {
                busy.remove(lang)
            }
        }
    }

    fun delete(lang: String) {
        scope.launch {
            busy[lang] = "delete"
            try {
                TranslateEngine.delete(lang)
                downloaded = (downloaded ?: emptySet()) - lang
                toast("${TranslateEngine.displayName(lang)} deleted")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Could not delete: ${e.message ?: "unknown error"}")
            } finally {
                busy.remove(lang)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Languages", onBack)
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 14.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item(key = "options") {
                CardGroup {
                    item {
                        SRow(Icons.Default.Translate, "Offer to translate pages", "Shows a card when a page is in another language") {
                            SSwitch(settings.translateOffer) { settings.putTranslateOffer(it) }
                        }
                    }
                    item {
                        SRow(Icons.Default.Language, "Translate automatically", "Only for languages already downloaded") {
                            SSwitch(settings.translateAuto) { settings.putTranslateAuto(it) }
                        }
                    }
                    item {
                        SRow(
                            Icons.Default.Translate, "Translate pages to",
                            TranslateEngine.displayName(settings.translateTarget), onClick = openTarget
                        ) { Chevron() }
                    }
                    item {
                        SRow(Icons.Default.Wifi, "Download on Wi-Fi only", "Each language is about ${TranslateEngine.MODEL_MB} MB") {
                            SSwitch(settings.wifiOnlyModels) { settings.putWifiOnlyModels(it) }
                        }
                    }
                    item {
                        SRow(
                            Icons.Default.Block, "Never translate these sites",
                            if (neverCount == 0) "No sites" else "$neverCount sites", onClick = openNever
                        ) { Chevron() }
                    }
                }
            }
            item(key = "label-offline") { SectionLabel("Offline languages") }
            item(key = "info") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(SRowBg).padding(16.dp)) {
                    Text("Translation runs on your device", color = SText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Download a language once and it works offline. English is added automatically as the bridge language.",
                        color = SMuted, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (downloaded == null) "Checking downloaded languages..."
                        else "${have.size} downloaded, about ${have.size * TranslateEngine.MODEL_MB} MB used",
                        color = SAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search languages", color = SMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = SMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SText, unfocusedTextColor = SText,
                        focusedBorderColor = SAccent, unfocusedBorderColor = SSurface3,
                        cursorColor = SAccent
                    )
                )
            }
            if (query.isBlank()) {
                if (haveList.isNotEmpty()) {
                    item(key = "label-have") { SectionLabel("Downloaded") }
                    items(haveList, key = { "d-" + it.first }) { (code, name) ->
                        LangRow(name, true, busy[code], { download(code) }, { confirmDelete = code })
                    }
                }
                item(key = "label-all") { SectionLabel("Available") }
                items(availableList, key = { "a-" + it.first }) { (code, name) ->
                    LangRow(name, false, busy[code], { download(code) }, { confirmDelete = code })
                }
            } else {
                item(key = "label-results") { SectionLabel("Results") }
                items(matches, key = { "m-" + it.first }) { (code, name) ->
                    LangRow(name, code in have, busy[code], { download(code) }, { confirmDelete = code })
                }
            }
        }
    }

    confirmDelete?.let { lang ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = SSurface,
            title = { Text("Delete ${TranslateEngine.displayName(lang)}?", color = SText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Pages in this language will need the pack again (about ${TranslateEngine.MODEL_MB} MB) before they can be translated.",
                    color = SMuted, fontSize = 13.sp
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = null; delete(lang) }) { Text("Delete", color = SAccent) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = SMuted) } }
        )
    }
}

@Composable
private fun LangRow(
    name: String,
    isDownloaded: Boolean,
    busy: String?,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = SText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    busy == "download" -> "Downloading..."
                    busy == "delete" -> "Deleting..."
                    isDownloaded -> "Ready offline"
                    else -> "About ${TranslateEngine.MODEL_MB} MB"
                },
                color = if (isDownloaded && busy == null) SAccent else SMuted, fontSize = 12.sp
            )
        }
        when {
            busy != null -> CircularProgressIndicator(Modifier.size(22.dp).padding(2.dp), color = SAccent, strokeWidth = 2.dp)
            isDownloaded -> IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = SMuted) }
            else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Download", tint = SAccent) }
        }
    }
}

@Composable
private fun NeverTranslatePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("swift_translate", Context.MODE_PRIVATE) }
    val sites = remember {
        mutableStateListOf<String>().apply { addAll((sp.getStringSet("never", emptySet()) ?: emptySet()).sorted()) }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Never translate", onBack)
        if (sites.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No sites yet", color = SMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.padding(horizontal = 18.dp)) {
                items(sites.toList(), key = { it }) { host ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, color = SText, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = {
                            sites.remove(host)
                            sp.edit().putStringSet("never", sites.toSet()).apply()
                        }) { Icon(Icons.Default.Close, "Remove", tint = SMuted) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
//  Password Manager
// ---------------------------------------------------------------------
private fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun PasswordsPage(passwords: PasswordStore, onBack: () -> Unit) {
    var viewing by remember { mutableStateOf<SavedLogin?>(null) }
    var editing by remember { mutableStateOf<SavedLogin?>(null) }
    var adding by remember { mutableStateOf(false) }
    val sorted = passwords.logins.sortedWith(compareBy({ it.host }, { it.username.lowercase() }))

    Column(Modifier.fillMaxSize()) {
        PageHeader("Password Manager", onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            item(key = "options") {
                CardGroup {
                    item {
                        SRow(Icons.Default.Key, "Offer to save passwords", "Asks after you sign in to a site") {
                            SSwitch(passwords.offerToSave) { passwords.putOfferToSave(it) }
                        }
                    }
                    item {
                        SRow(Icons.Default.Edit, "Fill in saved passwords", "Only on secure (HTTPS) sites") {
                            SSwitch(passwords.autoFill) { passwords.putAutoFill(it) }
                        }
                    }
                    item {
                        SRow(Icons.Default.Add, "Add password", null, onClick = { adding = true }) { Chevron() }
                    }
                }
            }
            item(key = "label-saved") { SectionLabel("Saved passwords") }
            if (sorted.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Passwords you save are encrypted on this device and never leave it.",
                        color = SMuted, fontSize = 14.sp, modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                item(key = "list") {
                    CardGroup {
                        sorted.forEach { l ->
                            item {
                                SRow(
                                    Icons.Default.Key, l.host,
                                    if (l.username.isBlank()) "No username" else l.username,
                                    onClick = { viewing = l }
                                ) { Chevron() }
                            }
                        }
                    }
                }
            }
            if (passwords.neverHosts.isNotEmpty()) {
                item(key = "label-never") { SectionLabel("Never saved") }
                items(passwords.neverHosts.toList(), key = { "n-" + it }) { host ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, color = SText, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { passwords.removeNever(host) }) { Icon(Icons.Default.Close, "Remove", tint = SMuted) }
                    }
                }
            }
        }
    }

    viewing?.let { login ->
        LoginDetailDialog(
            login = login,
            onDismiss = { viewing = null },
            onEdit = { editing = login; viewing = null },
            onDelete = { passwords.delete(login.id); viewing = null }
        )
    }
    if (adding) {
        LoginEditDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { host, user, pass -> passwords.save(PasswordStore.hostKey("https://$host").ifEmpty { host }, user, pass); adding = false }
        )
    }
    editing?.let { login ->
        LoginEditDialog(
            initial = login,
            onDismiss = { editing = null },
            onSave = { host, user, pass ->
                passwords.edit(login.id, PasswordStore.hostKey("https://$host").ifEmpty { host }, user, pass)
                editing = null
            }
        )
    }
}

@Composable
private fun LoginDetailDialog(login: SavedLogin, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SSurface,
        title = { Text(login.host, color = SText, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Username", color = SMuted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        login.username.ifBlank { "(none)" }, color = SText, fontSize = 15.sp,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (login.username.isNotBlank()) {
                        IconButton(onClick = { copySensitive(context, "Username", login.username) }) {
                            Icon(Icons.Default.ContentCopy, "Copy username", tint = SMuted)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Password", color = SMuted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (show) login.password else "\u2022".repeat(minOf(login.password.length, 12)),
                        color = SText, fontSize = 15.sp, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show or hide", tint = SMuted)
                    }
                    IconButton(onClick = { copySensitive(context, "Password", login.password) }) {
                        Icon(Icons.Default.ContentCopy, "Copy password", tint = SMuted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("Edit", color = SAccent) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = SMuted) }
                TextButton(onClick = onDismiss) { Text("Close", color = SMuted) }
            }
        }
    )
}

@Composable
private fun LoginEditDialog(
    initial: SavedLogin?,
    onDismiss: () -> Unit,
    onSave: (host: String, user: String, pass: String) -> Unit
) {
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pass by remember { mutableStateOf(initial?.password ?: "") }
    var show by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = SText, unfocusedTextColor = SText,
        focusedBorderColor = SAccent, unfocusedBorderColor = SSurface3,
        focusedLabelColor = SAccent, unfocusedLabelColor = SMuted, cursorColor = SAccent
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SSurface,
        title = { Text(if (initial == null) "Add password" else "Edit password", color = SText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("Website (example.com)") }, singleLine = true, colors = fieldColors)
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, colors = fieldColors)
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true, colors = fieldColors,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show or hide", tint = SMuted)
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && pass.isNotEmpty(),
                onClick = { onSave(host.trim(), user.trim(), pass) }
            ) { Text("Save", color = SAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SMuted) } }
    )
}

/** "Save password?" asked right after a sign-in. */
@Composable
fun SavePasswordDialog(
    pending: PendingLogin,
    onSave: () -> Unit,
    onNever: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SSurface,
        title = {
            Text(if (pending.isUpdate) "Update password?" else "Save password?", color = SText, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(pending.host, color = SText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(pending.username.ifBlank { "No username" }, color = SMuted, fontSize = 14.sp)
                Text("\u2022".repeat(minOf(pending.password.length, 12)), color = SMuted, fontSize = 14.sp)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onNever) { Text("Never", color = SMuted) }
                TextButton(onClick = onDismiss) { Text("Not now", color = SMuted) }
                TextButton(onClick = onSave) { Text(if (pending.isUpdate) "Update" else "Save", color = SAccent) }
            }
        }
    )
}

// =====================================================================
//  Building blocks
// =====================================================================
@Composable
private fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp).padding(top = 6.dp, bottom = 28.dp),
            content = content
        )
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = SText) }
        Spacer(Modifier.width(4.dp))
        Text(title, color = SText, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = SMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 12.dp)
    )
}

private class CardScope {
    val rows = mutableListOf<@Composable () -> Unit>()
    fun item(row: @Composable () -> Unit) { rows.add(row) }
}

/** Rows as separate rounded cards with a thin gap, like Brave's settings list. */
@Composable
private fun CardGroup(content: CardScope.() -> Unit) {
    val rows = CardScope().apply(content).rows
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { i, row ->
            val big = 22.dp
            val small = 6.dp
            val first = i == 0
            val last = i == rows.lastIndex
            val shape = RoundedCornerShape(
                topStart = if (first) big else small, topEnd = if (first) big else small,
                bottomStart = if (last) big else small, bottomEnd = if (last) big else small
            )
            Box(Modifier.fillMaxWidth().clip(shape).background(SRowBg)) { row() }
        }
    }
}

@Composable
private fun SRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = SIcon)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = SMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.Default.KeyboardArrowRight, null, tint = SMuted)
}

@Composable
private fun SSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = SAccent, checkedThumbColor = Color.White)
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SSurface,
        title = { Text(title, color = SText, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(options, key = { it.first }) { (code, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(code) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = code == selected,
                            onClick = { onSelect(code) },
                            colors = RadioButtonDefaults.colors(selectedColor = SAccent, unselectedColor = SMuted)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = SText, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SAccent) } }
    )
}

@Composable
private fun ClearDataDialog(
    hasHistory: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (cookies: Boolean, cache: Boolean, history: Boolean) -> Unit
) {
    var cookies by remember { mutableStateOf(true) }
    var cache by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SSurface,
        title = { Text("Clear browsing data", color = SText, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                CheckRow("Cookies and site data", cookies) { cookies = it }
                CheckRow("Cached images and files", cache) { cache = it }
                if (hasHistory) CheckRow("Browsing history", history) { history = it }
            }
        },
        confirmButton = {
            TextButton(enabled = cookies || cache || history, onClick = { onConfirm(cookies, cache, history) }) {
                Text("Clear", color = SAccent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SMuted) } }
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = SAccent, uncheckedColor = SMuted)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = SText, fontSize = 15.sp)
    }
}
