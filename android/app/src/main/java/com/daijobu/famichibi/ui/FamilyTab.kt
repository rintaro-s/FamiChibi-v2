package com.daijobu.famichibi.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daijobu.famichibi.FamRef
import com.daijobu.famichibi.NfcWriteActivity
import com.daijobu.famichibi.net.Family
import com.daijobu.famichibi.net.JsonCfg
import com.daijobu.famichibi.net.bool
import com.daijobu.famichibi.net.num
import com.daijobu.famichibi.net.obj
import com.daijobu.famichibi.net.str
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Composable
fun FamilyTab(ref: FamRef) {
    val fam = ref.family
    val s = ref.session
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = fam.members.firstOrNull { it.user_id == s.userId }
    val isOwner = me?.role == "owner"

    fun patchFamily(body: JsonObject) = scope.launch {
        runCatching { s.upsertFamily(s.api.decode<Family>(s.api.patch("/api/families/${fam.id}", body)["family"])) }.onFailure { toast(ctx, errText(it)) }
    }
    fun patchMe(body: JsonObject) = scope.launch {
        runCatching { s.upsertFamily(s.api.decode<Family>(s.api.patch("/api/families/${fam.id}/me", body)["family"])) }.onFailure { toast(ctx, errText(it)) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---------------------------------------------------------------- members
        item {
            SectionCard("👨‍👩‍👧 メンバー") {
                fam.members.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarDot(m, 40.dp, online = m.online)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(m.label + if (m.user_id == s.userId) "（あなた）" else "", fontWeight = FontWeight.Bold)
                            Text(listOfNotNull(m.name.takeIf { it != m.label }, if (m.role == "owner") "オーナー" else null,
                                if (m.calendar_private) "🔒カレンダー非公開" else null, m.birthday.takeIf { it.isNotBlank() }?.let { "🎂$it" }).joinToString("・"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (m.user_id != s.userId) {
                            TextButton(onClick = { poke(ref, m.user_id, "poke") }) { Text("つんつん") }
                            TextButton(onClick = { poke(ref, m.user_id, "pat") }) { Text("なでなで") }
                        }
                    }
                }
                Text("つんつん・なでなでは相手のスマホのちびっこに届きます", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // ---------------------------------------------------------------- presence & watching over
        item { PresenceSection(ref) }
        // ---------------------------------------------------------------- invites / NFC
        item { InviteSection(ref) }
        // ---------------------------------------------------------------- birthday messages
        item { BirthdaySection(ref) }
        // ---------------------------------------------------------------- my membership
        item {
            SectionCard("🙋 この家族でのわたし") {
                var nick by remember(me?.nickname) { mutableStateOf(me?.nickname.orEmpty()) }
                var qs by remember(me?.quiet_start) { mutableStateOf(me?.quiet_start.orEmpty()) }
                var qe by remember(me?.quiet_end) { mutableStateOf(me?.quiet_end.orEmpty()) }
                OutlinedTextField(nick, { nick = it.take(8) }, label = { Text("呼ばれ方") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("カレンダーを非公開にする")
                        Text("AIが予定を聞かれたとき、推測せずに直接あなたに聞きに来ます", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(me?.calendar_private == true, { patchMe(buildJsonObject { put("calendar_private", it) }) })
                }
                Text("おやすみ時間（この時間は急ぎ以外そっと届けます）", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(qs, { qs = it.take(5) }, label = { Text("開始 23:00") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(qe, { qe = it.take(5) }, label = { Text("終了 07:00") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                FilledTonalButton(onClick = { patchMe(buildJsonObject { put("nickname", nick); put("quiet_start", qs); put("quiet_end", qe) }) }) { Text("保存") }
            }
        }
        // ---------------------------------------------------------------- AI / IoT (owner)
        item { AiSection(ref, isOwner, ::patchFamily) }
        // ---------------------------------------------------------------- demo director
        item { DemoSection(ref) }
    }
}

private fun poke(ref: FamRef, to: String, kind: String) {
    com.daijobu.famichibi.Graph.scope.launch {
        runCatching { ref.session.api.post("/api/families/${ref.family.id}/poke", buildJsonObject { put("to", to); put("kind", kind) }) }
    }
}

fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

@Composable
private fun InviteSection(ref: FamRef) {
    val fam = ref.family
    val s = ref.session
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("") }
    var presetNick by remember { mutableStateOf("") }
    val codeLink = "famichibi://join?host=${s.api.base}&code=${fam.code}"
    SectionCard("💌 家族を招待") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("招待コード", style = MaterialTheme.typography.labelMedium)
                Text(fam.code, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "FamiChibiの「${fam.name}」に参加してね！\nコード: ${fam.code}\n$codeLink"), "招待を送る"))
            }) { Icon(Icons.Default.Share, null); Text(" 送る") }
        }
        HorizontalDivider()
        Text("おじいちゃん・おばあちゃん用 NFCカード", fontWeight = FontWeight.Bold)
        Text("名前を入れてカードに書き込むと、カードをスマホにかざすだけでアカウント作成から参加まで完了します（入力ゼロ）。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(presetName, { presetName = it.take(12) }, label = { Text("名前") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(presetNick, { presetNick = it.take(8) }, label = { Text("呼ばれ方") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    runCatching {
                        val r = s.api.post("/api/families/${fam.id}/invites", buildJsonObject { put("preset_name", presetName); put("preset_nickname", presetNick) })
                        link = r.str("link")
                    }.onFailure { toast(ctx, errText(it)) }
                }
            }) { Text("招待を作る") }
            if (link != null) FilledTonalButton(onClick = {
                ctx.startActivity(Intent(ctx, NfcWriteActivity::class.java).putExtra("uri", link).putExtra("label", presetName.ifBlank { fam.name }))
            }) { Icon(Icons.Default.Nfc, null); Text(" NFCに書く") }
        }
        link?.let { l ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val bmp = remember(l) { qrBitmap(l) }
                    Image(bmp.asImageBitmap(), "招待QR", Modifier.size(200.dp))
                    Text("QRをカメラで読むか、NFCカードをかざすと参加できます", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun BirthdaySection(ref: FamRef) {
    val fam = ref.family
    val s = ref.session
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val targets = fam.members.filter { it.user_id != s.userId && it.birthday.isNotBlank() }
    if (targets.isEmpty()) return
    var msg by remember { mutableStateOf("") }
    var who by remember { mutableStateOf(targets.first().user_id) }
    SectionCard("🎂 誕生日の寄せ書き") {
        Text("書いたメッセージは誕生日の0時に届きます。家族全員のちびっこが集合してお祝いするよ！", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            targets.forEach { m -> androidx.compose.material3.FilterChip(who == m.user_id, { who = m.user_id }, label = { Text("${m.label}（${m.birthday}）") }) }
        }
        OutlinedTextField(msg, { msg = it }, placeholder = { Text("お誕生日おめでとう！") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = msg.isNotBlank(), onClick = {
            scope.launch {
                runCatching {
                    val r = s.api.form("/api/families/${fam.id}/birthday/$who", mapOf("text" to msg))
                    toast(ctx, "${r.str("deliver_at")} に届けます🎁"); msg = ""
                }.onFailure { toast(ctx, errText(it)) }
            }
        }) { Text("こっそり予約") }
    }
}

@Composable
private fun AiSection(ref: FamRef, isOwner: Boolean, patch: (JsonObject) -> Unit) {
    val fam = ref.family
    val s = ref.session
    var status by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(fam.id, fam.settings, fam.iot_enabled) { status = runCatching { s.api.get("/api/families/${fam.id}/ai/status") }.getOrNull() }
    var aiName by remember(fam.ai_name) { mutableStateOf(fam.ai_name) }
    var url by remember(fam.settings) { mutableStateOf(fam.settings.str("ollama_url").orEmpty()) }
    var model by remember(fam.settings) { mutableStateOf(fam.settings.str("model").orEmpty()) }
    var mcp by remember(fam.settings) { mutableStateOf(fam.settings["mcp_servers"]?.toString() ?: "[]") }
    var showAvatar by remember { mutableStateOf(false) }
    SectionCard("🤖 AIちびっこ・IoT") {
        status?.let { st ->
            val llm = st.bool("llm") == true
            Surface(color = if (llm) AiSoft else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(if (llm) "判断エンジン: LLM（${st.str("model")}）" else "判断エンジン: ルールエンジン（LLM未接続でも動作）", fontWeight = FontWeight.Bold)
                    val tools = (st["mcp_tools"] as? JsonArray)?.map { it.jsonObject.str("server") + "." + it.jsonObject.str("name") } ?: emptyList()
                    Text("MCPツール: " + tools.joinToString(", ").ifBlank { "なし" }, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!isOwner) { Text("設定の変更はオーナーのみできます", style = MaterialTheme.typography.labelSmall); return@SectionCard }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("AIちびっこ", Modifier.weight(1f)); Switch(fam.ai_enabled, { patch(buildJsonObject { put("ai_enabled", it) }) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("IoT連携（家電操作）", Modifier.weight(1f)); Switch(fam.iot_enabled, { patch(buildJsonObject { put("iot_enabled", it) }) }) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LLMを使う（オフならルールのみ）", Modifier.weight(1f))
            Switch(fam.settings.bool("llm_enabled") != false, { v -> patch(buildJsonObject { put("settings", buildJsonObject { put("llm_enabled", v) }) }) })
        }
        OutlinedTextField(aiName, { aiName = it.take(10) }, label = { Text("AIちびっこの名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("Ollama URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("モデル（例: qwen3.5:9b）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mcp, { mcp = it }, label = { Text("MCPサーバー（JSON）") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            supportingText = { Text("例: [{\"name\":\"ha\",\"type\":\"http\",\"url\":\"http://homeassistant.local:8086/mcp\"}]") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                val servers = runCatching { JsonCfg.parseToJsonElement(mcp) }.getOrNull()
                patch(buildJsonObject {
                    put("ai_name", aiName)
                    put("settings", buildJsonObject { put("ollama_url", url); put("model", model); servers?.let { put("mcp_servers", it) } })
                })
            }) { Text("保存") }
            OutlinedButton(onClick = { showAvatar = !showAvatar }) { Text("見た目を変える") }
        }
        if (showAvatar) AvatarPicker(s.api, fam.ai_avatar, "#5ec8ff", onSelect = { patch(buildJsonObject { put("ai_avatar", it.id) }) }, previewHeight = 220)
    }
}

@Composable
private fun DemoSection(ref: FamRef) {
    val fam = ref.family
    val s = ref.session
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scenarios by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        scenarios = runCatching { s.api.get("/api/demo/scenarios").obj("scenarios")!!.entries.map { it.key to (it.value as JsonPrimitive).content } }.getOrDefault(emptyList())
    }
    SectionCard("🎬 デモ（発表用）") {
        TextButton(onClick = { open = !open }) { Text(if (open) "閉じる" else "シナリオを表示") }
        if (open) {
            Text("ボタンを押すと、デモ家族のメンバーから連絡が届きます。ホーム画面に戻ると、ちびっこが歩いてきます。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            scenarios.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (k, label) ->
                        AssistChip(onClick = {
                            scope.launch {
                                runCatching { s.api.post("/api/families/${fam.id}/demo/$k") }.onFailure { toast(ctx, errText(it)) }
                            }
                        }, label = { Text(label, maxLines = 2) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}


@Composable
private fun PresenceSection(ref: FamRef) {
    val fam = ref.family
    val s = ref.session
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = com.daijobu.famichibi.Graph.prefs.settings.collectAsState().value
    var timeline by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    LaunchedEffect(fam.id, fam.members.joinToString { it.presence.toString() }) {
        timeline = runCatching { (s.api.get("/api/families/${fam.id}/timeline")["timeline"] as JsonArray).map { it.jsonObject } }.getOrDefault(emptyList())
    }
    SectionCard("🌼 けはい・見守り") {
        Text("カメラも位置情報も使わず、「家のWi-Fiにいるか」「スマホを使っているか」だけで家族の様子をリビングに映します。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自分のけはいを共有する")
                Text("帰宅すると家族のスマホで「ただいま」が届きます", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(settings.sharePresence, { v ->
                com.daijobu.famichibi.Graph.prefs.updateSettings { it.copy(sharePresence = v) }
                scope.launch { runCatching { s.api.patch("/api/families/${fam.id}/me", buildJsonObject { put("share_presence", v) }) } }
            })
        }
        HorizontalDivider()
        Text("見守り（おじいちゃん・おばあちゃん向け）", fontWeight = FontWeight.Bold)
        fam.members.filter { it.user_id != s.userId }.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarDot(m, 30.dp)
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text("${m.label} ${presenceIcon(m)}")
                    Text(presenceText(m), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(m.watch, { v ->
                    scope.launch {
                        runCatching { s.upsertFamily(s.api.decode(s.api.patch("/api/families/${fam.id}/members/${m.user_id}/watch", buildJsonObject { put("watch", v) })["family"])) }
                            .onFailure { toast(ctx, errText(it)) }
                    }
                })
            }
        }
        Text("見守り中の人には毎朝9時にちびAIが「元気？」とあいさつに行き、返事を家族に伝えます。日中4時間スマホに触れていない・電池が少ない時もそっとお知らせ。見守りは本人にも表示されます。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (timeline.isNotEmpty()) {
            HorizontalDivider()
            Text("最近のけはい", fontWeight = FontWeight.Bold)
            timeline.take(6).forEach { t ->
                val at = t.num("at") ?: 0.0
                Text("${fmtTime(at)}  ${t.str("text").orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
