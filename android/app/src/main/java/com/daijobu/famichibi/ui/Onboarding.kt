package com.daijobu.famichibi.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.data.HostAccount
import com.daijobu.famichibi.net.Api
import com.daijobu.famichibi.net.Discovery
import com.daijobu.famichibi.net.Family
import com.daijobu.famichibi.net.HostInfo
import com.daijobu.famichibi.net.User
import com.daijobu.famichibi.net.str
import com.daijobu.famichibi.service.ChibiService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pending join request (from deep link / NFC / QR) consumed by onboarding or the home screen. */
data class JoinRequest(val host: String, val code: String? = null, val invite: String? = null)

@Composable
fun Onboarding(join: JoinRequest?, startAt: Int = 0, onDone: (String?) -> Unit) {
    var step by remember { mutableIntStateOf(startAt) }
    var host by remember { mutableStateOf<HostInfo?>(null) }
    var familyId by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // One-tap join from an NFC card / invite link: no typing at all (for grandparents).
    var pending by remember { mutableStateOf(join) }
    LaunchedEffect(join) { if (join != null) pending = join }
    LaunchedEffect(pending) {
        val j = pending ?: return@LaunchedEffect
        if (j.invite != null) {
            runCatching {
                val api = Api(j.host)
                val r = api.post("/api/invites/${j.invite}/claim", buildJsonObject { })
                val u: User = api.decode(r["user"])
                val info = Discovery.probe(j.host)
                Graph.addAccount(HostAccount(api.base, r.str("token")!!, u.id, u.name, info?.name.orEmpty()))
                val fam: Family = api.decode(r["family"])
                familyId = fam.id
                step = 4
            }.onFailure { toast(ctx, "招待を使えませんでした: ${errText(it)}"); host = Discovery.probe(j.host); if (host != null) step = 2 }
        } else {
            host = Discovery.probe(j.host)
            if (host != null) step = 2
        }
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)))
            .safeDrawingPadding().imePadding()
    ) {
        AnimatedContent(targetState = step, label = "step") { s ->
            when (s) {
                0 -> Welcome { step = 1 }
                1 -> ConnectHost(onHost = { h -> host = h; step = 2 }, onDemo = { h -> pending = JoinRequest(h.url, invite = h.demoInvite) })
                2 -> Profile(host!!) { step = 3 }
                3 -> FamilySetup(join) { fid -> familyId = fid; step = 4 }
                else -> if (startAt > 0) LaunchedEffect(Unit) { onDone(familyId) } else Permissions { onDone(familyId) }
            }
        }
    }
}

