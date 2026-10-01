package com.daijobu.famichibi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daijobu.famichibi.FamRef
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.net.Family
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(openFamily: (String) -> Unit, openSettings: () -> Unit, addHost: () -> Unit) {
    val fams by Graph.families.collectAsState()
    val sessions by Graph.sessions.collectAsState()
    val me = (sessions.lastOrNull { it.connected.value } ?: sessions.firstOrNull())?.me?.collectAsState()?.value
    var dialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("おかえりなさい", style = MaterialTheme.typography.titleLarge)
                        if (me != null) Text(me.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = { SilentButton(); IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "設定") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { dialog = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("家族サーバー") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sessions.forEach { s ->
                item(key = "off" + s.account.url) {
                    val online by s.connected.collectAsState()
                    if (!online) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { s.reconnectNow() }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudOff, null)
                            Text("  ${s.account.hostName.ifBlank { s.account.url }} に接続中…（タップで再接続）", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item(key = "silent") { SilentBanner() }
            if (fams.isEmpty()) item { EmptyHint("🏡", "まだ家族サーバーがありません。\n右下の「家族サーバー」から作成・参加しましょう") }
            items(fams, key = { it.family.id }) { ref -> FamilyCard(ref) { openFamily(ref.family.id) } }
        }
    }
    if (dialog) JoinOrCreateDialog(onDismiss = { dialog = false }, onDone = { dialog = false; openFamily(it) }, addHost = { dialog = false; addHost() })
}

@Composable
private fun FamilyCard(ref: FamRef, onClick: () -> Unit) {
    val f = ref.family
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.emoji, fontSize = 34.sp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleLarge)
                    Text("${f.members.size}人・${f.members.count { it.online }}人がオンライン", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (f.totalUnread > 0) Badge(containerColor = Urgent) { Text("${f.totalUnread}", fontSize = 13.sp, modifier = Modifier.padding(2.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(-8.dp)) {
                f.members.take(6).forEach { AvatarDot(it, 38.dp, online = it.online) }
                f.ai?.let { AvatarDot(it, 38.dp) }
            }
        }
    }
}

@Composable
fun JoinOrCreateDialog(onDismiss: () -> Unit, onDone: (String) -> Unit, addHost: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by Graph.sessions.collectAsState()
    // with several hosts, use one that is actually connected (newest first)
    val session = sessions.lastOrNull { it.connected.value } ?: sessions.lastOrNull() ?: return
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Family) = scope.launch {
        busy = true
        runCatching { val f = block(); session.upsertFamily(f); onDone(f.id) }.onFailure { toast(ctx, errText(it)) }
        busy = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("家族サーバー") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(nick, { nick = it.take(8) }, label = { Text("呼ばれ方（兄・母 など）") }, singleLine = true)
                OutlinedTextField(code, { code = it.take(12) }, label = { Text("招待コードで参加") }, singleLine = true)
                Button(enabled = normCode(code).length == 6 && !busy, onClick = {
                    run { session.api.decode(session.api.post("/api/families/join", buildJsonObject { put("code", normCode(code)); put("nickname", nick) })["family"]) }
                }, modifier = Modifier.fillMaxWidth()) { Text("参加") }
                OutlinedTextField(name, { name = it.take(20) }, label = { Text("新しい家族サーバーの名前") }, singleLine = true)
                Button(enabled = name.isNotBlank() && !busy, onClick = {
                    run { session.api.decode(session.api.post("/api/families", buildJsonObject { put("name", name); put("nickname", nick) })["family"]) }
                }, modifier = Modifier.fillMaxWidth()) { Text("作成") }
                TextButton(onClick = { run { session.api.decode(session.api.post("/api/demo/seed")["family"]) } }) { Text("🎬 デモ家族を作る") }
                TextButton(onClick = addHost) { Text("別のホストサーバーを追加") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
