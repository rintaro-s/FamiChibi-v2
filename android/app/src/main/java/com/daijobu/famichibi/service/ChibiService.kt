package com.daijobu.famichibi.service

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.daijobu.famichibi.FamRef
import com.daijobu.famichibi.Graph
import kotlinx.coroutines.flow.distinctUntilChanged
import com.daijobu.famichibi.external.ExternalChats
import com.daijobu.famichibi.LockScreenActivity
import com.daijobu.famichibi.MainActivity
import com.daijobu.famichibi.data.Silent
import com.daijobu.famichibi.net.ChibiCue
import com.daijobu.famichibi.net.HostSession
import com.daijobu.famichibi.net.JsonCfg
import com.daijobu.famichibi.net.Message
import com.daijobu.famichibi.net.Notify
import com.daijobu.famichibi.net.obj
import com.daijobu.famichibi.net.str
import com.daijobu.famichibi.net.strOrNull
import com.daijobu.famichibi.overlay.ActorInfo
import com.daijobu.famichibi.overlay.OverlayController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Keeps the family connections alive and turns server events into chibi appearances:
 * overlay chibi (normal), lock-screen chibis (urgent / unread while locked), system notifications
 * (safety net). Also receives quick replies from bubbles and notifications.
 */
class ChibiService : Service(), OverlayController.Handler {
    private lateinit var notifier: Notifier
    private lateinit var overlay: OverlayController
    private lateinit var presence: PresenceReporter
    private val jobs = ArrayList<Job>()
    private var screenReceiver: BroadcastReceiver? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        instance = this
        notifier = Notifier(this)
        overlay = OverlayController(this, this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.SERVICE_ID, notifier.serviceNotification("ちびっこが見守っています"), type)
        observe()
        presence = PresenceReporter(this).also { it.start() }
        registerScreen()
        registerNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> overlay.hide()
            ACTION_TEST -> testChibi()
            ACTION_SILENT -> intent.getIntExtra("minutes", 60).let { m -> if (m < 0) Silent.enableUntilMorning() else Silent.enable(m) }
            ACTION_SILENT_OFF -> Silent.disable()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        presence.stop()
        overlay.hide()
        screenReceiver?.let { unregisterReceiver(it) }
        netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ event plumbing
    private val observed = HashSet<HostSession>()
    private val pendingForOverlay = ArrayList<ChibiCue>()

    private fun observe() {
        jobs += Graph.scope.launch {
            Graph.sessions.collect { list ->
                list.filter { observed.add(it) }.forEach { s -> jobs += Graph.scope.launch { s.events.collect { onEvent(s, it) } } }
            }
        }
        // LINE / SMS messages from linked family members, brought by their chibis (on-device only)
        jobs += Graph.scope.launch { ExternalChats.incoming.collect { dispatch(it.ref, it.cue, external = true) } }
        jobs += Graph.scope.launch {
            Graph.appInForeground.collect { fg ->
                overlay.setPausedForApp(fg)
                if (!fg && Graph.prefs.settings.value.overlay) {
                    // messages that arrived while the app was open (for other families) walk in now
                    pendingForOverlay.takeLast(3).forEach { overlay.enqueue(it) }
                    pendingForOverlay.clear()
                    // leaving the app: the resident chibi comes out to the home screen
                    if (!overlay.isShowing && Graph.prefs.settings.value.resident) {
                        val ref = Graph.famRef(Graph.prefs.lastFamilyId) ?: Graph.families.value.firstOrNull()
                        ref?.let { r -> resolveActor(ChibiCue(from = "ai", familyId = r.family.id))?.let { overlay.showResident(it) } }
                    }
                }
            }
        }
        jobs += Graph.scope.launch {
            Graph.prefs.settings.collect { s -> if (!s.overlay) overlay.hide() }
        }
        // Silent mode: the resident chibi steps out (and comes back when it ends); the status notification shows it.
        jobs += Graph.scope.launch {
            var first = true
            kotlinx.coroutines.flow.combine(Silent.on, Graph.prefs.settings) { on, st -> on to st.silentShowChibi }
                .distinctUntilChanged().collect { (on, showChibi) ->
                notifier.updateService(on)
                if (on) Graph.speech.stop()
                if (on && !showChibi) overlay.hide()
                else if (!first && Graph.prefs.settings.value.overlay && Graph.prefs.settings.value.resident && !Graph.appInForeground.value) {
                    val ref = Graph.famRef(Graph.prefs.lastFamilyId) ?: Graph.families.value.firstOrNull()
                    ref?.let { r -> resolveActor(ChibiCue(from = "ai", familyId = r.family.id))?.let { overlay.showResident(it) } }
                }
                first = false
            }
        }
        // Show the resident AI chibi once families are known.
        jobs += Graph.scope.launch {
            val fams = withTimeoutOrNull(20_000) { Graph.families.first { it.isNotEmpty() } } ?: return@launch
            delay(800)
            val ref = fams.firstOrNull { it.family.id == Graph.prefs.lastFamilyId } ?: fams.first()
            if (Graph.prefs.settings.value.overlay && Graph.prefs.settings.value.resident && !Graph.appInForeground.value) {
                resolveActor(ChibiCue(from = "ai", familyId = ref.family.id))?.let { overlay.showResident(it) }
            }
        }
    }

