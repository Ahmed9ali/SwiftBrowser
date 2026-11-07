package com.swift.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One saved login. [host] is stored without "www." (see [PasswordStore.hostKey]). */
data class SavedLogin(
    val id: String,
    val host: String,
    val username: String,
    val password: String,
    val updated: Long
)

/** A login the page just submitted, waiting for the user's answer to "Save password?". */
data class PendingLogin(val host: String, val username: String, val password: String, val isUpdate: Boolean)

/**
 * AES-256-GCM with a key that lives in the Android Keystore (it never leaves the
 * secure hardware / system keystore). Only the encrypted blob is written to disk.
 */
private object Vault {
    private const val ALIAS = "swift_password_vault"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }
}

/** Saved logins (encrypted on disk) plus the two password settings. */
class PasswordStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("swift_passwords", Context.MODE_PRIVATE)

    val logins = mutableStateListOf<SavedLogin>()
    val neverHosts = mutableStateListOf<String>()

    var offerToSave by mutableStateOf(prefs.getBoolean("offer", true))
        private set
    var autoFill by mutableStateOf(prefs.getBoolean("autofill", true))
        private set

    companion object {
        /** "https://www.Example.com/login" -> "example.com" */
        fun hostKey(url: String): String =
            (runCatching { Uri.parse(url).host }.getOrNull() ?: "").lowercase().removePrefix("www.")
    }

    init {
        runCatching {
            val blob = prefs.getString("vault", null)
            if (blob != null) {
                val arr = JSONArray(Vault.decrypt(blob))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    logins.add(
                        SavedLogin(
                            o.getString("id"), o.getString("host"), o.optString("user"),
                            o.getString("pass"), o.optLong("t")
                        )
                    )
                }
            }
        }
        neverHosts.addAll((prefs.getStringSet("never", emptySet()) ?: emptySet()).sorted())
    }

    private fun persist() {
        val arr = JSONArray()
        logins.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("host", it.host).put("user", it.username)
                    .put("pass", it.password).put("t", it.updated)
            )
        }
        runCatching { prefs.edit().putString("vault", Vault.encrypt(arr.toString())).apply() }
    }

    fun putOfferToSave(v: Boolean) { offerToSave = v; prefs.edit().putBoolean("offer", v).apply() }
    fun putAutoFill(v: Boolean) { autoFill = v; prefs.edit().putBoolean("autofill", v).apply() }

    fun loginsFor(host: String): List<SavedLogin> = logins.filter { it.host == host }

    fun find(host: String, username: String): SavedLogin? =
        logins.firstOrNull { it.host == host && it.username.equals(username, ignoreCase = true) }

    /** Adds a login, or updates the one with the same site and username. */
    fun save(host: String, username: String, password: String) {
        val now = System.currentTimeMillis()
        val i = logins.indexOfFirst { it.host == host && it.username.equals(username, ignoreCase = true) }
        if (i >= 0) logins[i] = logins[i].copy(password = password, updated = now)
        else logins.add(SavedLogin(UUID.randomUUID().toString(), host, username, password, now))
        persist()
    }

    fun edit(id: String, host: String, username: String, password: String) {
        val i = logins.indexOfFirst { it.id == id }
        if (i >= 0) {
            logins[i] = logins[i].copy(host = host, username = username, password = password, updated = System.currentTimeMillis())
            persist()
        }
    }

    fun delete(id: String) {
        logins.removeAll { it.id == id }
        persist()
    }

    fun isNever(host: String) = host in neverHosts

    fun addNever(host: String) {
        if (host !in neverHosts) neverHosts.add(host)
        prefs.edit().putStringSet("never", neverHosts.toSet()).apply()
    }

    fun removeNever(host: String) {
        neverHosts.remove(host)
        prefs.edit().putStringSet("never", neverHosts.toSet()).apply()
    }
}

/**
 * Receives "the user submitted a login form" from the page. It only ever receives
 * (never returns) data, and the site comes from the real page address, not from
 * anything the page says.
 */
class PasswordBridge(
    private val webView: WebView,
    private val onLogin: (host: String, username: String, password: String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onSubmit(username: String?, password: String?) {
        if (password.isNullOrEmpty() || password.length > 256) return
        val user = (username ?: "").take(256)
        main.post {
            val url = webView.url ?: return@post
            if (!url.startsWith("http")) return@post
            val host = PasswordStore.hostKey(url)
            if (host.isNotEmpty()) onLogin(host, user, password)
        }
    }
}

/** Watches for login submits (form submit, Enter, or tapping a sign-in button). */
const val PASSWORD_CAPTURE_JS = """
(function () {
  if (window.__swiftPw) return;
  window.__swiftPw = true;
  var last = 0;
  var USER_TYPES = ['text', 'email', 'tel', 'number', ''];
  function grab(scope) {
    var root = (scope && scope.querySelectorAll) ? scope : document;
    var pws = [].slice.call(root.querySelectorAll('input[type=password]')).filter(function (e) { return e.value; });
    if (!pws.length) return;
    var pw = pws[0];
    var f = pw.form || document;
    var inputs = [].slice.call(f.querySelectorAll('input'));
    var i = inputs.indexOf(pw), user = '';
    for (var j = i - 1; j >= 0; j--) {
      var t = (inputs[j].type || 'text').toLowerCase();
      if (USER_TYPES.indexOf(t) >= 0 && inputs[j].value) { user = inputs[j].value; break; }
    }
    var now = Date.now();
    if (now - last < 1500) return;
    last = now;
    try { SwiftPasswords.onSubmit(user, pw.value); } catch (e) {}
  }
  document.addEventListener('submit', function (e) { grab(e.target); }, true);
  document.addEventListener('click', function (e) {
    var b = e.target && e.target.closest && e.target.closest('button,input[type=submit],input[type=button],[role=button],a');
    if (!b) return;
    var txt = ((b.innerText || b.value || b.getAttribute('aria-label') || '') + '').toLowerCase();
    if (!(b.type === 'submit' || /log ?in|sign ?in|sign ?up|submit|continue|next|register|create|enter/.test(txt))) return;
    grab(b.form || (b.closest && b.closest('form')) || document);
  }, true);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && e.target && e.target.tagName === 'INPUT') grab(e.target.form || document);
  }, true);
})();
"""

/** Fills the visible password field (and the username field before it) of the page. */
fun passwordFillJs(username: String, password: String): String =
    "(function(u,p){" +
        "function setVal(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(el,v);" +
        "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
        "function vis(el){var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}" +
        "var pws=[].slice.call(document.querySelectorAll('input[type=password]')).filter(vis);" +
        "pws.forEach(function(pw){if(pw.value)return;var f=pw.form||document;" +
        "var inputs=[].slice.call(f.querySelectorAll('input')).filter(vis);var i=inputs.indexOf(pw);var user=null;" +
        "for(var j=i-1;j>=0;j--){var t=(inputs[j].type||'text').toLowerCase();" +
        "if(['text','email','tel','number',''].indexOf(t)>=0){user=inputs[j];break;}}" +
        "if(user&&!user.value&&u)setVal(user,u);setVal(pw,p);});" +
        "})(" + JSONObject.quote(username) + "," + JSONObject.quote(password) + ");"
