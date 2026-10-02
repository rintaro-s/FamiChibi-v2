package com.daijobu.famichibi.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.chibi.ChibiWeb
import com.daijobu.famichibi.net.ChibiCue
import com.daijobu.famichibi.net.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What the overlay needs to know about a chibi. */
data class ActorInfo(val id: String, val avatarUrl: String?, val name: String, val color: String, val pitch: Float, val ai: Boolean)

/**
 * The resident chibi drawn over other apps (SYSTEM_ALERT_WINDOW).
 *
 * Geometry: the chibi is described by an *anchor* = (center x of the model, bottom y of the model) in
 * screen pixels. The WebView always has the same size (model area + bubble area); the window is a
 * clipping rectangle around it: compact (just the model, so it blocks as few touches as possible)
 * or expanded (model + speech bubble). Because the WebView never resizes, the 3D model never jumps.
 *
 * Choreography: walk in from the screen edge -> deliver (motion, face, bubble, voice) -> idle in the
 * corner. It can be dragged, thrown (physics with bounces), or thrown off-screen, in which case it
 * sulks and later peeks back from that edge.
 */
@SuppressLint("ClickableViewAccessibility")
class OverlayController(private val ctx: Context, private val cb: Handler) {
    interface Handler {
        fun resolveActor(cue: ChibiCue): ActorInfo?
        fun onReply(cue: ChibiCue, text: String)
        fun onOpen(cue: ChibiCue?)
        fun onSeen(cue: ChibiCue)
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val dp = ctx.resources.displayMetrics.density
    private val scope = Graph.scope

    private var frame: ChibiFrame? = null
    private var web: ChibiWeb? = null
    private var lp: WindowManager.LayoutParams? = null

    // geometry (px)
    private var modelW = 0; private var modelH = 0; private var bubbleH = 0; private var webW = 0; private var webH = 0
    private var screenW = 0; private var screenH = 0; private var insetTop = 0; private var insetBottom = 0
    private var anchorX = 0f
    private var anchorY = 0f
    private var homeX = -1f
    private var expanded = false

    private enum class State { HIDDEN, WALKING, IDLE, DELIVERING, CARRIED, FLYING, AWAY, PARTY }
    private var state = State.HIDDEN

    private var visibleActor: String? = null
    private val actors = HashMap<String, ActorInfo>()
    private val queue = ArrayDeque<ChibiCue>()
    private var worker: Job? = null
    private var current: ChibiCue? = null
    private var bubbleDone: CompletableDeferred<Unit>? = null
    private val loadWaiters = HashMap<String, CompletableDeferred<Boolean>>()
    private var awayEdge = 1
    private var awayJob: Job? = null
    private var awayUntil = 0L

    val isShowing get() = frame != null

    fun canDraw() = Settings.canDrawOverlays(ctx)

    // ------------------------------------------------------------------ public API
    fun enqueue(cue: ChibiCue) {
        if (!canDraw()) return
        // one appearance per message: a re-notification replaces the queued original
        if (cue.messageId != 0L) queue.removeAll { it.messageId == cue.messageId }
        if (cue.messageId != 0L && current?.messageId == cue.messageId) return
        if (cue.urgent) queue.addFirst(cue) else queue.addLast(cue)
        while (queue.size > 12) queue.removeLast()
        ensureWorker()
    }

    /** Show a resident chibi idling in the corner without a message. */
    fun showResident(actor: ActorInfo) {
        if (!canDraw() || state != State.HIDDEN || com.daijobu.famichibi.data.Silent.isOn()) return
        actors[actor.id] = actor
        scope.launch {
            ensureWindow()
            if (loadActor(actor)) walkIn(actor.id, fromEdge = 1)
            state = State.IDLE
        }
    }

    fun party(title: String, members: List<ActorInfo>, lines: List<Pair<String, String>>) {
        if (!canDraw() || com.daijobu.famichibi.data.Silent.isOn()) return
        queue.clear()
        worker?.cancel()
        scope.launch { runParty(title, members, lines) }
    }

