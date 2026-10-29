package com.daijobu.famichibi.data

import com.daijobu.famichibi.Graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * サイレントモード（時間指定で自動的に解除）。2種類:
 *  - ちびっこもお休み: ちびっこは画面に出てこず、読み上げ・通知音もなし（通知は無音で残る）。
 *    急ぎの連絡だけは届けるかどうかを選べる。
 *  - 音だけオフ（silentShowChibi）: ちびっこはいつも通り歩いてきて吹き出しで伝えるが、
 *    読み上げ・ボイス再生・通知音・バイブは急ぎの連絡も含めて一切なし。
 */
object Silent {
    const val FOREVER = Long.MAX_VALUE

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun isOn(now: Long = System.currentTimeMillis()) = Graph.prefs.settings.value.silentUntil > now

    /** Keeps [on] in sync with the clock (auto-off when the time is up). */
    fun start() {
        Graph.scope.launch { Graph.prefs.settings.collect { _on.value = isOn() } }
        Graph.scope.launch {
            while (true) {
                _on.value = isOn()
                delay(15_000)
            }
        }
    }

    fun enable(minutes: Int?) {
        val until = when {
            minutes == null -> FOREVER
            else -> System.currentTimeMillis() + minutes * 60_000L
        }
        Graph.prefs.updateSettings { it.copy(silentUntil = until) }
        _on.value = true
    }

    /** Until 7:00 tomorrow morning. */
    fun enableUntilMorning() {
        val c = Calendar.getInstance().apply {
            if (get(Calendar.HOUR_OF_DAY) >= 7) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        Graph.prefs.updateSettings { it.copy(silentUntil = c.timeInMillis) }
        _on.value = true
    }

    fun disable() {
        Graph.prefs.updateSettings { it.copy(silentUntil = 0) }
        _on.value = false
    }

    /** "音だけオフ": chibis still come, but nothing makes a sound. */
    fun soundOnly(): Boolean = isOn() && Graph.prefs.settings.value.silentShowChibi

    /** Are the chibis off screen right now? */
    fun hidesChibi(): Boolean = isOn() && !Graph.prefs.settings.value.silentShowChibi

    /** Should this delivery be held back (no chibi, soundless notification)? Urgent ones can break through (setting). */
    fun mutes(urgent: Boolean): Boolean = hidesChibi() && !(urgent && Graph.prefs.settings.value.silentAllowUrgent)

    fun setShowChibi(show: Boolean) = Graph.prefs.updateSettings { it.copy(silentShowChibi = show) }

    fun label(): String {
        val u = Graph.prefs.settings.value.silentUntil
        if (u == FOREVER) return "オフにするまで"
        return SimpleDateFormat("H:mm", Locale.JAPAN).format(Date(u)) + "まで"
    }

    val presets = listOf("30分" to 30, "1時間" to 60, "3時間" to 180)
}
