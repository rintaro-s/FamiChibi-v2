package com.daijobu.famichibi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.daijobu.famichibi.FamRef
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.chibi.ChibiWeb
import com.daijobu.famichibi.net.ChibiCue
import com.daijobu.famichibi.net.JsonCfg
import com.daijobu.famichibi.net.Message
import com.daijobu.famichibi.net.Notify
import com.daijobu.famichibi.net.obj
import com.daijobu.famichibi.net.bool
import com.daijobu.famichibi.net.str
import com.daijobu.famichibi.service.ChibiService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/** Push every member's presence into the living room. The AI chibi always stays home. */
fun applyPresence(web: ChibiWeb, fam: com.daijobu.famichibi.net.Family, animate: Boolean) {
    for (m in fam.members) {
        val p = m.presence
        web.presence(m.user_id, p.at_home != false, p.state, p.label, animate)
    }
}

@Stable
class StageState {
    var web: ChibiWeb? by mutableStateOf(null)
    val events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 32)
}

/** Embeds the 3D chibi stage (transparent WebView) in Compose. */
@Composable
fun ChibiStage(state: StageState, query: String, modifier: Modifier = Modifier, cacheKey: String? = null) {
    val ctx = LocalContext.current
    val web = remember(query, cacheKey) {
        if (cacheKey != null) com.daijobu.famichibi.chibi.StageCache.obtain(cacheKey, query) else ChibiWeb(ctx, query)
    }
    val token = remember { Any() }
    DisposableEffect(web) {
        web.owner = token
        web.onEvent = { state.events.tryEmit(it) }
        web.pause(false)
        state.web = web
        onDispose {
            state.web = null
            // When the layout changes (rotation), the new owner may already hold the stage: leave it alone.
            if (web.owner === token) {
                web.onEvent = null
                web.owner = null
                if (cacheKey != null) com.daijobu.famichibi.chibi.StageCache.park(web) else web.destroy()
            }
        }
    }
    AndroidView(factory = { (web.parent as? android.view.ViewGroup)?.removeView(web); web.owner = token; web.pause(false); web }, modifier = modifier)
}

/**
 * The family's "living room": every member's chibi (and the AI chibi) stands in a row. Messages for
 * this family are delivered here while the app is open (the overlay steps back meanwhile).
 */