    /** The message was answered / seen somewhere: forget queued appearances of it. */
    fun drop(messageId: Long) {
        if (messageId != 0L) queue.removeAll { it.messageId == messageId }
    }

    fun hide() {
        worker?.cancel()
        queue.clear()
        removeWindow()
    }

    fun setPausedForApp(inApp: Boolean) {
        // When the app itself is in the foreground the in-app living room shows the chibis.
        val f = frame ?: return
        val p = lp ?: return
        f.visibility = if (inApp) View.INVISIBLE else View.VISIBLE
        p.flags = if (inApp) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(f, p) }
        web?.pause(inApp)
    }

    // ------------------------------------------------------------------ window
    private fun measure() {
        val s = Graph.prefs.settings.value
        val k = s.chibiSize.coerceIn(0.7f, 1.6f)
        modelW = (118 * dp * k).toInt()
        modelH = (178 * dp * k).toInt()
        bubbleH = (230 * dp * s.fontScale.coerceIn(0.8f, 1.6f)).toInt()
        val metrics = wm.currentWindowMetrics
        val b = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        screenW = b.width(); screenH = b.height()
        insetTop = insets.top; insetBottom = insets.bottom
        webW = min((300 * dp).toInt(), screenW)
        webH = modelH + bubbleH
        if (homeX < 0) homeX = screenW - modelW / 2f - 10 * dp
        homeX = homeX.coerceIn(modelW / 2f, screenW - modelW / 2f)
    }

    private fun floorY() = (screenH - insetBottom - 6 * dp)

    private fun ensureWindow() {
        if (frame != null) return
        measure()
        val w = ChibiWeb(ctx, "fps=30&tex=512&fraction=${modelH.toFloat() / webH}&font=${Graph.prefs.settings.value.fontScale}")
        w.onEvent = ::onWebEvent
        val f = ChibiFrame(ctx)
        f.addView(w, FrameLayout.LayoutParams(webW, webH))
        f.clipChildren = true
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val p = WindowManager.LayoutParams(
            modelW, modelH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 31) p.alpha = 1f
        anchorX = screenW + modelW.toFloat()
        anchorY = floorY()
        frame = f; web = w; lp = p
        applyGeometry(p)
        runCatching { wm.addView(f, p) }.onFailure { Log.w(TAG, "addView: $it"); frame = null; web = null; lp = null }
        w.configure(JSONObject().put("maxBubbleWidth", (webW / dp - 16).toInt()))
    }

    private fun removeWindow() {
        frame?.let { runCatching { wm.removeView(it) } }
        web?.destroy()
        frame = null; web = null; lp = null
        visibleActor = null
        state = State.HIDDEN
        loadWaiters.values.forEach { it.complete(false) }
        loadWaiters.clear()
    }

    /** Position window + WebView offset from anchor and expanded state. */
    private fun applyGeometry(params: WindowManager.LayoutParams? = lp) {
        val p = params ?: return
        val webLeftScreen = anchorX - webW / 2f
        val webTopScreen = anchorY - webH
        val w: Int; val h: Int; val x: Int; val y: Int
        if (expanded) {
            w = min(webW, screenW); h = webH
            x = (anchorX - w / 2f).toInt().coerceIn(0, max(0, screenW - w))
            y = (anchorY - h).toInt()
        } else {
            w = modelW; h = modelH
            x = (anchorX - modelW / 2f).toInt()
            y = (anchorY - modelH).toInt()
        }
        p.width = w; p.height = h; p.x = x; p.y = y
        web?.let {
            it.translationX = webLeftScreen - x
            it.translationY = webTopScreen - y
        }
        frame?.let { f -> if (f.isAttachedToWindow) runCatching { wm.updateViewLayout(f, p) } }
    }

