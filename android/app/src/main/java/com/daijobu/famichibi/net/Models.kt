package com.daijobu.famichibi.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

val JsonCfg = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

val EmptyJson = JsonObject(emptyMap())

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonElement?.strOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

@Serializable
data class User(
    val id: String,
    val name: String,
    val avatar: String = "",
    val color: String = "#ff8a65",
    val birthday: String = "",
    val voice_pitch: Double = 1.0,
)

@Serializable
data class MemberPresence(
    val state: String? = null,       // active | idle | moving | sleep
    val at_home: Boolean? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val since: Double? = null,
    val home_since: Double? = null,
    val last_active: Double? = null,
    val label: String = "",
)

@Serializable
data class Member(
    val user_id: String,
    val name: String = "",
    val nickname: String = "",
    val role: String = "member",
    val avatar: String = "",
    val color: String = "#ff8a65",
    val birthday: String = "",
    val voice_pitch: Double = 1.0,
    val calendar_private: Boolean = false,
    val quiet_start: String = "",
    val quiet_end: String = "",
    val online: Boolean = false,
    val ai: Boolean = false,
    val watch: Boolean = false,
    val share_presence: Boolean = true,
    val presence: MemberPresence = MemberPresence(),
) {
    val label: String get() = nickname.ifBlank { name }
}

@Serializable
data class Channel(
    val id: String,
    val family_id: String = "",
    val name: String,
    val kind: String = "text",
    val position: Int = 0,
)

@Serializable
data class Family(
    val id: String,
    val name: String,
    val emoji: String = "🏠",
    val code: String = "",
    val owner_id: String = "",
    val ai_enabled: Boolean = true,
    val iot_enabled: Boolean = true,
    val ai_name: String = "ちびAI",
    val ai_avatar: String = "",
    val members: List<Member> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val ai: Member? = null,
    val settings: JsonObject = EmptyJson,
    val me: Member? = null,
    val unread: Map<String, Int> = emptyMap(),
) {
    fun member(uid: String?): Member? = if (uid == null || uid == "ai") ai else members.firstOrNull { it.user_id == uid }
    val textChannels get() = channels.filter { it.kind == "text" }
    val voiceChannels get() = channels.filter { it.kind == "voice" }
    val aiChannel get() = channels.firstOrNull { it.name == "ちびAI" }
    val totalUnread get() = unread.values.sum()
}

@Serializable
data class Analysis(
    val emotion: String = "neutral",
    val motion: String = "talk",
    val fx: String? = null,
    val urgency: String = "normal",
    val replies: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
    val party: Boolean = false,
)

@Serializable
data class Message(
    val id: Long,
    val channel_id: String,
    val family_id: String,
    val user_id: String? = null,
    val kind: String = "text",
    val text: String = "",
    val media: String = "",
    val analysis: Analysis = Analysis(),
    val meta: JsonObject = EmptyJson,
    val reply_to: Long? = null,
    val created_at: Double = 0.0,
    val acks: List<String> = emptyList(),
) {
    val isAI get() = kind == "ai" || user_id == null
    val isUrgent get() = analysis.urgency == "high"
}

@Serializable
data class Notify(val mode: String = "now", val reason: String = "")

/** Instructions for a chibi to deliver something (server "chibi" objects and messages both map to this). */
@Serializable
data class ChibiCue(
    val from: String? = null,
    val text: String = "",
    val emotion: String = "neutral",
    val motion: String? = null,
    val fx: String? = null,
    val urgent: Boolean = false,
    val replies: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
    val duration: Long? = null,
    val media: String = "",
    // routing (filled on the client)
    val familyId: String = "",
    val channelId: String = "",
    val messageId: Long = 0,
    val askId: String = "",
    val kind: String = "message",
    val quiet: Boolean = false,
    val party: Boolean = false,
)

@Serializable
data class Memo(
    val id: String,
    val family_id: String,
    val author_id: String? = null,
    val text: String,
    val color: String = "#fff3a3",
    val notify_at: Double? = null,
    val target_id: String? = null,
    val emotion: String = "",
    val motion: String = "",
    val party: Int = 0,
    val media: String = "",
    val done: Int = 0,
    val delivered: Int = 0,
    val created_at: Double = 0.0,
    val notify_label: String = "",
    val parsed: String = "",
)

@Serializable
data class CalEvent(
    val id: String,
    val family_id: String,
    val user_id: String? = null,
    val title: String,
    val start: Double,
    val end: Double? = null,
    val all_day: Int = 0,
    val private: Int = 0,
    val note: String = "",
    val masked: Boolean = false,
)

@Serializable
data class FamTask(
    val id: String,
    val family_id: String,
    val title: String,
    val assignee_id: String? = null,
    val done: Int = 0,
    val due: Double? = null,
    val created_by: String? = null,
)

@Serializable
data class Ask(
    val id: String,
    val family_id: String,
    val channel_id: String? = null,
    val group_id: String = "",
    val requester_id: String? = null,
    val target_id: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val answer: String? = null,
    val status: String = "open",
)

@Serializable
data class Avatar(
    val id: String,
    val name: String,
    val url: String,
    val builtin: Boolean = false,
    val credit: String = "",
    val size: Long = 0,
)

@Serializable
data class HostInfo(
    val app: String = "", val name: String = "", val version: String = "", val url: String = "",
    val demoInvite: String = "", val demoName: String = "",   // 見に来た人をすぐ入れるデモルーム（サーバーが案内したときだけ）
)
