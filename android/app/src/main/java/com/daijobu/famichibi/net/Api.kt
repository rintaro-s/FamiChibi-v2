package com.daijobu.famichibi.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

val Http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

private val JSON = "application/json; charset=utf-8".toMediaType()

/** Thin REST client for one host. */
class Api(baseUrl: String, var token: String? = null) {
    val base: String = normalizeBase(baseUrl)

    fun url(path: String) = if (path.startsWith("http")) path else base + path

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(url(path))
        token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    private suspend fun exec(req: Request): JsonObject = withContext(Dispatchers.IO) {
        Http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val detail = runCatching { JsonCfg.parseToJsonElement(body).jsonObject.str("detail") }.getOrNull()
                throw ApiException(res.code, detail ?: "HTTP ${res.code}")
            }
            if (body.isBlank()) EmptyJson else JsonCfg.parseToJsonElement(body).jsonObject
        }
    }

    suspend fun get(path: String): JsonObject = exec(request(path).get().build())
    suspend fun post(path: String, body: JsonElement? = null): JsonObject =
        exec(request(path).post((body?.toString() ?: "{}").toRequestBody(JSON)).build())
    suspend fun patch(path: String, body: JsonElement): JsonObject =
        exec(request(path).patch(body.toString().toRequestBody(JSON)).build())
    suspend fun delete(path: String): JsonObject = exec(request(path).delete().build())

    /** A queued write: [idempotencyKey] lets the server recognise a retry and replay the first result. */
    suspend fun send(method: String, path: String, body: JsonElement?, idempotencyKey: String): JsonObject {
        val b = request(path).header("Idempotency-Key", idempotencyKey)
        val rb = (body?.toString() ?: "{}").toRequestBody(JSON)
        return exec(when (method) { "POST" -> b.post(rb); "PATCH" -> b.patch(rb); "DELETE" -> b.delete(); else -> error("method $method") }.build())
    }

    suspend fun upload(path: String, file: File, mime: String, fields: Map<String, String> = emptyMap(), field: String = "file"): JsonObject {
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> mb.addFormDataPart(k, v) }
        mb.addFormDataPart(field, file.name, file.asRequestBody(mime.toMediaType()))
        return exec(request(path).post(mb.build()).build())
    }

    suspend fun form(path: String, fields: Map<String, String>): JsonObject {
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> mb.addFormDataPart(k, v) }
        return exec(request(path).post(mb.build() as RequestBody).build())
    }

    inline fun <reified T> decode(e: JsonElement?): T = JsonCfg.decodeFromJsonElement(e ?: EmptyJson)

    companion object {
        fun normalizeBase(u: String): String {
            var s = u.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            if (Regex("^https?://[^/:]+$").matches(s)) s += ":8000"
            return s
        }
    }
}
