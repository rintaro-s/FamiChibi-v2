package com.daijobu.famichibi.data

import android.util.Log
import com.daijobu.famichibi.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import com.daijobu.famichibi.net.JsonCfg
import java.io.File
import java.security.MessageDigest

/**
 * The app's local copy of server data (one folder per account), so every screen can show the last
 * known state immediately and keeps working without a connection. Writes are debounced and atomic
 * (write to a temp file, then rename), so a crash or a kill never leaves a half-written file.
 */
class DiskCache(private val dir: File, private val scope: CoroutineScope) {
    constructor(accountToken: String, scope: CoroutineScope) : this(File(Graph.app.filesDir, "cache/" + sha(accountToken).take(16)), scope)

    init { dir.mkdirs() }
    private val pending = HashMap<String, Job>()

    fun <T> read(name: String, serializer: KSerializer<T>): T? = runCatching {
        val f = file(name)
        if (f.exists()) JsonCfg.decodeFromString(serializer, f.readText()) else null
    }.onFailure { Log.w(TAG, "read $name: $it") }.getOrNull()

    /** Save [value] a moment later; repeated saves of the same name within the delay are merged. */
    fun <T> write(name: String, serializer: KSerializer<T>, value: T, delayMs: Long = 400) {
        synchronized(pending) {
            pending[name]?.cancel()
            pending[name] = scope.launch(Dispatchers.IO) {
                delay(delayMs)
                runCatching {
                    val tmp = File(dir, "$name.tmp")
                    tmp.writeText(JsonCfg.encodeToString(serializer, value))
                    if (!tmp.renameTo(file(name))) { file(name).delete(); tmp.renameTo(file(name)) }
                }.onFailure { Log.w(TAG, "write $name: $it") }
            }
        }
    }

    fun clear() { dir.deleteRecursively() }

    private fun file(name: String) = File(dir, "$name.json")

    companion object {
        private const val TAG = "DiskCache"
        fun sha(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
