package com.daijobu.famichibi.net

import android.util.Log
import com.daijobu.famichibi.data.HostAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min

/**
 * One logged-in account on one host: REST client + a self-healing WebSocket + in-memory state.
 * Everything UI/overlay related observes the StateFlows / the [events] stream.
 */
class HostSession(val account: HostAccount, private val scope: CoroutineScope) {
    val api = Api(account.url, account.token)
    val userId get() = account.userId

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _me = MutableStateFlow<User?>(null)
    val me: StateFlow<User?> = _me.asStateFlow()

    private val _families = MutableStateFlow<List<Family>>(emptyList())
    val families: StateFlow<List<Family>> = _families.asStateFlow()

    private val _home = MutableStateFlow<JsonObject?>(null)
    val home: StateFlow<JsonObject?> = _home.asStateFlow()

    private val _voice = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val voice: StateFlow<Map<String, List<String>>> = _voice.asStateFlow()

    private val _asks = MutableStateFlow<List<Ask>>(emptyList())
    val asks: StateFlow<List<Ask>> = _asks.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap()) // channel -> (user, time)
    val typing: StateFlow<Map<String, Pair<String, Long>>> = _typing.asStateFlow()

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    private val messageFlows = HashMap<String, MutableStateFlow<List<Message>>>()

    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempts = 0
    @Volatile private var closed = false

    fun family(id: String?): Family? = _families.value.firstOrNull { it.id == id }

    fun messages(channelId: String): StateFlow<List<Message>> =
        synchronized(messageFlows) { messageFlows.getOrPut(channelId) { MutableStateFlow(emptyList()) } }

    // ------------------------------------------------------------------ connection
    fun start() {
        closed = false
        connect()
    }

    fun stop() {
        closed = true
        reconnectJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        _connected.value = false
    }

    private fun connect() {
        if (closed) return
        val wsUrl = api.base.replaceFirst("http", "ws") + "/ws?token=" + account.token
        val req = Request.Builder().url(wsUrl).build()
        ws = Http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                _connected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = runCatching { JsonCfg.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                scope.launch {
                    runCatching { handle(ev) }.onFailure { Log.w(TAG, "event ${ev.str("type")}: $it") }
                    _events.emit(ev)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDrop(webSocket, code)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onDrop(webSocket, response?.code ?: -1)
        })
    }

    private fun onDrop(socket: WebSocket, code: Int) {
        if (socket !== ws) return
        _connected.value = false
        if (closed || code == 4401) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            attempts++
            delay(min(30_000L, 1000L * (1 shl min(attempts, 5))))
            connect()
        }
    }

    fun reconnectNow() {
        if (_connected.value || closed) return
        reconnectJob?.cancel()
        attempts = 0
        connect()
    }

    fun send(obj: JsonObject): Boolean = ws?.send(obj.toString()) ?: false

    // ------------------------------------------------------------------ event handling
    private fun handle(ev: JsonObject) {
        when (ev.str("type")) {
            "hello" -> {
                _me.value = JsonCfg.decodeFromJsonElement<User>(ev["user"]!!)
                _families.value = JsonCfg.decodeFromJsonElement(ev["families"]!!)
                _home.value = ev.obj("home")
                _asks.value = runCatching { JsonCfg.decodeFromJsonElement<List<Ask>>(ev["asks"]!!) }.getOrDefault(emptyList())
                _voice.value = ev.obj("voice")?.mapValues { (_, v) -> v.jsonArray.map { it.strOrNull().orEmpty() } } ?: emptyMap()
                // refresh open channels after a reconnect
                synchronized(messageFlows) { messageFlows.keys.toList() }.forEach { cid -> scope.launch { runCatching { loadMessages(cid) } } }
            }
            "message" -> {
                val m = JsonCfg.decodeFromJsonElement<Message>(ev["message"]!!)
                synchronized(messageFlows) { messageFlows[m.channel_id] }?.update { list -> if (list.any { it.id == m.id }) list else list + m }
                if (m.user_id != userId) bumpUnread(m.family_id, m.channel_id, 1)
                _typing.update { it - m.channel_id }
            }
            "ack" -> {
                val mid = ev.num("message_id")?.toLong() ?: return
                val uid = ev.str("user_id") ?: return
                synchronized(messageFlows) { messageFlows.values.toList() }.forEach { f ->
                    f.update { list -> list.map { if (it.id == mid && uid !in it.acks) it.copy(acks = it.acks + uid) else it } }
                }
            }
            "family" -> {
                val f = JsonCfg.decodeFromJsonElement<Family>(ev["family"]!!)
                _families.update { list ->
                    val old = list.firstOrNull { it.id == f.id }
                    val merged = f.copy(me = f.members.firstOrNull { it.user_id == userId } ?: old?.me, unread = old?.unread ?: f.unread)
                    if (old == null) list + merged else list.map { if (it.id == f.id) merged else it }
                }
            }
            "family_deleted" -> _families.update { l -> l.filterNot { it.id == ev.str("family_id") } }
            "presence" -> {
                val uid = ev.str("user_id") ?: return
                val on = ev.bool("online") ?: false
                val pr = ev["presence"]?.let { runCatching { JsonCfg.decodeFromJsonElement<MemberPresence>(it) }.getOrNull() }
                val fid = ev.str("family_id")
                _families.update { l -> l.map { f -> f.copy(members = f.members.map {
                    if (it.user_id == uid) it.copy(online = on, presence = if (pr != null && (fid == null || fid == f.id)) pr else it.presence) else it }) } }
            }
            "arrive", "leave" -> {
                val uid = ev.str("user_id") ?: return
                val fid = ev.str("family_id")
                val pr = ev["presence"]?.let { runCatching { JsonCfg.decodeFromJsonElement<MemberPresence>(it) }.getOrNull() } ?: return
                _families.update { l -> l.map { f -> if (f.id != fid) f else f.copy(members = f.members.map { if (it.user_id == uid) it.copy(presence = pr) else it }) } }
            }
            "voice_state" -> {
                val cid = ev.str("channel_id") ?: return
                val users = (ev["users"] as? JsonArray)?.map { it.strOrNull().orEmpty() } ?: emptyList()
                _voice.update { it + (cid to users) }
            }
            "home" -> _home.value = ev.obj("home")
            "ask" -> {
                val a = JsonCfg.decodeFromJsonElement<Ask>(ev["ask"]!!)
                _asks.update { l -> l.filterNot { it.id == a.id } + a }
            }
            "ask_answer" -> {
                val a = JsonCfg.decodeFromJsonElement<Ask>(ev["ask"]!!)
                _asks.update { l -> l.filterNot { it.id == a.id } }
            }
            "typing" -> {
                val cid = ev.str("channel_id") ?: return
                val uid = ev.str("user_id") ?: return
                _typing.update { it + (cid to (uid to System.currentTimeMillis())) }
            }
        }
    }

    private fun bumpUnread(fid: String, cid: String, n: Int) {
        _families.update { l -> l.map { f -> if (f.id == fid) f.copy(unread = f.unread + (cid to ((f.unread[cid] ?: 0) + n))) else f } }
    }

    // ------------------------------------------------------------------ REST helpers
    suspend fun refresh() {
        val r = api.get("/api/me")
        _me.value = api.decode(r["user"])
        _families.value = api.decode(r["families"])
    }

    suspend fun loadMessages(cid: String): List<Message> {
        val r = api.get("/api/channels/$cid/messages?limit=80")
        val list: List<Message> = api.decode(r["messages"])
        messages(cid)
        synchronized(messageFlows) { messageFlows[cid]!! }.value = list
        return list
    }

    suspend fun loadOlder(cid: String): Int {
        val cur = messages(cid).value
        val first = cur.firstOrNull()?.id ?: return 0
        val r = api.get("/api/channels/$cid/messages?limit=60&before=$first")
        val older: List<Message> = api.decode(r["messages"])
        synchronized(messageFlows) { messageFlows[cid]!! }.update { older + it }
        return older.size
    }

    suspend fun markRead(fid: String, cid: String) {
        _families.update { l -> l.map { f -> if (f.id == fid) f.copy(unread = f.unread + (cid to 0)) else f } }
        runCatching { api.post("/api/channels/$cid/read") }
    }

    suspend fun sendMessage(cid: String, text: String, replyTo: Long? = null): Message {
        val r = api.post("/api/channels/$cid/messages", buildJsonObject {
            put("text", text)
            replyTo?.let { put("reply_to", it) }
        })
        val m: Message = api.decode(r["message"])
        synchronized(messageFlows) { messageFlows[cid] }?.update { l -> if (l.any { it.id == m.id }) l else l + m }
        return m
    }

    suspend fun ack(messageId: Long) = runCatching { api.post("/api/messages/$messageId/ack") }

    suspend fun answerAsk(askId: String, answer: String) {
        api.post("/api/asks/$askId/answer", buildJsonObject { put("answer", answer) })
        _asks.update { l -> l.filterNot { it.id == askId } }
    }

    fun upsertFamily(f: Family) {
        _families.update { l -> if (l.any { it.id == f.id }) l.map { if (it.id == f.id) f else it } else l + f }
    }

    fun avatarUrl(avatarId: String?): String? = avatarId?.takeIf { it.isNotBlank() }?.let { api.url("/avatars/${avatarFile(it)}") }

    companion object {
        const val TAG = "HostSession"
        /** Avatar ids map to files: builtin_<stem> -> <stem>.vrm, av_xxx -> av_xxx.vrm */
        fun avatarFile(id: String) = if (id.startsWith("builtin_")) id.removePrefix("builtin_") + ".vrm" else "$id.vrm"
    }
}
