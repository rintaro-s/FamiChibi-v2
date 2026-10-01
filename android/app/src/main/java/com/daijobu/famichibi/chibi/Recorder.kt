package com.daijobu.famichibi.chibi

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** Records short voice messages (AAC in .m4a). */
class Recorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): Boolean = runCatching {
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder(ctx)
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64000)
        r.setAudioSamplingRate(44100)
        r.setMaxDuration(60_000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        rec = r; file = f; startedAt = System.currentTimeMillis()
        true
    }.getOrDefault(false)

    /** Returns the file and its duration in seconds, or null if nothing usable was recorded. */
    fun stop(): Pair<File, Double>? {
        val r = rec ?: return null
        rec = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val dur = (System.currentTimeMillis() - startedAt) / 1000.0
        val f = file ?: return null
        return if (ok && dur >= 0.6) f to dur else null
    }
}
