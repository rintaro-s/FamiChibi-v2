package com.daijobu.famichibi.data

import android.content.Context
import com.daijobu.famichibi.net.JsonCfg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class HostAccount(
    val url: String,
    val token: String,
    val userId: String,
    val userName: String,
    val hostName: String = "",
    val alts: List<String> = emptyList(),   // other URLs of the same server (public / LAN), used for failover
)

@Serializable
data class Settings(
    val overlay: Boolean = true,          // resident chibi on top of other apps
    val resident: Boolean = true,         // stay on screen after delivering (false: walk away)
    val tts: Boolean = true,              // read messages aloud
    val ttsRate: Float = 1.0f,
    val fontScale: Float = 1.0f,          // bubble & app text size
    val bigMode: Boolean = false,         // grandparents mode: huge UI, auto read aloud
    val lockscreen: Boolean = true,       // show chibis on the lock screen for unread / urgent
    val chibiSize: Float = 1.0f,
    val vibrate: Boolean = true,
    val quickReplies: List<String> = listOf("了解！", "ありがとう", "今向かってる", "あとで連絡する", "👍"),
    val onboarded: Boolean = false,
    val sharePresence: Boolean = true,
    val silentUntil: Long = 0,            // サイレントモード（epoch ms、0=オフ、Long.MAX=解除するまで）
    val silentAllowUrgent: Boolean = true, // サイレント中でも「急ぎ」の連絡は届ける
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("famichibi", Context.MODE_PRIVATE)

    private val _accounts = MutableStateFlow(load<List<HostAccount>>("accounts") ?: emptyList())
    val accounts: StateFlow<List<HostAccount>> = _accounts.asStateFlow()

    private val _settings = MutableStateFlow(load<Settings>("settings") ?: Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    var lastFamilyId: String?
        get() = sp.getString("lastFamily", null)
        set(v) = sp.edit().putString("lastFamily", v).apply()

    private inline fun <reified T> load(key: String): T? =
        sp.getString(key, null)?.let { runCatching { JsonCfg.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> save(key: String, v: T) = sp.edit().putString(key, JsonCfg.encodeToString(v)).apply()

    fun upsertAccount(a: HostAccount) {
        val list = _accounts.value.filterNot { it.url == a.url || it.token == a.token } + a
        _accounts.value = list
        save("accounts", list)
    }

    fun removeAccount(url: String) {
        val list = _accounts.value.filterNot { it.url == url }
        _accounts.value = list
        save("accounts", list)
    }

    fun updateSettings(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        _settings.value = s
        save("settings", s)
    }
}
