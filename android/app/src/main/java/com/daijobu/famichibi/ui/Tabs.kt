package com.daijobu.famichibi.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daijobu.famichibi.FamRef
import com.daijobu.famichibi.net.CalEvent
import com.daijobu.famichibi.net.FamTask
import com.daijobu.famichibi.net.Memo
import com.daijobu.famichibi.net.bool
import com.daijobu.famichibi.net.num
import com.daijobu.famichibi.net.obj
import com.daijobu.famichibi.net.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

// =============================================================================================
// Fridge memos
// =============================================================================================
private val MemoColors = listOf("#fff3a3", "#ffd6e0", "#d4f5e9", "#d8e8ff", "#ffe4c4")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoTab(ref: FamRef) {
    val s = ref.session
    val fam = ref.family
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val memos = remember { mutableStateListOf<Memo>() }
    var text by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<JsonObject?>(null) }
    var color by remember { mutableStateOf(MemoColors[0]) }
    var target by remember { mutableStateOf<String?>(null) }

    suspend fun reload() = runCatching { memos.clear(); memos.addAll(s.api.decode<List<Memo>>(s.api.get("/api/families/${fam.id}/memos")["memos"])) }
    LaunchedEffect(fam.id) { reload() }
    LaunchedEffect(fam.id) {
        s.events.collect { ev ->
            if (ev.str("family_id") == fam.id && ev.str("type") in setOf("memo", "memo_deleted", "memo_due")) reload()
        }
    }
    // Live preview of the natural-language time ("15日16時：…" -> ⏰ 10/15 16:00)
    LaunchedEffect(text) {
        preview = null
        if (text.length < 3) return@LaunchedEffect
        delay(350)
        preview = runCatching { s.api.post("/api/memos/parse", buildJsonObject { put("text", text) }) }.getOrNull()
    }

    LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionCard("🧲 冷蔵庫にメモを貼る") {
                OutlinedTextField(text, { text = it }, placeholder = { Text("例: 15日16時：カレー作っておいたから食べてね") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                preview?.let { p ->
                    val label = p.str("label").orEmpty()
                    if (label.isNotBlank()) RowCenter {
                        Icon(Icons.Default.Alarm, null, tint = MaterialTheme.colorScheme.primary)
                        Text("$label に${if (target == null) "みんな" else fam.member(target)?.label}のスマホで、あなたのちびっこが踊ってお知らせします",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MemoColors.forEach { c ->
                        Box(Modifier.size(26.dp).clip(CircleShape).background(parseColor(c)).border(if (c == color) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { color = c })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(target == null, { target = null }, label = { Text("みんなへ") })
                    fam.members.filter { it.user_id != s.userId }.take(4).forEach { m -> FilterChip(target == m.user_id, { target = m.user_id }, label = { Text(m.label) }) }
                }
                Button(enabled = text.isNotBlank(), onClick = {
                    val body = text
                    scope.launch {
                        runCatching {
                            s.api.post("/api/families/${fam.id}/memos", buildJsonObject { put("text", body); put("color", color); target?.let { put("target_id", it) } })
                            text = ""; reload()
                        }.onFailure { toast(ctx, errText(it)) }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("貼る") }
            }
        }
        if (memos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyHint("🧊", "冷蔵庫はまだ空っぽです") }
        items(memos, key = { it.id }) { m ->
            val author = fam.member(m.author_id)
            Surface(
                color = parseColor(m.color, Color(0xFFFFF3A3)), shape = RoundedCornerShape(4.dp, 4.dp, 18.dp, 4.dp), shadowElevation = 3.dp,
                modifier = Modifier.rotate(((m.id.hashCode() % 5) - 2) * 0.8f).combinedClickable(
                    onClick = { scope.launch { runCatching { s.api.patch("/api/memos/${m.id}", buildJsonObject { put("done", m.done == 0) }); reload() } } },
                    onLongClick = { scope.launch { runCatching { s.api.delete("/api/memos/${m.id}"); reload() } } },
                ),
            ) {
                Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(14.dp).clip(CircleShape).background(parseColor(author?.color)))
                    Text(m.text, color = Color(0xFF3A3326), textDecoration = if (m.done == 1) TextDecoration.LineThrough else null, fontSize = 15.sp)
                    if (m.notify_label.isNotBlank()) Text("⏰ ${m.notify_label}${if (m.delivered == 1) "（通知済）" else ""}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8A6D3B))
                    Text("— ${author?.label ?: "だれか"}${m.target_id?.let { " → " + (fam.member(it)?.label ?: "") } ?: ""}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF6B5E45))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("タップで完了／長押しで剥がす", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

// =============================================================================================
// Calendar
// =============================================================================================
@Composable
fun CalendarTab(ref: FamRef) {
    val s = ref.session
    val fam = ref.family
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val events = remember { mutableStateListOf<CalEvent>() }
    val month = remember { mutableStateOf(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); clearTime() }) }
    var selected by remember { mutableStateOf(Calendar.getInstance().apply { clearTime() }) }
    var adding by remember { mutableStateOf(false) }

    suspend fun reload() = runCatching {
        val a = month.value.timeInMillis / 1000.0 - 7 * 86400
        val b = a + 50 * 86400
        events.clear(); events.addAll(s.api.decode<List<CalEvent>>(s.api.get("/api/families/${fam.id}/events?start=$a&end=$b")["events"]))
    }
    LaunchedEffect(fam.id, month.value.timeInMillis) { reload() }
    LaunchedEffect(fam.id) { s.events.collect { ev -> if (ev.str("family_id") == fam.id && ev.str("type")?.startsWith("event") == true) reload() } }

    fun dayKey(c: Calendar) = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    val byDay = events.groupBy { e -> dayKey(Calendar.getInstance().apply { timeInMillis = (e.start * 1000).toLong() }) }
    val birthdays = fam.members.filter { it.birthday.isNotBlank() }.associateBy { it.birthday }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month.value = (month.value.clone() as Calendar).apply { add(Calendar.MONTH, -1) } }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "前月") }
                    Text("${month.value.get(Calendar.YEAR)}年 ${month.value.get(Calendar.MONTH) + 1}月", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    IconButton(onClick = { month.value = (month.value.clone() as Calendar).apply { add(Calendar.MONTH, 1) } }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "次月") }
                }
                Row { "日月火水木金土".forEach { Text("$it", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = if (it == '日') Urgent else if (it == '土') AiBlue else MaterialTheme.colorScheme.onSurfaceVariant) } }
                val first = month.value.clone() as Calendar
                val offset = first.get(Calendar.DAY_OF_WEEK) - 1
                val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
                val cells = (0 until 42).map { i -> if (i < offset || i >= offset + days) null else (first.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, i - offset + 1) } }
                cells.chunked(7).filter { w -> w.any { it != null } }.forEach { week ->
                    Row {
                        week.forEach { c ->
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (c != null && dayKey(c) == dayKey(selected)) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable(enabled = c != null) { if (c != null) selected = c }, contentAlignment = Alignment.Center) {
                                if (c != null) {
                                    val today = dayKey(c) == dayKey(Calendar.getInstance())
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${c.get(Calendar.DAY_OF_MONTH)}", fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                                            color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            byDay[dayKey(c)].orEmpty().take(3).forEach { e -> Box(Modifier.size(5.dp).clip(CircleShape).background(parseColor(fam.member(e.user_id)?.color, Mint))) }
                                            if (birthdays.containsKey("%02d-%02d".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)))) Text("🎂", fontSize = 8.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.get(Calendar.MONTH) + 1}月${selected.get(Calendar.DAY_OF_MONTH)}日の予定", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Text("追加") }
            }
        }
        val list = byDay[dayKey(selected)].orEmpty()
        birthdays["%02d-%02d".format(selected.get(Calendar.MONTH) + 1, selected.get(Calendar.DAY_OF_MONTH))]?.let { m ->
            item { Surface(color = Color(0xFFFFE4EC), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("🎂 ${m.label}の誕生日（0時にちびっこ達がお祝いに集合）", Modifier.padding(12.dp)) } }
        }
        if (list.isEmpty()) item { Text("予定はありません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(list, key = { it.id }) { e ->
            val m = fam.member(e.user_id)
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp, 36.dp).clip(RoundedCornerShape(3.dp)).background(parseColor(m?.color, Mint)))
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        RowCenter { if (e.masked) Icon(Icons.Default.Lock, null, Modifier.size(14.dp)); Text(e.title, fontWeight = FontWeight.Bold) }
                        Text(fmtDateTime(e.start) + (e.end?.let { " 〜 " + fmtTime(it) } ?: "") + "・" + (m?.label ?: "家族みんな"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (e.user_id == s.userId || e.user_id == null) IconButton(onClick = { scope.launch { runCatching { s.api.delete("/api/events/${e.id}"); reload() } } }) { Icon(Icons.Default.Delete, "削除") }
                }
            }
        }
    }
    if (adding) AddEventDialog(selected, onDismiss = { adding = false }) { title, start, end, private, shared ->
        adding = false
        scope.launch {
            runCatching {
                s.api.post("/api/families/${fam.id}/events", buildJsonObject {
                    put("title", title); put("start", start); end?.let { put("end", it) }; put("private", private); put("shared", shared)
                }); reload()
            }.onFailure { toast(ctx, errText(it)) }
        }
    }
}

private fun Calendar.clearTime() { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }

@Composable
private fun AddEventDialog(day: Calendar, onDismiss: () -> Unit, onAdd: (String, Double, Double?, Boolean, Boolean) -> Unit) {
    var title by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("10:00") }
    var end by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf(false) }
    fun ts(hm: String): Double? {
        val p = hm.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (p.size != 2) return null
        return (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, p[0]); set(Calendar.MINUTE, p[1]) }.timeInMillis / 1000.0
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${day.get(Calendar.MONTH) + 1}/${day.get(Calendar.DAY_OF_MONTH)} に予定を追加") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("予定") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, label = { Text("開始") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it }, label = { Text("終了") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(shared, { shared = it }); Text("  家族みんなの予定") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(private, { private = it }); Text("  内容を非公開（「予定あり」と表示）") }
        }
    }, confirmButton = {
        TextButton(enabled = title.isNotBlank() && ts(start) != null, onClick = { onAdd(title, ts(start)!!, ts(end), private, shared) }) { Text("追加") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}

// =============================================================================================
// Tasks
// =============================================================================================
@Composable
fun TasksTab(ref: FamRef) {
    val s = ref.session
    val fam = ref.family
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val tasks = remember { mutableStateListOf<FamTask>() }
    var text by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf<String?>(null) }
    suspend fun reload() = runCatching { tasks.clear(); tasks.addAll(s.api.decode<List<FamTask>>(s.api.get("/api/families/${fam.id}/tasks")["tasks"])) }
    LaunchedEffect(fam.id) { reload() }
    LaunchedEffect(fam.id) { s.events.collect { ev -> if (ev.str("family_id") == fam.id && ev.str("type")?.startsWith("task") == true) reload() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard("✅ やること") {
                OutlinedTextField(text, { text = it }, placeholder = { Text("例: お風呂掃除") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(assignee == null, { assignee = null }, label = { Text("だれでも") })
                    fam.members.take(4).forEach { m -> FilterChip(assignee == m.user_id, { assignee = m.user_id }, label = { Text(m.label) }) }
                }
                Button(enabled = text.isNotBlank(), onClick = {
                    val t = text
                    scope.launch {
                        runCatching { s.api.post("/api/families/${fam.id}/tasks", buildJsonObject { put("title", t); assignee?.let { put("assignee_id", it) } }); text = ""; reload() }
                            .onFailure { toast(ctx, errText(it)) }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("追加") }
                Text("終わらせると、頼んだ人のスマホに「やっておいたよ！」とちびっこが伝えに行きます", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (tasks.isEmpty()) item { EmptyHint("🧹", "やることはありません") }
        items(tasks, key = { it.id }) { t ->
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(t.done == 1, { d -> scope.launch { runCatching { s.api.patch("/api/tasks/${t.id}", buildJsonObject { put("done", d) }); reload() } } })
                    Text(t.title, Modifier.weight(1f), textDecoration = if (t.done == 1) TextDecoration.LineThrough else null)
                    fam.member(t.assignee_id)?.let { AvatarDot(it, 28.dp) }
                    IconButton(onClick = { scope.launch { runCatching { s.api.delete("/api/tasks/${t.id}"); reload() } } }) { Icon(Icons.Default.Delete, "削除") }
                }
            }
        }
    }
}

// =============================================================================================
// Smart home (same MCP server the AI chibi uses)
// =============================================================================================
@Composable
fun HomeIotTab(ref: FamRef, askAi: (String) -> Unit) {
    val s = ref.session
    val fam = ref.family
    val scope = rememberCoroutineScope()
    val home by s.home.collectAsState()
    LaunchedEffect(fam.id) { runCatching { s.api.get("/api/families/${fam.id}/home") } }
    if (!fam.iot_enabled) { EmptyHint("🔌", "この家族サーバーではIoT連携がOFFです（家族タブ → 設定）"); return }
    val devices = home?.obj("devices")?.entries?.map { it.key to it.value.jsonObject } ?: emptyList()
    val byRoom = devices.groupBy { it.second.str("room") ?: "その他" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionCard("🤖 ちびAIに頼む") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("電気消して", "エアコンつけて", "暑い？").forEach { c -> androidx.compose.material3.AssistChip(onClick = { askAi(c) }, label = { Text(c) }) }
                }
                Text("家電の操作はMCP経由。Home Assistant(ha-mcp)に切り替えれば本物の家電も動きます", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        byRoom.forEach { (room, list) ->
            item(key = room) {
                SectionCard(room) {
                    list.forEach { (id, d) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val type = d.str("type")
                            val on = d.bool("on") ?: d.bool("open") ?: d.bool("locked") ?: false
                            val emoji = when (type) { "light" -> if (on) "💡" else "⚫"; "climate" -> "❄️"; "cover" -> "🪟"; "lock" -> if (on) "🔒" else "🔓"; else -> "📟" }
                            Text(emoji, fontSize = 24.sp)
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(d.str("name").orEmpty(), fontWeight = FontWeight.Bold)
                                val sub = when (type) {
                                    "sensor" -> "${d.str("value")}${d.str("unit").orEmpty()}"
                                    "climate" -> if (on) "${d.str("mode")} ${d.num("target")?.toInt()}℃" else "停止中"
                                    "cover" -> if (on) "開いている" else "閉じている"
                                    "lock" -> if (on) "施錠中" else "開いている"
                                    else -> if (on) "ついている" else "消えている"
                                }
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (type != "sensor") {
                                val key = when (type) { "cover" -> "open"; "lock" -> "locked"; else -> "on" }
                                Switch(on, { v -> scope.launch { runCatching { s.api.post("/api/families/${fam.id}/home/$id", buildJsonObject { put(key, v) }) } } })
                            }
                        }
                    }
                }
            }
        }
    }
}