@Composable
private fun Welcome(next: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🏠", fontSize = 72.sp)
        Spacer(Modifier.height(12.dp))
        Text("FamiChibi", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("家族がいつでもそこにいる。", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(18.dp))
        Text("家族からの連絡を、その人の“ちびっこ”が\nスマホの画面まで歩いて届けにきます。",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(40.dp))
        Button(onClick = next, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("はじめる", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun ConnectHost(onHost: (HostInfo) -> Unit, onDemo: (HostInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<HostInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    val defaults = remember { com.daijobu.famichibi.BuildConfig.DEFAULT_HOSTS.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
    var url by remember { mutableStateOf(defaults.firstOrNull() ?: if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) "http://10.0.2.2:8000" else "http://192.168.") }
    var busy by remember { mutableStateOf(false) }
    // LAN discovery + the default (debug) hosts, probed in parallel
    fun scan() = scope.launch {
        scanning = true
        val probes = defaults.map { u -> async { Discovery.probe(u) } }
        val lan = Discovery.scan()
        found = (probes.awaitAll().filterNotNull() + lan).distinctBy { it.url }
        scanning = false
    }
    LaunchedEffect(Unit) { scan() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("おうちのサーバーにつなぐ", style = MaterialTheme.typography.headlineSmall)
        Text("家族のメッセージは、家のパソコンやラズパイで動く「ホストサーバー」に保存されます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionCard(if (defaults.isEmpty()) "同じWi-Fiで見つかったサーバー" else "見つかったサーバー（デバッグ既定ホスト含む）") {
            if (scanning) RowCenter { CircularProgressIndicator(Modifier.size(20.dp)); Text("さがしています…") }
            else if (found.isEmpty()) Text("見つかりませんでした", color = MaterialTheme.colorScheme.onSurfaceVariant)
            found.filter { it.demoInvite.isNotEmpty() }.take(1).forEach { h ->
                Button(onClick = { onDemo(h) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("🎬 デモルーム「${h.demoName}」にすぐ参加", style = MaterialTheme.typography.titleMedium)
                }
            }
            found.forEach { h ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { onHost(h) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(h.name, fontWeight = FontWeight.Bold); Text(h.url, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { scan() }) { Icon(Icons.Default.Wifi, null); Text(" もう一度さがす") }
        }
        SectionCard("URLを入力") {
            OutlinedTextField(url, { url = it }, label = { Text("例: http://192.168.1.10:8000") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    val h = Discovery.probe(url)
                    busy = false
                    if (h != null) onHost(h) else toast(ctx, "FamiChibiサーバーが見つかりません")
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "確認中…" else "接続する") }
        }
        Text("NFCカードや招待QRを持っている場合は、カードをかざす／QRのリンクを開くだけで参加できます。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Profile(host: HostInfo, next: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(host.url) { Api(host.url) }
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#ff8a65") }
    var birthday by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("あなたのちびっこ", style = MaterialTheme.typography.headlineSmall)
        Text("家族のスマホには、このちびっこがあなたの言葉を届けに行きます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AvatarPicker(api, avatar, color, onSelect = { avatar = it.id })
        OutlinedTextField(name, { name = it.take(20) }, label = { Text("なまえ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(birthday, { birthday = it.filter { c -> c.isDigit() || c == '-' }.take(5) }, label = { Text("誕生日 (MM-DD・任意)") },
            placeholder = { Text("08-15") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("誕生日の0時に家族のちびっこが集まってお祝いします🎂") })
        Button(enabled = name.isNotBlank() && !busy, onClick = {
            scope.launch {
                busy = true
                runCatching {
                    val r = api.post("/api/register", buildJsonObject {
                        put("name", name.trim()); put("avatar", avatar)
                        if (Regex("\\d{2}-\\d{2}").matches(birthday)) put("birthday", birthday)
                    })
                    val u: User = api.decode(r["user"])
                    Graph.addAccount(HostAccount(api.base, r.str("token")!!, u.id, u.name, host.name))
                    next()
                }.onFailure { toast(ctx, errText(it)) }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("これでOK") }
    }
}

@Composable
private fun FamilySetup(join: JoinRequest?, onFamily: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = Graph.sessions.value.lastOrNull() ?: return
    var code by remember { mutableStateOf(join?.code.orEmpty()) }
    var famName by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Family) = scope.launch {
        busy = true
        runCatching { val f = block(); session.upsertFamily(f); onFamily(f.id) }.onFailure { toast(ctx, errText(it)) }
        busy = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("家族サーバー", style = MaterialTheme.typography.headlineSmall)
        Text("家族サーバーは「家」みたいなもの。チャット・冷蔵庫メモ・カレンダーがここに集まります。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(nickname, { nickname = it.take(8) }, label = { Text("家族からの呼ばれ方（例: 兄・母）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SectionCard("招待コードで参加") {
            OutlinedTextField(code, { code = it.take(12) }, label = { Text("6文字のコード") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = normCode(code).length == 6 && !busy, onClick = {
                run { session.api.decode(session.api.post("/api/families/join", buildJsonObject { put("code", normCode(code)); put("nickname", nickname) })["family"]) }
            }, modifier = Modifier.fillMaxWidth()) { Text("参加する") }
        }
        SectionCard("新しく作る") {
            OutlinedTextField(famName, { famName = it.take(20) }, label = { Text("家族サーバーの名前（例: 鳥羽家）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = famName.isNotBlank() && !busy, onClick = {
                run { session.api.decode(session.api.post("/api/families", buildJsonObject { put("name", famName); put("nickname", nickname) })["family"]) }
            }, modifier = Modifier.fillMaxWidth()) { Text("作成する") }
        }
        TextButton(enabled = !busy, onClick = { run { session.api.decode(session.api.post("/api/demo/seed")["family"]) } }, modifier = Modifier.fillMaxWidth()) {
            Text("🎬 デモ家族（ママ・妹・パパ）で試す")
        }
    }
}

@Composable
private fun Permissions(done: () -> Unit) {
    val ctx = LocalContext.current
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var notifOk by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) }
    var micOk by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    var actOk by remember { mutableStateOf(granted(Manifest.permission.ACTIVITY_RECOGNITION)) }
    val act = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { actOk = it }
    val overlay = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { overlayOk = Settings.canDrawOverlays(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("ちびっこが画面に来られるように", style = MaterialTheme.typography.headlineSmall)
        PermRow("他のアプリの上に表示", "ホーム画面や他のアプリを使っているときも、ちびっこが歩いてきます", overlayOk) {
            overlay.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        PermRow("通知", "ちびっこを見逃したときの保険。ロック画面にも表示します", notifOk) {
            if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermRow("マイク", "ボイスメッセージ・通話・ちびっこへの話しかけ・口パクに使います", micOk) {
            mic.launch(Manifest.permission.RECORD_AUDIO)
        }
        PermRow("身体の動き（歩数）", "歩いている間は家族のリビングで「移動中」と表示されます。位置情報は使いません", actOk) {
            act.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            Graph.prefs.updateSettings { it.copy(onboarded = true) }
            ChibiService.start(ctx)
            done()
        }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("はじめよう！") }
    }
}

@Composable
private fun PermRow(title: String, desc: String, ok: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable(enabled = !ok, onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ok) Icon(Icons.Default.CheckCircle, null, tint = Mint) else Text("許可", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}
