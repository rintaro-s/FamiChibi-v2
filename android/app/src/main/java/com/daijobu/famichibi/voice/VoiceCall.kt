package com.daijobu.famichibi.voice

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.net.HostSession
import com.daijobu.famichibi.net.obj
import com.daijobu.famichibi.net.str
import com.daijobu.famichibi.net.num
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Family voice room: WebRTC audio mesh (small groups). Signaling over the family WebSocket:
 *   {type:'voice_join', channel_id} / {type:'voice_leave'}
 *   {type:'rtc', to, channel_id, data:{kind:'offer'|'answer'|'ice', sdp?, candidate?:{candidate,sdpMid,sdpMLineIndex}}}
 * The peer with the smaller user id creates the offer (same rule as the web client).
 */
class VoiceCall private constructor(private val ctx: Context, val session: HostSession, val familyId: String, val channelId: String) {
    private val me = session.userId
    private val peers = HashMap<String, PeerConnection>()
    private val pendingIce = HashMap<String, MutableList<IceCandidate>>()
    private var job: Job? = null
    private var statsJob: Job? = null
    private val audio = ctx.getSystemService(AudioManager::class.java)

    private val _levels = MutableStateFlow<Map<String, Float>>(emptyMap())
    val levels: StateFlow<Map<String, Float>> = _levels.asStateFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()

    private val factory: PeerConnectionFactory by lazy { Factory.get(ctx) }
    private val localTrack: AudioTrack by lazy {
        val src = factory.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        })
        factory.createAudioTrack("mic", src)
    }

    private fun start() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = true
        job = Graph.scope.launch {
            session.events.collect { ev ->
                when (ev.str("type")) {
                    "voice_state" -> if (ev.str("channel_id") == channelId) onRoster(ev)
                    "rtc" -> if (ev.str("channel_id") == channelId || ev.str("channel_id") == null) onSignal(ev.str("from") ?: return@collect, ev.obj("data") ?: return@collect)
                }
            }
        }
        session.send(buildJsonObject { put("type", "voice_join"); put("channel_id", channelId) })
        statsJob = Graph.scope.launch {
            while (isActive) { delay(160); pollLevels() }
        }
    }

    fun toggleMute() {
        _muted.value = !_muted.value
        localTrack.setEnabled(!_muted.value)
    }

    fun leave() {
        session.send(buildJsonObject { put("type", "voice_leave") })
        job?.cancel(); statsJob?.cancel()
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        audio.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        if (current === this) current = null
        _current.value = null
    }

    private fun onRoster(ev: JsonObject) {
        val users = (ev["users"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return
        // drop peers that left
        peers.keys.filter { it !in users }.forEach { uid -> peers.remove(uid)?.close(); _connected.update { it - uid } }
        for (uid in users) {
            if (uid == me || peers.containsKey(uid)) continue
            if (me < uid) {
                val pc = peer(uid)
                pc.createOffer(sdp { offer ->
                    pc.setLocalDescription(sdp { send(uid, "offer", offer.description) }, offer)
                }, MediaConstraints())
            }
        }
    }

    private fun onSignal(from: String, data: JsonObject) {
        when (data.str("kind")) {
            "offer" -> {
                val pc = peers[from] ?: peer(from)
                pc.setRemoteDescription(sdp {
                    flushIce(from, pc)
                    pc.createAnswer(sdp { ans -> pc.setLocalDescription(sdp { send(from, "answer", ans.description) }, ans) }, MediaConstraints())
                }, SessionDescription(SessionDescription.Type.OFFER, data.str("sdp")))
            }
            "answer" -> peers[from]?.let { pc -> pc.setRemoteDescription(sdp { flushIce(from, pc) }, SessionDescription(SessionDescription.Type.ANSWER, data.str("sdp"))) }
            "ice" -> {
                val c = data.obj("candidate") ?: return
                val cand = IceCandidate(c.str("sdpMid"), c.num("sdpMLineIndex")?.toInt() ?: 0, c.str("candidate"))
                val pc = peers[from]
                if (pc?.remoteDescription != null) pc.addIceCandidate(cand) else pendingIce.getOrPut(from) { mutableListOf() }.add(cand)
            }
        }
    }

    private fun flushIce(uid: String, pc: PeerConnection) {
        pendingIce.remove(uid)?.forEach { pc.addIceCandidate(it) }
    }

    private fun send(to: String, kind: String, sdp: String? = null, cand: IceCandidate? = null) {
        session.send(buildJsonObject {
            put("type", "rtc"); put("to", to); put("channel_id", channelId)
            putJsonObject("data") {
                put("kind", kind)
                sdp?.let { put("sdp", it) }
                cand?.let { c -> putJsonObject("candidate") { put("candidate", c.sdp); put("sdpMid", c.sdpMid); put("sdpMLineIndex", c.sdpMLineIndex) } }
            }
        })
    }

    private fun peer(uid: String): PeerConnection {
        val cfg = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = send(uid, "ice", cand = c)
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ice $uid $s")
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> _connected.update { it + uid }
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED, PeerConnection.IceConnectionState.DISCONNECTED -> _connected.update { it - uid }
                    else -> {}
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) { (r.track() as? AudioTrack)?.setEnabled(true) }
        })!!
        pc.addTrack(localTrack, listOf("family"))
        peers[uid] = pc
        return pc
    }

    /** Audio levels (0..1) per user for the chibis' mouths. */
    private fun pollLevels() {
        val out = HashMap<String, Float>()
        val pcs = peers.toMap()
        var pending = pcs.size
        if (pending == 0) { _levels.value = emptyMap(); return }
        for ((uid, pc) in pcs) {
            pc.getStats { report ->
                for (st in report.statsMap.values) {
                    val lvl = (st.members["audioLevel"] as? Number)?.toFloat() ?: continue
                    if (st.type == "inbound-rtp") out[uid] = lvl
                    if (st.type == "media-source") out[me] = maxOf(out[me] ?: 0f, if (_muted.value) 0f else lvl)
                }
                synchronized(out) { pending--; if (pending == 0) _levels.value = out.toMap() }
            }
        }
    }

    private fun sdp(onOk: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) = onOk(d)
        override fun onSetSuccess() = onOk(SessionDescription(SessionDescription.Type.OFFER, ""))
        override fun onCreateFailure(e: String?) { Log.w(TAG, "sdp create: $e") }
        override fun onSetFailure(e: String?) { Log.w(TAG, "sdp set: $e") }
    }

    private object Factory {
        private var f: PeerConnectionFactory? = null
        fun get(ctx: Context): PeerConnectionFactory = f ?: run {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
            val adm = JavaAudioDeviceModule.builder(ctx.applicationContext).setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory().also { f = it }
        }
    }

    companion object {
        const val TAG = "VoiceCall"
        @Volatile var current: VoiceCall? = null
            private set
        private val _current = MutableStateFlow<VoiceCall?>(null)
        val active: StateFlow<VoiceCall?> = _current.asStateFlow()

        fun join(ctx: Context, session: HostSession, familyId: String, channelId: String): VoiceCall {
            current?.let { if (it.channelId == channelId) return it; it.leave() }
            return VoiceCall(ctx.applicationContext, session, familyId, channelId).also { current = it; _current.value = it; it.start() }
        }
    }
}