    private fun setExpanded(on: Boolean) {
        if (expanded == on) return
        expanded = on
        val p = lp ?: return
        applyGeometry(p)
        val webLeftScreen = anchorX - webW / 2f
        // Tell JS which horizontal part of the WebView is actually visible so bubbles stay on screen.
        val clipL = max(0f, p.x - webLeftScreen)
        val clipR = min(webW.toFloat(), p.x + p.width - webLeftScreen)
        web?.configure(JSONObject().put("clipLeft", (clipL / dp).toDouble()).put("clipRight", (clipR / dp).toDouble()))
    }

    // ------------------------------------------------------------------ JS events
    private fun onWebEvent(ev: JsonObject) {
        when (ev.str("type")) {
            "loaded" -> ev.str("id")?.let { loadWaiters.remove(it)?.complete(true) }
            "error" -> ev.str("id")?.let { loadWaiters.remove(it)?.complete(false) }
            "bubbleHidden" -> bubbleDone?.complete(Unit)
            "reply" -> {
                val cue = current ?: return
                cb.onReply(cue, ev.str("text").orEmpty())
                bubbleDone?.complete(Unit)
            }
            "bubbleTap" -> {
                val cue = current
                if (cue != null) cb.onSeen(cue)
                cb.onOpen(cue)
            }
            "tapActor" -> onPoke(ev.str("part") == "head")
        }
    }

    private fun onPoke(head: Boolean) {
        val id = visibleActor ?: return
        if (state != State.IDLE) {
            current?.let { cb.onSeen(it) }
            return
        }
        val w = web ?: return
        if (head) {
            w.deliver(id, null, "happy", "bothWave", "hearts")
        } else {
            val picks = listOf("surprised" to "surprised", "happy" to "jump", "happy" to "wave")
            val (e, m) = picks.random()
            w.deliver(id, null, e, m, if (m == "surprised") "exclaim" else "sparkle")
        }
    }

    // ------------------------------------------------------------------ actors
    private suspend fun loadActor(a: ActorInfo): Boolean {
        val w = web ?: return false
        actors[a.id] = a
        val url = a.avatarUrl ?: return false
        val local = Graph.avatars.ensure(url) ?: return false
        if (w.isLoaded(a.id, local)) return true
        val waiter = CompletableDeferred<Boolean>()
        loadWaiters[a.id] = waiter
        w.add(a.id, local, a.name, a.color, a.ai)
        return withTimeoutOrNull(25_000) { waiter.await() } ?: false
    }

