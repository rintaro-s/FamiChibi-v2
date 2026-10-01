package com.daijobu.famichibi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daijobu.famichibi.FamRef
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.net.Family
import com.daijobu.famichibi.net.Member
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One-glance "who is where": 母🏠 父📍 妹🚶 祖母💤 */
fun presenceIcon(m: Member): String = when {
    m.presence.state == "sleep" -> "💤"
    m.presence.at_home == false -> if (m.presence.state == "moving") "🚶" else "📍"
    m.presence.state == "moving" -> "🚶"
    m.presence.at_home == true -> "🏠"
    else -> ""
}

fun presenceText(m: Member): String {
    val p = m.presence
    val where = when (p.at_home) { true -> "家にいる"; false -> "おでかけ中" + (p.label.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""); null -> "" }
    val what = when (p.state) { "active" -> "スマホを使っている"; "moving" -> "移動中"; "sleep" -> "おやすみ中"; "idle" -> "ひと休み中"; else -> "" }
    return listOf(where, what).filter { it.isNotBlank() }.joinToString("・").ifBlank { "けはい情報なし" }
}

@Composable
fun PresenceStrip(fam: Family) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fam.members.forEach { m ->
            Text("${m.label}${presenceIcon(m)}", style = MaterialTheme.typography.labelSmall, color = parseColor(m.color))
        }
    }
}

private fun ago(ts: Double?): String {
    ts ?: return "—"
    val m = ((System.currentTimeMillis() / 1000.0 - ts) / 60).toInt()
    return when { m < 1 -> "たった今"; m < 60 -> "${m}分前"; m < 60 * 24 -> "${m / 60}時間前"; else -> "${m / 1440}日前" }
}

/** Tapping a chibi in the living room: how they are doing + a quick poke. */
@Composable
fun MemberCard(ref: FamRef, actorId: String, onDismiss: () -> Unit) {
    val fam = ref.family
    val m = if (actorId.startsWith("ai_")) fam.ai else fam.members.firstOrNull { it.user_id == actorId }
    if (m == null) { onDismiss(); return }
    val poke = { kind: String ->
        Graph.scope.launch { runCatching { ref.session.api.post("/api/families/${fam.id}/poke", buildJsonObject { put("to", m.user_id); put("kind", kind) }) } }
        onDismiss()
    }
    AlertDialog(onDismissRequest = onDismiss, title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarDot(m, 40.dp)
            Column(Modifier.padding(start = 10.dp)) {
                Text(m.label, fontWeight = FontWeight.Bold)
                if (!m.ai) Text("${presenceIcon(m)} ${presenceText(m)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, text = {
        if (m.ai) Text("家族のお手伝い係。「ちびAI」チャンネルで話しかけてね。")
        else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val p = m.presence
            Text("最後にスマホを触った：${ago(p.last_active)}", fontSize = 15.sp)
            p.battery?.let { Text("電池：$it%${if (p.charging == true) "（充電中）" else ""}", fontSize = 15.sp) }
            if (m.watch) Text("🌼 見守り中（毎朝のあいさつ・長時間の無操作をお知らせ）", fontSize = 13.sp, color = Mint)
        }
    }, confirmButton = {
        if (!m.ai && m.user_id != ref.session.userId) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { poke("poke") }) { Text("つんつん") }
            FilledTonalButton(onClick = { poke("pat") }) { Text("なでなで") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
}
