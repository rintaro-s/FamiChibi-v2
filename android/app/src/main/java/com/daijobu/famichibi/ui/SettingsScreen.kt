package com.daijobu.famichibi.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.LockScreenActivity
import com.daijobu.famichibi.data.Settings
import com.daijobu.famichibi.net.User
import com.daijobu.famichibi.service.ChibiService
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(back: () -> Unit, addHost: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Graph.prefs.settings.collectAsState()
    val sessions by Graph.sessions.collectAsState()
    val session = sessions.lastOrNull { it.connected.value } ?: sessions.firstOrNull()
    val me = session?.me?.collectAsState()?.value
    fun upd(f: (Settings) -> Settings) = Graph.prefs.updateSettings(f)

    Scaffold(topBar = {
        TopAppBar(title = { Text("設定") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard("🧸 ちびっこ") {
                    Toggle("画面に常駐させる", "他のアプリの上にちびっこを表示します", s.overlay) {
                        upd { st -> st.copy(overlay = it) }
                        if (it && !AndroidSettings.canDrawOverlays(ctx)) ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                    Toggle("届けたあとも画面の隅にいる", "オフにすると、届け終わったら歩いて帰ります", s.resident) { upd { st -> st.copy(resident = it) } }
                    Toggle("ロック画面にも表示", "急ぎの連絡や未読があると、ロック画面でちびっこが待っています", s.lockscreen) { upd { st -> st.copy(lockscreen = it) } }
                    Text("ちびっこの大きさ  ${"%.1f".format(s.chibiSize)}x")
                    Slider(s.chibiSize, { v -> upd { it.copy(chibiSize = v) } }, valueRange = 0.7f..1.5f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { ChibiService.start(ctx, ChibiService.ACTION_TEST) }) { Text("ちびっこを呼ぶ") }
                        OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, LockScreenActivity::class.java).putExtra("preview", true)) }) { Text("ロック画面を試す") }
                    }
                }
            }
            item {
                val silentOn by com.daijobu.famichibi.data.Silent.on.collectAsState()
                SectionCard("🌙 サイレントモード") {
                    Text("会議中・授業中・寝るときに。ちびっこは画面に出てこず、読み上げも通知音もなし。連絡は音なしの通知として残ります。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Toggle("サイレントモード", if (silentOn) com.daijobu.famichibi.data.Silent.label() else "オフ", silentOn) {
                        if (it) com.daijobu.famichibi.data.Silent.enable(60) else com.daijobu.famichibi.data.Silent.disable()
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.daijobu.famichibi.data.Silent.presets.forEach { (l, m) -> AssistChip(onClick = { com.daijobu.famichibi.data.Silent.enable(m) }, label = { Text(l) }) }
                        AssistChip(onClick = { com.daijobu.famichibi.data.Silent.enableUntilMorning() }, label = { Text("明日の朝まで") })
                    }
                    Toggle("急ぎの連絡だけは届ける", "「迎えに来て」「至急」などはサイレント中でもちびっこが知らせます", s.silentAllowUrgent) { upd { st -> st.copy(silentAllowUrgent = it) } }
                }
            }
            item {
                SectionCard("🔊 読み上げ・文字") {
                    Toggle("ちびっこがしゃべる（読み上げ）", "家族ごとに声の高さが変わります", s.tts) { upd { st -> st.copy(tts = it) } }
                    Text("話す速さ  ${"%.1f".format(s.ttsRate)}")
                    Slider(s.ttsRate, { v -> upd { it.copy(ttsRate = v) } }, valueRange = 0.6f..1.6f)
                    Text("文字の大きさ  ${"%.1f".format(s.fontScale)}")
                    Slider(s.fontScale, { v -> upd { it.copy(fontScale = v) } }, valueRange = 0.85f..1.6f)
                    Toggle("おじいちゃん・おばあちゃんモード", "文字とボタンを大きく、ゆっくり読み上げます", s.bigMode) { upd { st -> st.copy(bigMode = it, tts = if (it) true else st.tts) } }
                }
            }
            item {
                var newReply by remember { mutableStateOf("") }
                SectionCard("⚡ クイック返信（定型文）") {
                    Text("ちびっこの吹き出しや通知から、ワンタップで返せます", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.quickReplies.forEach { q ->
                            InputChip(selected = false, onClick = { upd { it.copy(quickReplies = it.quickReplies - q) } }, label = { Text(q) },
                                trailingIcon = { Icon(Icons.Default.Close, "削除") })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newReply, { newReply = it.take(20) }, placeholder = { Text("例: 今から帰るね") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(enabled = newReply.isNotBlank(), onClick = { upd { it.copy(quickReplies = (it.quickReplies + newReply.trim()).distinct()) }; newReply = "" }) { Text("追加") }
                    }
                }
            }
            if (session != null && me != null) item {
                var name by remember(me.name) { mutableStateOf(me.name) }
                var bday by remember(me.birthday) { mutableStateOf(me.birthday) }
                var pitch by remember(me.voice_pitch) { mutableStateOf(me.voice_pitch.toFloat()) }
                var avatar by remember(me.avatar) { mutableStateOf(me.avatar) }
                SectionCard("👤 プロフィール（${session.account.hostName.ifBlank { session.api.base }}）") {
                    AvatarPicker(session.api, avatar, me.color, onSelect = { avatar = it.id }, previewHeight = 220)
                    OutlinedTextField(name, { name = it.take(20) }, label = { Text("なまえ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(bday, { bday = it.take(5) }, label = { Text("誕生日 MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("ちびっこの声の高さ  ${"%.2f".format(pitch)}")
                    Slider(pitch, { pitch = it }, valueRange = 0.6f..1.8f, onValueChangeFinished = { Graph.speech.speak("こんにちは、わたしのちびっこだよ", pitch, force = true) })
                    FilledTonalButton(onClick = {
                        scope.launch {
                            runCatching {
                                session.api.patch("/api/me", buildJsonObject {
                                    put("name", name); put("avatar", avatar); put("voice_pitch", pitch.toDouble())
                                    if (Regex("\\d{2}-\\d{2}").matches(bday)) put("birthday", bday)
                                })
                                session.refresh(); toast(ctx, "保存しました")
                            }.onFailure { toast(ctx, errText(it)) }
                        }
                    }) { Text("保存") }
                }
            }
            item {
                SectionCard("🖥 ホストサーバー") {
                    sessions.forEach { hs ->
                        val on by hs.connected.collectAsState()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(hs.account.hostName.ifBlank { "ホスト" })
                                Text("${hs.api.base}・${if (on) "接続中" else "未接続"}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { Graph.prefs.removeAccount(hs.account.url); Graph.syncSessions() }) { Text("削除") }
                        }
                    }
                    AssistChip(onClick = addHost, label = { Text("ホストを追加") })
                }
            }
            item {
                Text("FamiChibi 1.0 — Team Daijobu（鳥羽商船高等専門学校）\nVRM描画: three.js / @pixiv/three-vrm (MIT)・アバター: VRoid Project サンプル",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun Toggle(title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}