    private fun onEvent(s: HostSession, ev: JsonObject) {
        val type = ev.str("type") ?: return
        if (type == "hello") { presence.push(force = true); return }
        val fid = ev.str("family_id") ?: return
        val settings = Graph.prefs.settings.value
        val famRef = Graph.famRef(fid) ?: return
        val cue: ChibiCue = when (type) {
            "message" -> {
                val m = JsonCfg.decodeFromJsonElement<Message>(ev["message"]!!)
                val plan = ev["notify"]?.let { JsonCfg.decodeFromJsonElement<Notify>(it) } ?: Notify()
                if (plan.mode == "self" || m.user_id == s.userId) return
                if (m.kind == "system") return
                val media = if (m.media.isNotBlank()) s.api.url(m.media) else ""
                val replies = (m.analysis.replies + settings.quickReplies).distinct().take(4)
                ChibiCue(
                    from = m.user_id ?: "ai", text = m.text, emotion = m.analysis.emotion, motion = m.analysis.motion, fx = m.analysis.fx,
                    urgent = plan.mode == "urgent", quiet = plan.mode == "quiet", replies = if (m.isAI) emptyList() else replies,
                    reasons = m.analysis.reasons + listOfNotNull(plan.reason.takeIf { it.isNotBlank() }), media = media,
                    familyId = fid, channelId = m.channel_id, messageId = m.id, kind = "message",
                )
            }
            "arrive", "leave" -> {
                val c = ev.obj("chibi") ?: return
                JsonCfg.decodeFromJsonElement<ChibiCue>(c).copy(familyId = fid, kind = type, channelId = famRef.family.textChannels.firstOrNull()?.id.orEmpty(),
                    quiet = false)
            }
            "memo_due", "ask", "nudge", "poke" -> {
                val c = ev.obj("chibi") ?: return
                val base = JsonCfg.decodeFromJsonElement<ChibiCue>(c)
                val msg = ev.obj("message")
                base.copy(
                    familyId = fid, kind = if (type == "memo_due") "memo" else type,
                    askId = ev.obj("ask")?.str("id").orEmpty(),
                    channelId = msg?.str("channel_id") ?: ev.obj("ask")?.str("channel_id").orEmpty(),
                    messageId = msg?.get("id")?.strOrNull()?.toLongOrNull() ?: 0,
                    urgent = base.urgent || type == "nudge",
                )
            }
            "acked" -> ChibiCue(from = ev.str("by"), text = ev.str("text") ?: "見たよ！", emotion = "happy", motion = "nod", fx = "sparkle",
                quiet = true, familyId = fid, kind = "acked", duration = 4000)
            "party" -> { startParty(famRef, ev); return }
            "ack" -> {
                if (ev.str("user_id") == s.userId) ev["message_id"]?.strOrNull()?.toLongOrNull()?.let { overlay.drop(it) }
                return
            }
            else -> return
        }
        dispatch(famRef, cue)
    }

