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

private val INVITE_EXPIRY = listOf("1時間" to 1.0, "24時間" to 24.0, "7日間" to 168.0, "30日間" to 720.0, "期限なし" to 0.0)

@Composable
private fun InviteSection(ref: FamRef) {
    val fam = ref.family
    val s = ref.session
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<JsonObject?>(null) }
    var presetName by remember { mutableStateOf("") }
    var presetNick by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf(168.0) }
    var busy by remember { mutableStateOf(false) }

    fun create(personal: Boolean, then: (JsonObject) -> Unit = {}) {
        busy = true
        scope.launch {
            runCatching {
                s.api.post("/api/families/${fam.id}/invites", buildJsonObject {
                    if (personal) { put("preset_name", presetName.trim()); put("preset_nickname", presetNick.trim()) }
                    put("expires_hours", hours)
                })
            }.onSuccess { invite = it; then(it) }.onFailure { toast(ctx, errText(it)) }
            busy = false
        }
    }
    fun untilText(inv: JsonObject): String {
        val exp = (inv["expires_at"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        val t = exp?.let { java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.JAPAN).format(java.util.Date((it * 1000).toLong())) + " まで有効" } ?: "期限なし"
        return if (inv.str("single_use") == "true") "$t・1回だけ" else t
    }
    fun share(inv: JsonObject) {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "FamiChibiの「${fam.name}」に招待します！\nリンクを開いて「アプリで参加」か「ブラウザでチャット」を押してね。\n${inv.str("url")}\n（${untilText(inv)}）"), "招待を送る"))
    }

    SectionCard("💌 家族を招待") {
        Text("LINEやメールで送れる招待リンクを作ります。開くと招待ページが表示され、アプリでもブラウザでも参加できます。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("有効期限", style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            INVITE_EXPIRY.forEach { (l, h) -> androidx.compose.material3.FilterChip(hours == h, { hours = h }, label = { Text(l) }) }
        }
        Button(enabled = !busy, onClick = { create(false) { share(it) } }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Share, null); Text(" 招待リンクを送る")
        }
        invite?.let { inv ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val url = inv.str("url").orEmpty()
                    val bmp = remember(url) { qrBitmap(url) }
                    Image(bmp.asImageBitmap(), "招待QR", Modifier.size(180.dp))
                    Text(url, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    Text(untilText(inv), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { share(inv) }) { Text("送る") }
                        TextButton(onClick = {
                            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("招待リンク", url)); toast(ctx, "コピーしました")
                        }) { Text("コピー") }
                        if (inv.str("single_use") == "true") FilledTonalButton(onClick = {
                            ctx.startActivity(Intent(ctx, NfcWriteActivity::class.java).putExtra("uri", inv.str("link")).putExtra("label", presetName.ifBlank { fam.name }))
                        }) { Icon(Icons.Default.Nfc, null); Text(" NFCに書く") }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("招待コード", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(fam.code, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider()
        Text("おじいちゃん・おばあちゃん用（その人専用・NFCカード）", fontWeight = FontWeight.Bold)
        Text("名前を入れて作ると、その人専用の招待になります（1回だけ使える）。NFCカードに書き込めば、かざすだけでアカウント作成から参加まで完了します。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(presetName, { presetName = it.take(12) }, label = { Text("名前") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(presetNick, { presetNick = it.take(8) }, label = { Text("呼ばれ方") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedButton(enabled = !busy && (presetName.isNotBlank() || presetNick.isNotBlank()), onClick = { create(true) }) { Text("専用の招待を作る") }
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
    val llmOn = fam.settings.bool("llm_enabled") == true

    // The AI chibi is the family's helper: it connects people (watching over, asking for you, digests,
    // reminders). It is not the product's centre — that is the family and their chibis.
    SectionCard("🤖 ちびAI（家族のお手伝い係）") {
        Text("毎朝の見守りのあいさつ、本人に代わりに聞く、見逃した連絡のまとめ、リマインド。家族と家族の間をつなぐ係です。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!isOwner) { Text("設定の変更はオーナーのみできます", style = MaterialTheme.typography.labelSmall); return@SectionCard }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("ちびAIをつかう", Modifier.weight(1f)); Switch(fam.ai_enabled, { patch(buildJsonObject { put("ai_enabled", it) }) }) }
        if (fam.ai_enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(aiName, { aiName = it.take(10) }, label = { Text("名前") }, singleLine = true, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { patch(buildJsonObject { put("ai_name", aiName) }) }) { Text("保存") }
            }
            OutlinedButton(onClick = { showAvatar = !showAvatar }) { Text("見た目を変える") }
            if (showAvatar) AvatarPicker(s.api, fam.ai_avatar, "#5ec8ff", onSelect = { patch(buildJsonObject { put("ai_avatar", it.id) }) }, previewHeight = 220)
        }
    }

    // Optional extensions: off for a new family, for households that want them.
    SectionCard("🧩 拡張機能（オプション）") {
        Text("FamiChibiの中心は「家族とちびっこ」。ここから先は、必要な家庭だけがONにする追加機能です。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!isOwner) { Text("設定の変更はオーナーのみできます", style = MaterialTheme.typography.labelSmall); return@SectionCard }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("💡 家電操作")
                Text("「おうち」タブが増え、ちびAIに「電気消して」と頼めます（Home Assistant などに MCP で接続）", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(fam.iot_enabled, { patch(buildJsonObject { put("iot_enabled", it) }) })
        }
        if (fam.iot_enabled) {
            OutlinedTextField(mcp, { mcp = it }, label = { Text("つなぐ先（MCPサーバー・JSON）") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                supportingText = { Text("初期値は試せる「仮想の家」。例: [{\"name\":\"ha\",\"type\":\"http\",\"url\":\"http://homeassistant.local:8086/mcp\"}]") })
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("💬 ちびAIと雑談（LLM）")
                Text("決まった言い方以外の自由な会話にも答えます。家のPCなどで動く Ollama が必要です", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(llmOn, { v -> patch(buildJsonObject { put("settings", buildJsonObject { put("llm_enabled", v) }) }) })
        }
        if (llmOn) {
            status?.let { st ->
                val ok = st.bool("llm") == true
                Text(if (ok) "✅ つながっています（${st.str("model")}）" else "⚠️ まだつながっていません。URLとモデルを確認してください（つながるまでは決まった言い方だけに答えます）",
                    style = MaterialTheme.typography.labelSmall, color = if (ok) MaterialTheme.colorScheme.primary else Urgent)
            }
            OutlinedTextField(url, { url = it }, label = { Text("Ollama URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("モデル（例: qwen3.5:9b）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (fam.iot_enabled || llmOn) FilledTonalButton(onClick = {
            val servers = runCatching { JsonCfg.parseToJsonElement(mcp) }.getOrNull()
            patch(buildJsonObject { put("settings", buildJsonObject { put("ollama_url", url); put("model", model); servers?.let { put("mcp_servers", it) } }) })
        }) { Text("拡張機能の設定を保存") }
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
