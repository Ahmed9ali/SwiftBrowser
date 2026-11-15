package com.swift.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * "Export JSON / CSV" style buttons make the file inside the page (a blob: or data: link), so there is
 * no web address the download manager could fetch. The page itself reads the blob and hands it over
 * in small pieces; the finished file is then added to Downloads like any other.
 */
object BlobDownloads {
    /** Runs at document start in every page: remembers blobs and the file names anchors ask for. */
    const val HOOK_JS = """
(function(){
 if(window.__swiftBlob)return;
 var M={},N={},order=[];
 try{
  var c=URL.createObjectURL;
  URL.createObjectURL=function(b){
   var u=c.apply(this,arguments);
   try{ if(b&&typeof b==='object'&&typeof b.size==='number'){ M[u]=b; order.push(u); if(order.length>15){var o=order.shift();delete M[o];delete N[o];} } }catch(e){}
   return u;
  };
  var ac=HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click=function(){
   try{ if(this.download&&this.href)N[this.href]=this.download; }catch(e){}
   return ac.apply(this,arguments);
  };
  document.addEventListener('click',function(e){
   try{var a=e.target&&e.target.closest&&e.target.closest('a[download]'); if(a&&a.href)N[a.href]=a.download;}catch(x){}
  },true);
 }catch(e){}
 window.__swiftBlob={get:function(u){return M[u]||null;},name:function(u){return N[u]||'';}};
})();
"""

    private const val FETCH_JS = """
(function(u,fallbackName,mime,id){
 function fail(m){try{SwiftBlob.fail(id,String(m));}catch(e){}}
 function go(b){
  var nm=(window.__swiftBlob&&window.__swiftBlob.name(u))||fallbackName;
  SwiftBlob.begin(id,nm,b.type||mime||'');
  var CH=786432,pos=0;
  (function next(){
   if(pos>=b.size){SwiftBlob.end(id);return;}
   var fr=new FileReader();
   fr.onload=function(){SwiftBlob.chunk(id,String(fr.result).split(',')[1]||'');pos+=CH;next();};
   fr.onerror=function(){fail('read error');};
   fr.readAsDataURL(b.slice(pos,pos+CH));
  })();
 }
 try{
  var b=window.__swiftBlob&&window.__swiftBlob.get(u);
  if(b){go(b);return;}
  fetch(u).then(function(r){return r.blob();}).then(go).catch(fail);
 }catch(e){fail(e);}
})(__URL__,__NAME__,__MIME__,__ID__)
"""

    fun startBlob(wv: WebView, url: String, fallbackName: String, mime: String?) {
        val js = FETCH_JS
            .replace("__URL__", JSONObject.quote(url))
            .replace("__NAME__", JSONObject.quote(fallbackName))
            .replace("__MIME__", JSONObject.quote(mime ?: ""))
            .replace("__ID__", JSONObject.quote(System.nanoTime().toString()))
        wv.evaluateJavascript(js, null)
    }

    /** data:[mime][;base64],payload */
    fun saveDataUrl(context: Context, url: String, fallbackName: String, mimeHint: String?): Boolean {
        val comma = url.indexOf(',')
        if (!url.startsWith("data:") || comma < 0) return false
        val head = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val mime = head.substringBefore(';').ifBlank { mimeHint ?: "application/octet-stream" }
        val bytes = try {
            if (head.contains(";base64")) Base64.decode(java.net.URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8"), Base64.DEFAULT)
            else java.net.URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray()
        } catch (e: Exception) { return false }
        val dir = File(context.cacheDir, "blob_dl").apply { mkdirs() }
        val f = File(dir, "d_" + System.nanoTime() + ".tmp")
        f.writeBytes(bytes)
        DownloadEngine.addLocalFile(withExtension(fallbackName, mime), mime, f)
        return true
    }

    fun withExtension(name: String, mime: String?): String {
        val clean = name.substringAfterLast('/').ifBlank { "download" }
        if (clean.substringAfterLast('.', "").isNotEmpty() && clean.contains('.')) return clean
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.substringBefore(';').trim()) }
        return if (ext.isNullOrBlank()) clean else "$clean.$ext"
    }

    /** Receives the pieces from the page. */
    class Bridge(private val context: Context) {
        private val outs = ConcurrentHashMap<String, OutputStream>()
        private val meta = ConcurrentHashMap<String, Triple<String, String, File>>()
        private val ui = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun begin(id: String, name: String, mime: String) {
            val dir = File(context.cacheDir, "blob_dl").apply { mkdirs() }
            val f = File(dir, "b_$id.tmp")
            outs[id] = f.outputStream().buffered()
            meta[id] = Triple(name.ifBlank { "download" }, mime, f)
        }

        @JavascriptInterface
        fun chunk(id: String, b64: String) {
            try {
                outs[id]?.write(Base64.decode(b64, Base64.DEFAULT))
            } catch (e: Exception) { fail(id, e.message ?: "write error") }
        }

        @JavascriptInterface
        fun end(id: String) {
            runCatching { outs.remove(id)?.close() }
            val m = meta.remove(id) ?: return
            DownloadEngine.addLocalFile(withExtension(m.first, m.second), m.second.ifBlank { null }, m.third)
        }

        @JavascriptInterface
        fun fail(id: String, msg: String) {
            runCatching { outs.remove(id)?.close() }
            meta.remove(id)?.third?.delete()
            ui.post { Toast.makeText(context, "Couldn't save this file", Toast.LENGTH_SHORT).show() }
        }
    }
}