    /** @param external the message came from another chat app's notification, which is still there: post no second one */
    private fun dispatch(ref: FamRef, original: ChibiCue, external: Boolean = false) {
        val settings = Graph.prefs.settings.value
        // Silent mode: keep it as a soundless notification only (urgent ones may break through).
        val muted = Silent.mutes(original.urgent)
        // "音だけオフ": the chibi still delivers (urgent ones still look urgent), but the notification makes no sound or vibration
        val soundOnly = Silent.soundOnly()
        val cue = if (muted) original.copy(quiet = true, urgent = false) else if (soundOnly) original.copy(quiet = true) else original
        val inThisFamily = Graph.appInForeground.value && Graph.foregroundFamily.value == cue.familyId
        val sender = ref.family.member(cue.from)?.label ?: ref.family.ai_name
        val locked = isLocked()
        LockBoard.add(cue)
        if (!inThisFamily && cue.kind != "acked" && !external) {
            notifier.post(if (soundOnly) cue.copy(urgent = false) else cue, sender, ref.family.name, fullScreen = cue.urgent && locked && settings.lockscreen)
        }
        if (muted) return
        if (locked) {
            if (settings.lockscreen && (cue.urgent || cue.kind == "ask")) wakeToLockScreen()
            return
        }
        // While the app is open its own living room shows the chibis; the overlay waits for the home screen.
        if (inThisFamily || !settings.overlay) return
        if (Graph.appInForeground.value) { pendingForOverlay += cue; return }
        overlay.enqueue(cue)
    }

    private fun startParty(ref: FamRef, ev: JsonObject) {
        val members = ref.family.members.mapNotNull { m -> resolveActor(ChibiCue(from = m.user_id, familyId = ref.family.id)) } +
            listOfNotNull(if (ref.family.ai_enabled) resolveActor(ChibiCue(from = "ai", familyId = ref.family.id)) else null)
        val lines = (ev["messages"] as? JsonArray)?.mapNotNull { e ->
            val o = e.jsonObject
            (o.str("from") ?: return@mapNotNull null) to (o.str("text") ?: "")
        } ?: emptyList()
        val title = ev.str("title") ?: "🎉 パーティー！"
        LockBoard.party = LockBoard.Party(ref.family.id, title, lines, System.currentTimeMillis())
        if (Silent.hidesChibi()) return  // the party waits on the lock board until silent mode ends
        if (isLocked()) { wakeToLockScreen(); return }
        if (Graph.appInForeground.value && Graph.foregroundFamily.value == ref.family.id) return
        overlay.party(title, members.take(5), lines)
    }

    // ------------------------------------------------------------------ OverlayController.Handler
    override fun resolveActor(cue: ChibiCue): ActorInfo? = actorFor(cue.familyId, cue.from)

    override fun onReply(cue: ChibiCue, text: String) {
        if (cue.kind == "external_who") { // "who is this LINE sender?" answered with a member name
            ExternalChats.answerWho(cue, text)
            LockBoard.remove(cue)
            overlay.drop(cue.messageId)
            return
        }
        if (cue.kind == "external") {
            // answer in LINE / SMS itself, through the notification's reply action
            if (!ExternalChats.reply(this, cue, text)) ExternalChats.open(cue)
            LockBoard.remove(cue)
            overlay.drop(cue.messageId)
            return
        }
        val ref = Graph.famRef(cue.familyId) ?: return
        Graph.scope.launch {
            runCatching {
                when (cue.kind) {
                    "ask" -> ref.session.answerAsk(cue.askId, text)
                    "poke" -> if (cue.from != null) ref.session.api.post("/api/families/${cue.familyId}/poke",
                        buildJsonObject { put("to", cue.from); put("kind", if (text.contains("💕")) "pat" else "poke") })
                    else -> {
                        val cid = cue.channelId.ifBlank { ref.family.textChannels.firstOrNull()?.id ?: return@runCatching }
                        ref.session.sendMessage(cid, text)
                        if (cue.messageId != 0L) ref.session.ack(cue.messageId)
                    }
                }
            }
            notifier.cancel(cue)
            LockBoard.remove(cue)
            overlay.drop(cue.messageId)
        }
    }

