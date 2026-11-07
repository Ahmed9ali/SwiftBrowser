package com.swift.browser

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

// Same palette as the browser (file-private there, so repeated here).
private val SBg = Color(0xFF101114)
private val SSurface = Color(0xFF1A1C20)
private val SSurface2 = Color(0xFF25282E)
private val SSurface3 = Color(0xFF30343B)
private val SAccent = Color(0xFFFF6B35)
private val SAccentSoft = Color(0x33FF6B35)
private val SText = Color(0xFFF4F4F5)
private val SMuted = Color(0xFF9A9DA5)

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

    fun setSearchEngine(v: String) { searchEngine = v; sp.edit().putString("search", v).apply() }
    fun setBlockTrackers(v: Boolean) { blockTrackers = v; sp.edit().putBoolean("block", v).apply() }
    fun setBlockThirdPartyCookies(v: Boolean) { blockThirdPartyCookies = v; sp.edit().putBoolean("block3p", v).apply() }
    fun setSafeBrowsing(v: Boolean) { safeBrowsing = v; sp.edit().putBoolean("safe", v).apply() }
    fun setJavascript(v: Boolean) { javascript = v; sp.edit().putBoolean("js", v).apply() }
    fun setLoadImages(v: Boolean) { loadImages = v; sp.edit().putBoolean("images", v).apply() }
    fun setTextZoom(v: Int) { textZoom = v; sp.edit().putInt("zoom", v).apply() }
    fun setTranslateOffer(v: Boolean) { translateOffer = v; sp.edit().putBoolean("tOffer", v).apply() }
    fun setTranslateAuto(v: Boolean) { translateAuto = v; sp.edit().putBoolean("tAuto", v).apply() }
    fun setWifiOnlyModels(v: Boolean) { wifiOnlyModels = v; sp.edit().putBoolean("tWifi", v).apply() }
    fun setTranslateTarget(v: String) { translateTarget = v; sp.edit().putString("tTarget", v).apply() }

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
        if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = safeBrowsing
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !blockThirdPartyCookies)
    }
}

// =====================================================================
//  The Settings page
// =====================================================================
@Composable
fun SettingsScreen(
    settings: AppSettings,
    blockingEnabled: Boolean,
    onBlockingChange: (Boolean) -> Unit,
    privateMode: Boolean,
    onPrivateChange: (Boolean) -> Unit,
    onWebSettingsChanged: () -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: (() -> Unit)?,
    onOpenPrivacyDashboard: () -> Unit,
    onSetDefaultBrowser: () -> Unit,
    onClose: () -> Unit
) {
    var page by remember { mutableStateOf("main") }
    BackHandler(enabled = page != "main") { page = "main" }

    Box(Modifier.fillMaxSize().background(SBg)) {
        when (page) {
            "languages" -> LanguagesPage(settings) { page = "main" }
            "never" -> NeverTranslatePage { page = "main" }
            else -> MainPage(
                settings = settings,
                blockingEnabled = blockingEnabled,
                onBlockingChange = onBlockingChange,
                privateMode = privateMode,
                onPrivateChange = onPrivateChange,
                onWebSettingsChanged = onWebSettingsChanged,
                onClearCache = onClearCache,
                onClearHistory = onClearHistory,
                onOpenPrivacyDashboard = onOpenPrivacyDashboard,
                onSetDefaultBrowser = onSetDefaultBrowser,
                openLanguages = { page = "languages" },
                openNever = { page = "never" },
                onClose = onClose
            )
        }
    }
}