    // ------------------------------------------------------------------ choreography
    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (queue.isNotEmpty()) {
                if (state == State.CARRIED || state == State.FLYING || state == State.PARTY) { delay(300); continue }
                val cue = queue.removeFirst()
                runCatching { deliver(cue) }.onFailure { Log.w(TAG, "deliver failed: $it") }
            }
            afterQueue()
        }
    }

    private suspend fun deliver(cue: ChibiCue) {
        val actor = cb.resolveActor(cue) ?: return
        ensureWindow()
        val w = web ?: return
        if (!loadActor(actor)) {
            Log.w(TAG, "could not load avatar for ${actor.name}")
            return
        }
        current = cue
        if (state == State.AWAY) {
            // thrown away earlier: peek from that edge first
            awayJob?.cancel()
            comeBackFromAway(actor.id)
        } else if (visibleActor != actor.id || state == State.HIDDEN) {
            val old = visibleActor
            if (old != null && state != State.HIDDEN) walkOut(old)
            walkIn(actor.id, fromEdge = 1)
        }
        state = State.DELIVERING
        setExpanded(true)
        val s = Graph.prefs.settings.value
        val quiet = cue.quiet
        val done = CompletableDeferred<Unit>()
        bubbleDone = done
        val badge = when {
            cue.kind == "nudge" -> "未読"
            cue.kind == "memo" -> "メモ"
            cue.kind == "ask" -> "質問"
            else -> null
        }
        w.deliver(
            actor.id, cue.text, cue.emotion, if (quiet) "nod" else cue.motion, if (quiet) null else cue.fx, cue.urgent,
            cue.replies.take(4), cue.reasons, ref = "${cue.kind}:${cue.messageId}",
            duration = cue.duration ?: (8_000L + cue.text.length * 220L).coerceAtMost(20_000L),
            badge = badge, audio = cue.media.isNotBlank(), sticky = cue.urgent || cue.kind == "ask",
        )
        // voice
        var speakingDone = CompletableDeferred<Unit>()
        if (cue.media.isNotBlank()) {
            w.speaking(actor.id, true)
            Graph.speech.playVoice(cue.media, onLevel = { lv -> web?.mouth(actor.id, lv) }, onDone = {
                web?.speaking(actor.id, false); speakingDone.complete(Unit)
            })
        } else if (s.tts && !quiet && (s.bigMode || !cue.quiet)) {
            val prefix = if (cue.kind == "message" && !actor.ai) "${actor.name}から。" else ""
            Graph.speech.speak(prefix + cue.text, actor.pitch, s.ttsRate * (if (s.bigMode) 0.9f else 1f),
                onStart = { web?.speaking(actor.id, true) }, onDone = { web?.speaking(actor.id, false); speakingDone.complete(Unit) })
        } else speakingDone.complete(Unit)

        val limit = if (cue.urgent || cue.kind == "ask") 5 * 60_000L else (cue.duration ?: (8_000L + cue.text.length * 220L).coerceAtMost(20_000L)) + 4_000L
        withTimeoutOrNull(limit) { done.await() }
        withTimeoutOrNull(8_000) { speakingDone.await() }
        w.unsay(actor.id)
        delay(260)
        setExpanded(false)
        current = null
        if (state == State.DELIVERING) state = State.IDLE
        delay(400)
    }

    private suspend fun afterQueue() {
        if (state == State.HIDDEN || state == State.AWAY) return
        val s = Graph.prefs.settings.value
        if (!s.resident) {
            visibleActor?.let { walkOut(it) }
            removeWindow()
        } else {
            state = State.IDLE
        }
    }

    private suspend fun walkIn(id: String, fromEdge: Int) {
        val w = web ?: return
        val target = homeX
        anchorY = floorY()
        anchorX = if (fromEdge > 0) screenW + modelW * 0.6f else -modelW * 0.6f
        setExpanded(false)
        applyGeometry()
        w.js("Chibi.only(${JSONObject.quote(id)})")
        visibleActor = id
        state = State.WALKING
        w.walk(id, if (target < anchorX) -1 else 1)
        animateAnchorTo(target, speedDpPerSec = 150f)
        w.walk(id, 0)
        state = State.IDLE
        delay(250)
    }

    private suspend fun walkOut(id: String) {
        val w = web ?: return
        setExpanded(false)
        state = State.WALKING
        val dir = if (anchorX > screenW / 2) 1 else -1
        w.walk(id, dir)
        animateAnchorTo(if (dir > 0) screenW + modelW * 0.7f else -modelW * 0.7f, speedDpPerSec = 220f)
        w.walk(id, 0)
    }

    private suspend fun animateAnchorTo(targetX: Float, speedDpPerSec: Float) {
        val start = anchorX
        val dist = abs(targetX - start)
        if (dist < 1) return
        val dur = (dist / (speedDpPerSec * dp) * 1000).toLong().coerceIn(300, 4000)
        val t0 = System.nanoTime()
        while (true) {
            val t = ((System.nanoTime() - t0) / 1e6 / dur).toFloat().coerceAtMost(1f)
            anchorX = start + (targetX - start) * t
            applyGeometry()
            if (t >= 1f) break
            awaitFrame()
        }
    }

    private suspend fun awaitFrame() {
        val d = CompletableDeferred<Unit>()
        Choreographer.getInstance().postFrameCallback { d.complete(Unit) }
        d.await()
    }

    // ------------------------------------------------------------------ thrown away / comeback
    private fun goneAway(edge: Int) {
        state = State.AWAY
        awayEdge = edge
        awayUntil = System.currentTimeMillis() + 3 * 60_000
        awayJob?.cancel()
        awayJob = scope.launch {
            delay(3 * 60_000)
            if (state == State.AWAY && queue.isEmpty()) visibleActor?.let { comeBackFromAway(it); state = State.IDLE }
        }
    }

    private suspend fun comeBackFromAway(id: String) {
        val w = web ?: return
        anchorY = floorY()
        anchorX = if (awayEdge > 0) screenW + modelW * 0.05f else -modelW * 0.05f
        w.js("Chibi.only(${JSONObject.quote(id)})")
        visibleActor = id
        applyGeometry()
        w.play(id, "peek")
        w.emotion(id, "angry")
        delay(1200)
        w.say(id, listOf("もう投げないでね…", "ひどいよ〜", "戻ってきたよ…ぷんぷん").random(), JSONObject().put("duration", 2200))
        setExpanded(true)
        delay(2300)
        setExpanded(false)
        w.emotion(id, "neutral")
        state = State.WALKING
        w.walk(id, -awayEdge)
        homeX = homeX.coerceIn(modelW.toFloat(), screenW - modelW.toFloat())
        animateAnchorTo(homeX, 150f)
        w.walk(id, 0)
        state = State.IDLE
    }

    // ------------------------------------------------------------------ party mode
    private suspend fun runParty(title: String, members: List<ActorInfo>, lines: List<Pair<String, String>>) {
        ensureWindow()
        val w = web ?: return
        val p = lp ?: return
        state = State.PARTY
        members.forEach { loadActor(it) }
        // full width stage at the bottom of the screen
        val h = (modelH * 1.25f + bubbleH).toInt()
        p.width = screenW; p.height = h; p.x = 0; p.y = (floorY() - h).toInt()
        w.translationX = 0f; w.translationY = 0f
        w.layoutParams = FrameLayout.LayoutParams(screenW, h)
        runCatching { wm.updateViewLayout(frame, p) }
        w.configure(JSONObject().put("layout", "row").put("modelFraction", (modelH * 1.25f) / h).put("clipLeft", 0).put("clipRight", screenW / dp))
        w.js("(function(){const ids=${org.json.JSONArray(members.map { it.id })};for(const id of Chibi.ids())Chibi.visible(id, ids.includes(id));Chibi.arrangeRow(ids);Chibi.party();})()")
        w.say(members.first().id, title, JSONObject().put("duration", 4500))
        Graph.speech.speak(title, 1.1f)
        delay(4800)
        for ((uid, text) in lines) {
            val a = members.firstOrNull { it.id == uid } ?: continue
            w.deliver(a.id, text, "happy", "bothWave", "hearts", duration = 5500)
            Graph.speech.speak(text, a.pitch, onStart = { web?.speaking(a.id, true) }, onDone = { web?.speaking(a.id, false) })
            delay(5800)
        }
        w.fx("confetti")
        delay(3000)
        // back to the single chibi
        w.layoutParams = FrameLayout.LayoutParams(webW, webH)
        w.configure(JSONObject().put("layout", "single").put("modelFraction", modelH.toFloat() / webH).put("clipLeft", 0).put("clipRight", webW / dp))
        val keep = visibleActor ?: members.first().id
        w.js("Chibi.only(${JSONObject.quote(keep)}); (Chibi._stage().actors.get(${JSONObject.quote(keep)})||{}).root && (Chibi._stage().actors.get(${JSONObject.quote(keep)}).root.position.x = 0)")
        visibleActor = keep
        expanded = true
        anchorX = homeX; anchorY = floorY()
        setExpanded(false)
        applyGeometry()
        state = State.IDLE
        ensureWorker()
    }

    // ------------------------------------------------------------------ touch: drag & throw
    inner class ChibiFrame(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var grabDX = 0f; private var grabDY = 0f
        private var dragging = false
        private var vt: VelocityTracker? = null
        private var lastVx = 0f; private var lastVy = 0f; private var lastT = 0L

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false
                    grabDX = e.rawX - anchorX; grabDY = e.rawY - anchorY
                    vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop * 1.5f && canDrag()) {
                        startDrag()
                        return true
                    }
                }
            }
            return false
        }

        private fun canDrag() = state == State.IDLE || state == State.DELIVERING || state == State.WALKING

        private fun startDrag() {
            dragging = true
            state = State.CARRIED
            current?.let { c -> cb.onSeen(c) }
            visibleActor?.let { web?.unsay(it); web?.carried(it, true) }
            setExpanded(false)
            lastT = System.nanoTime()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!dragging) {
                if (e.actionMasked == MotionEvent.ACTION_MOVE && hypot(e.rawX - downX, e.rawY - downY) > slop * 1.5f && canDrag()) startDrag()
                else return super.onTouchEvent(e)
            }
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    anchorX = e.rawX - grabDX
                    anchorY = e.rawY - grabDY
                    applyGeometry()
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f; val vy = vt?.yVelocity ?: 0f
                    val now = System.nanoTime(); val dt = max(1e-3f, (now - lastT) / 1e9f)
                    val ax = (vx - lastVx) / dt; val ay = (vy - lastVy) / dt
                    lastVx = vx; lastVy = vy; lastT = now
                    visibleActor?.let { web?.carried(it, true, vx); web?.inertia(it, ax, ay) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f; val vy = vt?.yVelocity ?: 0f
                    vt?.recycle(); vt = null
                    dragging = false
                    visibleActor?.let { web?.carried(it, false); web?.inertia(it, 0f, 0f) }
                    fly(vx, vy)
                }
            }
            return true
        }
    }

    private fun fly(vx0: Float, vy0: Float) {
        val id = visibleActor ?: return
        val w = web ?: return
        state = State.FLYING
        var vx = vx0.coerceIn(-9000f, 9000f)
        var vy = vy0.coerceIn(-9000f, 9000f)
        val fast = hypot(vx, vy) > 1400
        if (fast) w.play(id, "thrown") else w.play(id, "carried")
        val g = 4200f
        var last = System.nanoTime()
        var bounces = 0
        scope.launch {
            while (true) {
                awaitFrame()
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9f).coerceAtMost(0.033f)
                last = now
                vy += g * dt
                anchorX += vx * dt
                anchorY += vy * dt
                // thrown off screen -> sulk away
                if (anchorX < -modelW * 0.6f || anchorX > screenW + modelW * 0.6f) {
                    applyGeometry()
                    goneAway(if (anchorX > 0) 1 else -1)
                    w.play(id, "idle")
                    return@launch
                }
                val half = modelW / 2f
                val hardThrow = abs(vx) > 2600
                if (!hardThrow) {
                    if (anchorX < half) { anchorX = half; vx = abs(vx) * 0.45f }
                    if (anchorX > screenW - half) { anchorX = screenW - half; vx = -abs(vx) * 0.45f }
                }
                val top = insetTop + modelH.toFloat()
                if (anchorY < top) { anchorY = top; vy = abs(vy) * 0.4f }
                val floor = floorY()
                if (anchorY >= floor) {
                    anchorY = floor
                    if (abs(vy) > 700 && bounces < 3) {
                        vy = -abs(vy) * 0.33f; vx *= 0.7f; bounces++
                    } else {
                        applyGeometry()
                        break
                    }
                }
                applyGeometry()
            }
            // landed
            homeX = anchorX.coerceIn(modelW / 2f, screenW - modelW / 2f)
            anchorX = homeX
            applyGeometry()
            web?.sequence(id, if (fast) listOf("land", "dizzy") else listOf("land"))
            delay(if (fast) 3400 else 800)
            state = if (current != null) State.DELIVERING else State.IDLE
            if (current != null) setExpanded(true)
            ensureWorker()
        }
    }

    companion object {
        const val TAG = "Overlay"
    }
}