    override fun onOpen(cue: ChibiCue?) {
        if (cue?.kind == "external" && ExternalChats.open(cue)) return // open the conversation in the chat app
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        cue?.let { i.putExtra("family", it.familyId).putExtra("channel", it.channelId) }
        startActivity(i)
    }

    override fun onSeen(cue: ChibiCue) {
        if (cue.kind.startsWith("external")) { overlay.drop(cue.messageId); LockBoard.remove(cue); return } // nothing to tell the server
        if (cue.messageId == 0L) return
        val ref = Graph.famRef(cue.familyId) ?: return
        Graph.scope.launch { ref.session.ack(cue.messageId) }
        overlay.drop(cue.messageId)
        notifier.cancel(cue)
        LockBoard.remove(cue)
    }

    private fun testChibi() {
        val ref = Graph.families.value.firstOrNull() ?: return
        dispatch(ref, ChibiCue(from = "ai", text = "やっほー！ちびっこはこんな感じで家族の連絡を届けるよ！", emotion = "happy", motion = "wave",
            fx = "sparkle", replies = listOf("よろしく！", "かわいい"), familyId = ref.family.id, kind = "test"))
    }

    // ------------------------------------------------------------------ lock screen
    private fun isLocked(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        return km.isKeyguardLocked || !pm.isInteractive
    }

    private fun wakeToLockScreen() {
        if (!Graph.prefs.settings.value.lockscreen) return
        runCatching {
            startActivity(Intent(this, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun registerScreen() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (Silent.hidesChibi()) return
                if (i.action == Intent.ACTION_SCREEN_ON && LockBoard.hasPending() && Graph.prefs.settings.value.lockscreen && isLocked()) {
                    wakeToLockScreen()
                }
                if (i.action == Intent.ACTION_USER_PRESENT) {
                    // Unlocked: whatever is still unread walks in on the home screen.
                    val pending = LockBoard.takePending()
                    if (Graph.prefs.settings.value.overlay && !Graph.appInForeground.value) pending.forEach { overlay.enqueue(it) }
                    LockBoard.party?.let { p ->
                        if (System.currentTimeMillis() - p.at < 10 * 60_000) Graph.famRef(p.familyId)?.let { ref ->
                            val members = ref.family.members.mapNotNull { m -> actorFor(ref.family.id, m.user_id) }
                            overlay.party(p.title, members.take(5), p.lines)
                        }
                        LockBoard.party = null
                    }
                }
            }
        }
        screenReceiver = r
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun registerNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Graph.scope.launch { delay(500); Graph.sessions.value.forEach { it.reconnectNow() } }
                presence.networkChanged()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        netCb = cb
    }

    companion object {
        const val ACTION_HIDE = "hide"
        const val ACTION_TEST = "test"
        const val ACTION_SILENT = "silent"
        const val ACTION_SILENT_OFF = "silent_off"
        @Volatile var instance: ChibiService? = null

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, ChibiService::class.java)
            action?.let { i.action = it }
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ChibiService::class.java))

        /** Build overlay actor info for a member (or the family's AI chibi). */
        fun actorFor(familyId: String, from: String?): ActorInfo? {
            val ref = Graph.famRef(familyId) ?: return null
            val fam = ref.family
            val isAI = from == null || from == "ai"
            val m = fam.member(if (isAI) "ai" else from) ?: return null
            val avatar = m.avatar.ifBlank { "builtin_AvatarSample_C" }
            return ActorInfo(
                id = if (isAI) "ai_$familyId" else m.user_id,
                avatarUrl = ref.session.avatarUrl(avatar),
                name = m.label, color = m.color,
                pitch = (m.voice_pitch.toFloat() * if (isAI) 1.15f else 1f).coerceIn(0.6f, 1.8f),
                ai = isAI,
            )
        }
    }
}
