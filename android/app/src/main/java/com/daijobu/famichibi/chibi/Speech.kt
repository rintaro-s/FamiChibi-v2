package com.daijobu.famichibi.chibi

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * Voice output for chibis: TTS with a per-person pitch (so each family member's chibi sounds
 * different) and playback of recorded voice messages with real lip-sync from the audio amplitude.
 */
class Speech(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val callbacks = HashMap<String, Pair<() -> Unit, () -> Unit>>()
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.JAPANESE
            tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { callbacks[id]?.first?.let { main.post(it) } }
            override fun onDone(id: String) { callbacks.remove(id)?.second?.let { main.post(it) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { callbacks.remove(id)?.second?.let { main.post(it) } }
            override fun onStop(id: String, interrupted: Boolean) { callbacks.remove(id)?.second?.let { main.post(it) } }
        })
    }

    fun speak(text: String, pitch: Float = 1f, rate: Float = 1f, onStart: () -> Unit = {}, onDone: () -> Unit = {}, force: Boolean = false) {
        val clean = cleanForSpeech(text)
        if (!ready || clean.isBlank() || (!force && com.daijobu.famichibi.data.Silent.isOn())) { onDone(); return }
        val id = UUID.randomUUID().toString()
        callbacks[id] = onStart to onDone
        tts.setPitch(pitch.coerceIn(0.5f, 2f))
        tts.setSpeechRate(rate.coerceIn(0.5f, 2f))
        tts.speak(clean, TextToSpeech.QUEUE_ADD, Bundle(), id)
    }

    /** Plays a voice message and reports mouth openness (0..1) ~30 times per second. */
    fun playVoice(url: String, onLevel: (Float) -> Unit, onDone: () -> Unit, force: Boolean = false) {
        if (!force && com.daijobu.famichibi.data.Silent.isOn()) { onDone(); return }
        stopVoice()
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        mp.setOnPreparedListener {
            runCatching {
                visualizer = Visualizer(mp.audioSessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer, wave: ByteArray, rate: Int) {
                            var sum = 0
                            for (b in wave) sum += abs((b.toInt() and 0xff) - 128)
                            val level = min(1f, sum.toFloat() / wave.size / 38f)
                            main.post { onLevel(level) }
                        }
                        override fun onFftDataCapture(v: Visualizer, fft: ByteArray, rate: Int) {}
                    }, Visualizer.getMaxCaptureRate() / 2, true, false)
                    enabled = true
                }
            }
            mp.start()
        }
        mp.setOnCompletionListener { stopVoice(); onDone() }
        mp.setOnErrorListener { _, _, _ -> stopVoice(); onDone(); true }
        runCatching { mp.setDataSource(url); mp.prepareAsync() }.onFailure { stopVoice(); onDone() }
    }

    val hasVisualizer get() = visualizer != null

    fun stopVoice() {
        runCatching { visualizer?.release() }
        visualizer = null
        runCatching { player?.release() }
        player = null
    }

    fun stop() {
        tts.stop()
        stopVoice()
    }

    companion object {
        private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}]")
        fun cleanForSpeech(s: String) = s.replace(EMOJI, " ").replace(Regex("https?://\\S+"), "リンク").replace("w{2,}".toRegex(), "わら").trim()
    }
}
