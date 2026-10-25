package com.daijobu.famichibi

import android.content.Context
import android.os.Build
import android.util.Log
import com.daijobu.famichibi.net.HostSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crashes are written to a file before the process dies and uploaded to the family server the next
 * time a connection is up (POST /api/client-logs → server's data/client-logs/), so a crash seen on a
 * tester's phone can be read afterwards instead of being guessed at.
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private lateinit var dir: File
    @Volatile private var uploading = false

    fun install(context: Context) {
        dir = File(context.filesDir, "crash").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write("crash", "thread=${thread.name}\n" + stack(e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Also usable for errors that were handled but should not have happened. */
    fun write(kind: String, text: String) {
        if (!::dir.isInitialized) return
        File(dir, "${System.currentTimeMillis()}-$kind.txt").writeText(text.take(60_000))
    }

    fun upload(session: HostSession) {
        if (!::dir.isInitialized || uploading) return
        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return
        uploading = true
        Graph.scope.launch {
            try {
                for (f in files) {
                    val kind = f.name.substringAfter('-').substringBefore('.')
                    val ok = runCatching {
                        session.api.post("/api/client-logs", buildJsonObject {
                            put("kind", kind); put("text", f.readText())
                            put("app_version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                            put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
                        })
                    }.isSuccess
                    if (ok) f.delete() else break
                }
            } catch (e: Exception) {
                Log.w(TAG, "upload: $e")
            } finally {
                uploading = false
            }
        }
    }

    private fun stack(e: Throwable) = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
}