@Composable
fun LivingRoom(ref: FamRef, modifier: Modifier = Modifier, onReply: (ChibiCue, String) -> Unit, onTapActor: (String) -> Unit = {}) {
    val state = remember { StageState() }
    val fam = ref.family
    val settings = Graph.prefs.settings.value
    Box(modifier) {
        ChibiStage(state, "layout=room&tex=512&fraction=0.58&fps=30&font=${settings.fontScale}", Modifier.fillMaxSize(), cacheKey = "room:${fam.id}")
    }
    val memberKey = fam.members.joinToString { it.user_id + it.avatar } + fam.ai_avatar + fam.ai_enabled
    LaunchedEffect(state.web, memberKey) {
        val web = state.web ?: return@LaunchedEffect
        if (web.castKey == memberKey) return@LaunchedEffect // cached stage: everyone is already there
        val actors = fam.members.mapNotNull { ChibiService.actorFor(fam.id, it.user_id) } +
            listOfNotNull(if (fam.ai_enabled) ChibiService.actorFor(fam.id, "ai") else null)
        web.room(actors.size)
        // members who left the family (or changed id) disappear from a cached stage too
        web.js("(()=>{const keep=${org.json.JSONArray(actors.map { it.id })};for(const id of Chibi.ids()) if(!keep.includes(id)) Chibi.remove(id);})()")
        // presence first, so members who are out never appear in the room
        applyPresence(web, Graph.famRef(fam.id)?.family ?: fam, animate = false)
        coroutineScope {
            actors.map { a -> async { a to a.avatarUrl?.let { Graph.avatars.ensure(it) } } }.awaitAll()
        }.forEach { (a, local) -> if (local != null) web.add(a.id, local, a.name, a.color, a.ai) }
        web.arrangeRow(actors.map { it.id })
        web.castKey = memberKey
    }
    // Presence changes (asleep, moving, ...) and the real living-room light (IoT) are reflected in the room.
    val presenceKey = fam.members.joinToString { it.user_id + it.presence.state + it.presence.at_home + it.presence.label }
    LaunchedEffect(state.web, presenceKey) { state.web?.let { applyPresence(it, fam, animate = false) } }
    val home by ref.session.home.collectAsState()
    LaunchedEffect(state.web, home) {
        val on = (home?.obj("devices")?.obj("living_light")?.bool("on")) ?: return@LaunchedEffect
        state.web?.lights(on)
    }
    // Bubble / tap events from the stage
    val cues = remember { HashMap<String, ChibiCue>() }
    LaunchedEffect(state) {
        state.events.collect { ev ->
            when (ev.str("type")) {
                "reply" -> cues[ev.str("id")]?.let { onReply(it, ev.str("text").orEmpty()) }
                "tapActor" -> {
                    val id = ev.str("id") ?: return@collect
                    state.web?.deliver(id, null, if (ev.str("part") == "head") "happy" else "surprised",
                        if (ev.str("part") == "head") "bothWave" else "jump", if (ev.str("part") == "head") "hearts" else "exclaim")
                    onTapActor(id)
                }
            }
        }
    }
    // Live family events -> chibis
    LaunchedEffect(ref.session, fam.id, state.web) {
        val web = state.web ?: return@LaunchedEffect
        ref.session.events.collect { ev ->
            if (ev.str("family_id") != fam.id) return@collect
            val cur = Graph.famRef(fam.id)?.family ?: fam
            when (ev.str("type")) {
                "arrive", "leave" -> {
                    val uid = ev.str("user_id") ?: return@collect
                    val arrive = ev.str("type") == "arrive"
                    val p = ev.obj("presence")
                    web.presence(uid, arrive, p?.str("state"), p?.str("label").orEmpty(), animate = true,
                        text = if (arrive) "ただいま！" else "いってきます！")
                    if (Graph.prefs.settings.value.tts) {
                        val a = ChibiService.actorFor(fam.id, uid)
                        Graph.speech.speak((a?.name ?: "") + if (arrive) "、おかえり！" else "、いってらっしゃい！", 1.15f)
                    }
                }
                "message" -> {
                    val m = JsonCfg.decodeFromJsonElement<Message>(ev["message"]!!)
                    val plan = ev["notify"]?.let { JsonCfg.decodeFromJsonElement<Notify>(it) } ?: Notify()
                    val id = if (m.isAI) "ai_${fam.id}" else m.user_id!!
                    val mine = m.user_id == ref.session.userId
                    val cue = ChibiCue(from = m.user_id ?: "ai", text = m.text, familyId = fam.id, channelId = m.channel_id, messageId = m.id)
                    cues[id] = cue
                    web.deliver(id, m.text, m.analysis.emotion, m.analysis.motion, m.analysis.fx, plan.mode == "urgent",
                        if (mine || m.isAI) emptyList() else m.analysis.replies.take(3), m.analysis.reasons, ref = m.id.toString(),
                        audio = m.media.isNotBlank())
                    if (!mine && Graph.prefs.settings.value.tts && plan.mode != "quiet") {
                        val a = ChibiService.actorFor(fam.id, m.user_id ?: "ai")
                        if (m.media.isNotBlank()) {
                            web.speaking(id, true)
                            Graph.speech.playVoice(ref.session.api.url(m.media), { lv -> web.mouth(id, lv) }, { web.speaking(id, false) })
                        } else Graph.speech.speak(m.text, a?.pitch ?: 1f, Graph.prefs.settings.value.ttsRate,
                            onStart = { web.speaking(id, true) }, onDone = { web.speaking(id, false) })
                    }
                }
                "memo_due", "ask", "nudge", "poke" -> {
                    val c = ev.obj("chibi") ?: return@collect
                    val cue = JsonCfg.decodeFromJsonElement<ChibiCue>(c).copy(familyId = fam.id, kind = ev.str("type")!!,
                        askId = ev.obj("ask")?.str("id").orEmpty())
                    val id = if (cue.from == null || cue.from == "ai") "ai_${fam.id}" else cue.from
                    cues[id] = cue
                    web.deliver(id, cue.text, cue.emotion, cue.motion, cue.fx, cue.urgent, cue.replies, cue.reasons)
                    if (Graph.prefs.settings.value.tts) {
                        val a = ChibiService.actorFor(fam.id, cue.from)
                        Graph.speech.speak(cue.text, a?.pitch ?: 1f, onStart = { web.speaking(id, true) }, onDone = { web.speaking(id, false) })
                    }
                }
                "party" -> {
                    web.party()
                    val title = ev.str("title") ?: "🎉"
                    val first = cur.members.firstOrNull()?.user_id
                    (ev["messages"] as? JsonArray)?.forEachIndexed { i, e ->
                        val o = e.jsonObject
                        val from = o.str("from") ?: return@forEachIndexed
                        web.js("setTimeout(()=>Chibi.say(${JSONObject.quote(from)}, {text:${JSONObject.quote(o.str("text").orEmpty())}, duration:5200}), ${2500 + i * 5500})")
                    }
                    if (first != null) web.say("ai_${fam.id}", title, JSONObject().put("duration", 3000))
                }
            }
        }
    }
}
