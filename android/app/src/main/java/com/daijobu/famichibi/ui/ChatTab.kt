package com.daijobu.famichibi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.daijobu.famichibi.FamRef
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.chibi.Recorder
import com.daijobu.famichibi.net.Ask
import com.daijobu.famichibi.net.Family
import com.daijobu.famichibi.net.Message
import com.daijobu.famichibi.net.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Composable
fun ChatTab(ref: FamRef, channelId: String, onChannel: (String) -> Unit, openVoice: (String) -> Unit) {
    val fam = ref.family
    val s = ref.session
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by s.messages(channelId).collectAsState()
    val asks by s.asks.collectAsState()
    val voice by s.voice.collectAsState()
    val typing by s.typing.collectAsState()
    val settings by Graph.prefs.settings.collectAsState()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    val channel = fam.channels.firstOrNull { it.id == channelId }
    val isAiChannel = channel?.name == "ちびAI"
    val recorder = remember { Recorder(ctx) }
    var recording by remember { mutableStateOf(false) }

    LaunchedEffect(channelId) {
        if (channelId.isBlank()) return@LaunchedEffect
        runCatching { s.loadMessages(channelId) }
        s.markRead(fam.id, channelId)
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
            s.markRead(fam.id, channelId)
        }
    }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it }
    }

    fun send(t: String) {
        val body = t.trim()
        if (body.isEmpty()) return
        text = ""
        scope.launch { runCatching { s.sendMessage(channelId, body) }.onFailure { toast(ctx, errText(it)); text = body } }
    }

    Column(Modifier.fillMaxSize()) {
        // channels
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fam.textChannels.forEach { c ->
                val unread = fam.unread[c.id] ?: 0
                FilterChip(selected = c.id == channelId, onClick = { onChannel(c.id) },
                    label = { Text((if (c.name == "ちびAI") "🤖 " else "# ") + c.name + if (unread > 0 && c.id != channelId) "  $unread" else "") })
            }
            fam.voiceChannels.forEach { c ->
                val n = voice[c.id]?.size ?: 0
                AssistChip(onClick = { openVoice(c.id) }, label = { Text("🔊 ${c.name}" + if (n > 0) "（${n}人）" else "") })
            }
        }
        // AI questions waiting for me
        asks.filter { it.family_id == fam.id }.forEach { a -> AskCard(fam, a) { ans -> scope.launch { runCatching { s.answerAsk(a.id, ans) } } } }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (messages.isEmpty()) item {
                EmptyHint(if (isAiChannel) "🤖" else "💬", if (isAiChannel) "ちびAIに話しかけてみよう\n「土曜みんな空いてる？」「見逃した連絡まとめて」「何ができる？」" else "まだメッセージはありません")
            }
            items(messages, key = { it.id }) { m -> MessageItem(fam, m, mine = m.user_id == s.userId, me = s.userId) }
            typing[channelId]?.let { (uid, at) ->
                if (System.currentTimeMillis() - at < 6000) item {
                    Text("${fam.member(uid)?.label ?: "だれか"}が入力中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // quick replies: smart suggestions for the latest message + my own templates
        val last = messages.lastOrNull { it.user_id != s.userId && !it.isAI }
        val quick = ((last?.analysis?.replies ?: emptyList()) + settings.quickReplies).distinct().take(8)
        if (!isAiChannel && quick.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quick.forEach { q -> AssistChip(onClick = { send(q) }, label = { Text(q) }) }
            }
        }
        if (isAiChannel) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOfNotNull(if (ref.family.iot_enabled) "電気消して" else null, "今度の土曜日、みんな空いてる？", "見逃した連絡まとめて", "何ができる？").forEach { q -> AssistChip(onClick = { send(q) }, label = { Text(q) }) }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        micPerm.launch(Manifest.permission.RECORD_AUDIO); return@IconButton
                    }
                    if (!recording) { recording = recorder.start() }
                    else {
                        recording = false
                        val (file, dur) = recorder.stop() ?: return@IconButton
                        scope.launch {
                            runCatching { s.api.upload("/api/channels/$channelId/voice", file, "audio/mp4", mapOf("duration" to dur.toString())) }
                                .onFailure { toast(ctx, errText(it)) }
                        }
                    }
                }) {
                    Icon(if (recording) Icons.Default.Stop else Icons.Default.KeyboardVoice, "ボイスメッセージ", tint = if (recording) Urgent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextField(
                    value = text, onValueChange = {
                        text = it
                        s.send(kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("typing")); put("channel_id", kotlinx.serialization.json.JsonPrimitive(channelId)) })
                    },
                    placeholder = { Text(if (recording) "録音中… もう一度押すと送信" else if (isAiChannel) "ちびAIにお願い（例: 土曜みんな空いてる？）" else "メッセージ") },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), maxLines = 4,
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    trailingIcon = {
                        IconButton(onClick = {
                            dictate.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                        }) { Icon(Icons.Default.RecordVoiceOver, "音声入力") }
                    },
                )
                Spacer(Modifier.size(6.dp))
                FilledIconButton(onClick = { send(text) }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "送信") }
            }
        }
    }
}

