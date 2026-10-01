package com.daijobu.famichibi.chibi

import android.content.Context
import com.daijobu.famichibi.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Downloads VRM files from the host once and keeps them in app storage. The WebView loads them via
 * WebViewAssetLoader (https://appassets.androidplatform.net/avatars/<file>) so they work offline and
 * never trigger mixed-content / CORS problems.
 */
class AvatarCache(context: Context) {
    val dir = File(context.filesDir, "avatars").apply { mkdirs() }
    private val locks = HashMap<String, Mutex>()

    fun localFile(url: String): File = File(dir, fileName(url))

    fun fileName(url: String): String {
        val base = url.substringAfterLast('/').substringBefore('?')
        val host = url.substringAfter("://").substringBefore('/').replace(Regex("[^A-Za-z0-9]"), "_")
        return "${host}__$base"
    }

    /** Returns the WebView URL of a cached copy, downloading it if needed. */
    suspend fun ensure(url: String): String? = withContext(Dispatchers.IO) {
        val lock = synchronized(locks) { locks.getOrPut(url) { Mutex() } }
        lock.withLock {
            val f = localFile(url)
            if (!f.exists() || f.length() < 1024) {
                runCatching {
                    val tmp = File(dir, f.name + ".part")
                    Http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                        if (!res.isSuccessful) error("HTTP ${res.code}")
                        res.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    }
                    tmp.renameTo(f)
                }.onFailure { return@withContext null }
            }
            ChibiWeb.ORIGIN + "/avatars/" + f.name
        }
    }

    fun clear() = dir.listFiles()?.forEach { it.delete() }
}
