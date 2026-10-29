package com.daijobu.famichibi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.external.ExternalChats

/** A family member someone from LINE can be linked to: "family id|member id" + how to show it. */
private data class LinkTarget(val key: String, val label: String, val names: Set<String>)

/**
 * Settings: "家族のLINEも、ちびっこが届ける". The family keeps using LINE; this phone turns messages
 * from linked family members into their chibis. On-device only.
 */
@Composable
fun ExternalChatsSection() {
    val ctx = LocalContext.current
    val s by Graph.prefs.settings.collectAsState()
    val fams by Graph.families.collectAsState()
    var access by remember { mutableStateOf(ExternalChats.hasAccess(ctx)) }
    // re-check after coming back from the system's "notification access" screen
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { access = ExternalChats.hasAccess(ctx); ExternalChats.ensureBound(ctx) } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val targets = fams.flatMap { r ->
        r.family.members.filter { it.user_id != r.session.userId }.map { m ->
            LinkTarget("${r.family.id}|${m.user_id}", "${r.family.emoji} ${m.label}" + if (fams.size > 1) "（${r.family.name}）" else "",
                setOf(m.name, m.nickname, m.label).filter { it.isNotBlank() }.toSet())
        }
    }

    SectionCard("💬 家族のLINEも、ちびっこが届ける") {
        Text("家族は今まで通りLINEやSMSを使うだけ。このスマホでは、その人のちびっこが歩いてきてメッセージを伝え、吹き出しのボタンでそのままLINEに返事できます。",
            style = MaterialTheme.typography.bodySmall)
        Surface(color = Color(0xFFE2F4FF), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("🔒 通知の中身はこのスマホの中だけで使います。保存も、家族サーバーを含むどこへの送信もしません。下で家族と結び付けた人以外の通知は無視します。",
                Modifier.padding(10.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF1F4E6B))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LINE・SMSの通知をちびっこで届ける", Modifier.weight(1f))
            Switch(s.externalChats, { on ->
                Graph.prefs.updateSettings { it.copy(externalChats = on) }
                if (on && !ExternalChats.hasAccess(ctx)) ctx.startActivity(ExternalChats.accessSettingsIntent()) else ExternalChats.ensureBound(ctx)
            })
        }
        if (s.externalChats && !access) {
            FilledTonalButton(onClick = { ctx.startActivity(ExternalChats.accessSettingsIntent()) }) { Text("「通知へのアクセス」を許可する") }
            Text("一覧から FamiChibi を選んで ON にしてください", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!s.externalChats) return@SectionCard

        Text("だれのちびっこにする？", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        if (s.externalSeen.isEmpty()) {
            Text("まだ通知が届いていません。家族からLINEが届くと、ここに名前が出ます。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        s.externalSeen.forEach { senderKey ->
            val (app, name) = senderKey.split("|", limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: senderKey) }
            val linked = s.externalLinks[senderKey]?.let { k -> targets.firstOrNull { it.key == k } }
            val suggestion = if (linked == null) targets.firstOrNull { t -> t.names.any { it == name } } else null
            var menu by remember(senderKey) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name)
                    Text(app, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (suggestion != null) AssistChip(onClick = { link(senderKey, suggestion.key) }, label = { Text("${suggestion.label} にする？") })
                Box {
                    AssistChip(onClick = { menu = true }, label = { Text(linked?.label ?: "届けない") })
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("届けない") }, onClick = { link(senderKey, null); menu = false })
                        targets.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { link(senderKey, t.key); menu = false }) }
                    }
                }
            }
        }
    }
}

private fun link(senderKey: String, target: String?) = Graph.prefs.updateSettings {
    it.copy(externalLinks = if (target == null) it.externalLinks - senderKey else it.externalLinks + (senderKey to target))
}
