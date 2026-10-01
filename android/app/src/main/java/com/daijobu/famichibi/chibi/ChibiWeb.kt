package com.daijobu.famichibi.chibi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.net.JsonCfg
import com.daijobu.famichibi.net.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/**
 * A transparent WebView running chibi-stage (three.js + @pixiv/three-vrm) and a typed Kotlin API for it.
 *
 * Why a WebView: three-vrm is the reference VRM implementation (MToon, spring bones, expressions,
 * look-at, VRM0/1 differences). Re-implementing that in raw OpenGL was the source of the prototype's
 * avatar bugs, so the renderer is the official library and Kotlin only choreographs.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ChibiWeb(context: Context, private val query: String = "") : WebView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<String>()
    var ready = false
        private set
    var onEvent: ((JsonObject) -> Unit)? = null
    private val loaded = HashSet<String>()

    private val loader = WebViewAssetLoader.Builder()
        .setDomain(DOMAIN)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/avatars/", WebViewAssetLoader.InternalStoragePathHandler(context, Graph.avatars.dir))
        .build()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.textZoom = 100
        addJavascriptInterface(Bridge(), "ChibiNative")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("ChibiWeb", "${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                return true
            }
        }
        loadUrl("$ORIGIN/assets/stage/index.html?$query")
    }

    inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            val ev = runCatching { JsonCfg.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
            main.post {
                if (ev.str("type") == "ready") {
                    ready = true
                    pending.forEach { evaluateJavascript(it, null) }
                    pending.clear()
                }
                if (ev.str("type") == "loaded") ev.str("id")?.let { loaded.add(it) }
                onEvent?.invoke(ev)
            }
        }
    }

    /** Run JS once the stage is ready (queued before that). */
    fun js(code: String) {
        main.post {
            if (ready) evaluateJavascript(code, null) else pending.add(code)
        }
    }

    private fun q(s: String?) = if (s == null) "null" else JSONObject.quote(s)

    fun isLoaded(id: String) = id in loaded

    fun add(id: String, url: String, name: String, color: String, ai: Boolean = false, x: Float? = null) {
        val opts = JSONObject().put("name", name).put("color", color).put("ai", ai)
        if (x != null) opts.put("x", x.toDouble())
        js("Chibi.add(${q(id)}, ${q(url)}, $opts)")
    }

    // ---- living room ----
    fun room(members: Int) = js("Chibi.room({members:$members})")
    fun presence(id: String, home: Boolean, state: String?, label: String, animate: Boolean = false, text: String? = null) {
        val p = JSONObject().put("home", home).put("state", state ?: "idle").put("label", label)
        text?.let { p.put("text", it) }
        js("Chibi.presence(${q(id)}, $p, $animate)")
    }
    fun lights(on: Boolean) = js("Chibi.lights($on)")

    fun remove(id: String) { loaded.remove(id); js("Chibi.remove(${q(id)})") }
    fun clear() { loaded.clear(); js("Chibi.clear()") }
    fun play(id: String, motion: String, restart: Boolean = true) = js("Chibi.play(${q(id)}, ${q(motion)}, {restart:$restart})")
    fun walk(id: String, dir: Int) = js("Chibi.walk(${q(id)}, $dir)")
    fun emotion(id: String, e: String) = js("Chibi.emotion(${q(id)}, ${q(e)})")
    fun speaking(id: String, on: Boolean) = js("Chibi.speaking(${q(id)}, $on)")
    fun mouth(id: String, level: Float) = js("Chibi.mouth(${q(id)}, $level)")
    fun carried(id: String, on: Boolean, vx: Float = 0f) = js("Chibi.carried(${q(id)}, $on, $vx)")
    fun inertia(id: String, ax: Float, ay: Float) = js("Chibi.inertia(${q(id)}, $ax, $ay)")
    fun fx(kind: String, id: String? = null) = js("Chibi.fx(${q(kind)}, ${q(id)})")
    fun unsay(id: String) = js("Chibi.unsay(${q(id)})")
    fun party() = js("Chibi.party()")
    fun arrangeRow(ids: List<String>) = js("Chibi.arrangeRow(${org.json.JSONArray(ids)})")
    fun configure(opts: JSONObject) = js("Chibi.configure($opts)")
    fun pause(p: Boolean) = js("Chibi.pause($p)")
    fun sequence(id: String, motions: List<String>) =
        js("Chibi.sequence(${q(id)}, ${org.json.JSONArray(motions.map { JSONObject().put("motion", it) })})")

    fun say(id: String, text: String, opts: JSONObject = JSONObject()) {
        opts.put("text", text)
        js("Chibi.say(${q(id)}, $opts)")
    }

    /** Deliver a message: emotion + motion + particles + bubble (see Chibi.deliver). */
    fun deliver(id: String, text: String?, emotion: String, motion: String?, fx: String?, urgent: Boolean = false,
                replies: List<String> = emptyList(), reasons: List<String> = emptyList(), ref: String = "",
                duration: Long? = null, badge: String? = null, audio: Boolean = false, sticky: Boolean = false) {
        val o = JSONObject().put("emotion", emotion).put("urgent", urgent).put("ref", ref)
        text?.let { o.put("text", it) }
        motion?.let { o.put("motion", it) }
        if (fx != null) o.put("fx", fx)
        if (replies.isNotEmpty()) o.put("replies", org.json.JSONArray(replies))
        if (reasons.isNotEmpty()) o.put("reasons", org.json.JSONArray(reasons))
        duration?.let { o.put("duration", it) }
        badge?.let { o.put("badge", it) }
        if (audio) o.put("audio", true)
        if (sticky) o.put("sticky", true)
        js("Chibi.deliver(${q(id)}, $o)")
    }

    companion object {
        const val DOMAIN = "appassets.androidplatform.net"
        const val ORIGIN = "https://$DOMAIN"
        fun num(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
        fun uri(s: String): Uri = Uri.parse(s)
    }
}