@Composable
private fun AskCard(fam: Family, a: Ask, answer: (String) -> Unit) {
    Surface(color = AiSoft, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        .border(1.5.dp, AiBlue, RoundedCornerShape(18.dp))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RowCenter { AvatarDot(fam.ai, 28.dp); Text("${fam.ai_name}からの質問", fontWeight = FontWeight.Bold, color = Color(0xFF0B3A57)) }
            Text("${fam.member(a.requester_id)?.label ?: "家族"}が知りたがってるよ：${a.question}", color = Color(0xFF0B3A57))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { a.options.forEach { o -> OutlinedButton(onClick = { answer(o) }) { Text(o) } } }
        }
    }
}

@Composable
fun MessageItem(fam: Family, m: Message, mine: Boolean, me: String) {
    val sender = fam.member(m.user_id)
    val color = if (m.isAI) AiBlue else parseColor(sender?.color)
    var why by remember { mutableStateOf(false) }
    if (m.kind == "system") {
        Text(m.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!mine) { AvatarDot(sender ?: fam.ai, 34.dp); Spacer(Modifier.size(8.dp)) }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.fillMaxWidth(0.84f)) {
            if (!mine) RowCenter {
                Text(sender?.label ?: fam.ai_name, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
                if (m.isAI) Pill("AI", AiBlue)
                if (m.isUrgent) Pill("急ぎ", Urgent)
            }
            val bg = when {
                mine -> MaterialTheme.colorScheme.primaryContainer
                m.isAI -> AiSoft
                else -> MaterialTheme.colorScheme.surface
            }
            Surface(
                color = bg, shape = RoundedCornerShape(topStart = if (mine) 18.dp else 4.dp, topEnd = if (mine) 4.dp else 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                modifier = if (m.isUrgent && !mine) Modifier.border(2.dp, Urgent, RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)) else Modifier,
                shadowElevation = if (mine) 0.dp else 1.dp,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (m.kind == "voice") VoiceRow(fam, m) else Text(m.text, color = if (m.isAI) Color(0xFF0B3A57) else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.08f,
                            lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.55f))
                    if (m.isAI && (m.analysis.reasons.isNotEmpty())) {
                        Text(if (why) "▼ AIの判断根拠" else "▶ なぜ？（AIの判断根拠）", style = MaterialTheme.typography.labelSmall, color = AiBlue,
                            modifier = Modifier.padding(top = 4.dp).clickable { why = !why })
                        AnimatedVisibility(why) {
                            Column(Modifier.padding(top = 4.dp)) {
                                m.analysis.reasons.forEach { r -> Text("・$r", style = MaterialTheme.typography.labelSmall, color = Color(0xFF3B5B70)) }
                                (m.meta["citations"] as? JsonArray)?.forEach { c ->
                                    val o = c.jsonObject
                                    Text("📄 ${o.str("when")} ${o.str("who")}「${o.str("text")}」", style = MaterialTheme.typography.labelSmall, color = Color(0xFF3B5B70))
                                }
                            }
                        }
                    } else if (!mine && !m.isAI && m.analysis.reasons.isNotEmpty() && m.isUrgent) {
                        Text(m.analysis.reasons.last(), style = MaterialTheme.typography.labelSmall, color = Urgent, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            val seen = m.acks.filter { it != me && it != m.user_id }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(fmtTime(m.created_at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (mine && seen.isNotEmpty()) Text("見た: " + seen.mapNotNull { fam.member(it)?.label }.joinToString("・"), style = MaterialTheme.typography.labelSmall, color = Mint)
            }
        }
    }
}

@Composable
private fun VoiceRow(fam: Family, m: Message) {
    var playing by remember { mutableStateOf(false) }
    val session = Graph.famRef(fam.id)?.session
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
        val url = session?.api?.url(m.media) ?: return@clickable
        if (playing) { Graph.speech.stopVoice(); playing = false }
        else { playing = true; Graph.speech.playVoice(url, {}, { playing = false }, force = true) }
    }) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null, tint = Color.White)
        }
        Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 6.dp))
        val dur = (m.meta["duration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        Text(if (dur != null && dur > 0) "${dur.toInt()}秒" else "ボイス", style = MaterialTheme.typography.bodySmall)
    }
}