@Composable
private fun MainPage(
    settings: AppSettings,
    blockingEnabled: Boolean,
    onBlockingChange: (Boolean) -> Unit,
    privateMode: Boolean,
    onPrivateChange: (Boolean) -> Unit,
    onWebSettingsChanged: () -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: (() -> Unit)?,
    onOpenPrivacyDashboard: () -> Unit,
    onSetDefaultBrowser: () -> Unit,
    openLanguages: () -> Unit,
    openNever: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(settings.textZoom.toFloat()) }
    val neverCount = remember {
        (context.getSharedPreferences("swift_translate", Context.MODE_PRIVATE)
            .getStringSet("never", emptySet()) ?: emptySet()).size
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Settings", onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 17.dp).padding(bottom = 28.dp)
        ) {
            SectionLabel("SEARCH")
            SCard {
                SRow(
                    Icons.Default.Search, "Search engine",
                    AppSettings.ENGINES.firstOrNull { it.first == settings.searchEngine }?.second ?: "Brave Search",
                    onClick = { dialog = "search" }
                ) { Chevron() }
            }

            SectionLabel("PRIVACY AND SECURITY")
            SCard {
                SRow(Icons.Default.Shield, "Privacy dashboard", "Trackers blocked and protection status", onClick = onOpenPrivacyDashboard) { Chevron() }
                SDivider()
                SRow(Icons.Default.Block, "Block ads and trackers", "Blocks known tracking domains") {
                    SSwitch(blockingEnabled) { onBlockingChange(it) }
                }
                SDivider()
                SRow(Icons.Default.VisibilityOff, "Private browsing", "Does not save history; clears cookies when turned on") {
                    SSwitch(privateMode) { onPrivateChange(it) }
                }
                SDivider()
                SRow(Icons.Default.Lock, "Block third-party cookies", "Stops cross-site tracking cookies") {
                    SSwitch(settings.blockThirdPartyCookies) {
                        settings.setBlockThirdPartyCookies(it); onWebSettingsChanged()
                    }
                }
                SDivider()
                SRow(Icons.Default.Shield, "Safe Browsing", "Warns about dangerous sites") {
                    SSwitch(settings.safeBrowsing) { settings.setSafeBrowsing(it); onWebSettingsChanged() }
                }
                SDivider()
                SRow(Icons.Default.DeleteSweep, "Clear browsing data", "Cookies, site data, cache", onClick = { dialog = "clear" }) { Chevron() }
            }

            SectionLabel("CONTENT")
            SCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TextFields, null, tint = SAccent)
                        Spacer(Modifier.width(12.dp))
                        Text("Text size", color = SText, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("${zoom.roundToInt()}%", color = SMuted, fontSize = 13.sp)
                    }
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        onValueChangeFinished = {
                            settings.setTextZoom(zoom.roundToInt())
                            onWebSettingsChanged()
                        },
                        valueRange = 50f..200f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = SAccent, activeTrackColor = SAccent, inactiveTrackColor = SSurface3
                        )
                    )
                }
                SDivider()
                SRow(Icons.Default.Image, "Load images", "Turn off to save data") {
                    SSwitch(settings.loadImages) { settings.setLoadImages(it); onWebSettingsChanged() }
                }
                SDivider()
                SRow(Icons.Default.Code, "JavaScript", "Turning this off breaks most sites and page translation") {
                    SSwitch(settings.javascript) { settings.setJavascript(it); onWebSettingsChanged() }
                }
            }

            SectionLabel("TRANSLATION (ON YOUR DEVICE)")
            SCard {
                SRow(Icons.Default.Translate, "Offer to translate pages", "Shows a card when a page is in another language") {
                    SSwitch(settings.translateOffer) { settings.setTranslateOffer(it) }
                }
                SDivider()
                SRow(Icons.Default.Language, "Translate automatically", "Only for languages already downloaded") {
                    SSwitch(settings.translateAuto) { settings.setTranslateAuto(it) }
                }
                SDivider()
                SRow(
                    Icons.Default.Translate, "Translate pages to",
                    TranslateEngine.displayName(settings.translateTarget),
                    onClick = { dialog = "target" }
                ) { Chevron() }
                SDivider()
                SRow(Icons.Default.Wifi, "Download language packs on Wi-Fi only", "Each pack is about ${TranslateEngine.MODEL_MB} MB") {
                    SSwitch(settings.wifiOnlyModels) { settings.setWifiOnlyModels(it) }
                }
                SDivider()
                SRow(Icons.Default.Download, "Languages", "Download or delete offline languages", onClick = openLanguages) { Chevron() }
                SDivider()
                SRow(
                    Icons.Default.Block, "Never translate these sites",
                    if (neverCount == 0) "No sites" else "$neverCount sites",
                    onClick = openNever
                ) { Chevron() }
            }

            SectionLabel("BROWSER")
            SCard {
                SRow(Icons.Default.Public, "Set as default browser", null, onClick = onSetDefaultBrowser) { Chevron() }
                SDivider()
                SRow(Icons.Default.Info, "About Swift Browser", "Version $version") {}
            }
        }
    }

    when (dialog) {
        "search" -> ChoiceDialog(
            title = "Search engine",
            options = AppSettings.ENGINES,
            selected = settings.searchEngine,
            onSelect = { settings.setSearchEngine(it); dialog = null },
            onDismiss = { dialog = null }
        )
        "target" -> ChoiceDialog(
            title = "Translate pages to",
            options = remember {
                TranslateEngine.supportedLanguages().map { it to TranslateEngine.displayName(it) }.sortedBy { it.second }
            },
            selected = settings.translateTarget,
            onSelect = { settings.setTranslateTarget(it); dialog = null },
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

// =====================================================================
//  Languages: download and delete offline translation languages
// =====================================================================
@Composable
private fun LanguagesPage(settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloaded by remember { mutableStateOf<Set<String>?>(null) }
    val busy = remember { mutableStateMapOf<String, String>() }
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

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
            toast("Wi-Fi only is on. Connect to Wi-Fi or turn it off in Settings.")
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
        LazyColumn(Modifier.weight(1f).padding(horizontal = 17.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)) {
            item(key = "info") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SSurface).padding(14.dp)
                ) {
                    Text("Translation runs on your device", color = SText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Download a language once and it works offline. Each one is about ${TranslateEngine.MODEL_MB} MB, " +
                            "and English is added automatically as the bridge language.",
                        color = SMuted, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (downloaded == null) "Checking downloaded languages..."
                        else "${have.size} downloaded, about ${have.size * TranslateEngine.MODEL_MB} MB used",
                        color = SAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium
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
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SText, unfocusedTextColor = SText,
                        focusedBorderColor = SAccent, unfocusedBorderColor = SSurface3,
                        cursorColor = SAccent
                    )
                )
            }
            if (query.isBlank()) {
                if (haveList.isNotEmpty()) {
                    item(key = "label-have") { SectionLabel("DOWNLOADED") }
                    items(haveList, key = { "d-" + it.first }) { (code, name) ->
                        LangRow(name, true, busy[code], { download(code) }, { confirmDelete = code })
                    }
                }
                item(key = "label-all") { SectionLabel("AVAILABLE") }
                items(availableList, key = { "a-" + it.first }) { (code, name) ->
                    LangRow(name, false, busy[code], { download(code) }, { confirmDelete = code })
                }
            } else {
                item(key = "label-results") { SectionLabel("RESULTS") }
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
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; delete(lang) }) { Text("Delete", color = SAccent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = SMuted) }
            }
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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = SText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    busy == "download" -> "Downloading..."
                    busy == "delete" -> "Deleting..."
                    isDownloaded -> "Ready offline"
                    else -> "About ${TranslateEngine.MODEL_MB} MB"
                },
                color = if (isDownloaded && busy == null) SAccent else SMuted, fontSize = 11.sp
            )
        }
        when {
            busy != null -> CircularProgressIndicator(Modifier.size(22.dp).padding(2.dp), color = SAccent, strokeWidth = 2.dp)
            isDownloaded -> IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = SMuted) }
            else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Download", tint = SAccent) }
        }
    }
}

// =====================================================================
//  Sites the user chose never to translate
// =====================================================================
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
                Text("No sites yet", color = SMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.padding(horizontal = 17.dp)) {
                items(sites.toList(), key = { it }) { host ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, color = SText, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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

// =====================================================================
//  Small building blocks
// =====================================================================
@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = SText) }
        Text(title, color = SText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = SMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp)
    )
}

@Composable
private fun SCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SSurface)) { content() }
}

@Composable
private fun SDivider() {
    HorizontalDivider(color = SSurface3.copy(alpha = .6f), modifier = Modifier.padding(start = 50.dp))
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
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = SAccent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = SMuted, fontSize = 11.sp)
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
                        Text(label, color = SText, fontSize = 14.sp)
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
            TextButton(
                enabled = cookies || cache || history,
                onClick = { onConfirm(cookies, cache, history) }
            ) { Text("Clear", color = SAccent) }
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
        Text(label, color = SText, fontSize = 14.sp)
    }
}
