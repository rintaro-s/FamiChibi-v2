package com.daijobu.famichibi.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.net.ChibiCue
import kotlinx.coroutines.launch

/** Tabs of the family screen. "おうち" (smart home) is an optional extension and only shows when it is turned on. */
fun familyTabs(iot: Boolean) = listOfNotNull("チャット", "冷蔵庫メモ", "カレンダー", "やること", if (iot) "おうち" else null, "家族")

@Composable
fun FamilyScreen(familyId: String, initialChannel: String?, back: () -> Unit, openVoice: (String) -> Unit) {
    val fams by Graph.families.collectAsState()
    val ref = fams.firstOrNull { it.family.id == familyId }
    if (ref == null) {
        EmptyHint("🔌", "家族サーバーを読み込み中…")
        return
    }
    val fam = ref.family
    var tabName by rememberSaveable { mutableStateOf("チャット") }
    var roomOpen by rememberSaveable { mutableStateOf(true) }
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    var tapped by remember { mutableStateOf<String?>(null) }
    // queued changes the server refused (e.g. already deleted by someone else) are reported, never swallowed
    val toastCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(ref.session) { ref.session.outbox.problems.collect { toast(toastCtx, it) } }
    var channelId by rememberSaveable(familyId) { mutableStateOf(initialChannel ?: fam.textChannels.firstOrNull()?.id.orEmpty()) }

    DisposableEffect(familyId) {
        Graph.foregroundFamily.value = familyId
        Graph.prefs.lastFamilyId = familyId
        onDispose { if (Graph.foregroundFamily.value == familyId) Graph.foregroundFamily.value = null }
    }

    val onStageReply: (ChibiCue, String) -> Unit = { cue, text ->
        when (cue.kind) {
            // a LINE / SMS message: answer in that app, or say who the sender is
            "external_who" -> com.daijobu.famichibi.external.ExternalChats.answerWho(cue, text)
            "external" -> { com.daijobu.famichibi.external.ExternalChats.reply(toastCtx, cue, text) }
            else -> Graph.scope.launch {
                runCatching {
                    if (cue.kind == "ask" && cue.askId.isNotBlank()) ref.session.answerAsk(cue.askId, text)
                    else ref.session.sendMessage(cue.channelId.ifBlank { channelId }, text)
                    if (cue.messageId != 0L) ref.session.ack(cue.messageId)
                }
            }
        }
        Unit
    }

    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val header: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth()) {
            IconButton(onClick = back, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
            Column(Modifier.align(Alignment.Center).padding(horizontal = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${fam.emoji} ${fam.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                PresenceStrip(fam)
            }
            androidx.compose.foundation.layout.Row(Modifier.align(Alignment.CenterEnd)) {
                SilentButton()
                if (!landscape) IconButton(onClick = { roomOpen = !roomOpen }) {
                    Icon(if (roomOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "リビング")
                }
            }
        }
    }
    val room: @Composable (Modifier) -> Unit = { m -> LivingRoom(ref, m, onReply = onStageReply, onTapActor = { tapped = it }) }
    val content: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            val tabs = familyTabs(fam.iot_enabled)
            val current = tabName.takeIf { it in tabs } ?: "チャット" // e.g. "おうち" after the extension was turned off
            PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(current), edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.background) {
                tabs.forEach { t ->
                    Tab(selected = current == t, onClick = { tabName = t }, text = { Text(t, fontWeight = if (current == t) FontWeight.Bold else FontWeight.Normal) })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (current) {
                    "チャット" -> ChatTab(ref, channelId, onChannel = { channelId = it }, openVoice = openVoice)
                    "冷蔵庫メモ" -> MemoTab(ref)
                    "カレンダー" -> CalendarTab(ref)
                    "やること" -> TasksTab(ref)
                    "おうち" -> HomeIotTab(ref) { cmd -> tabName = "チャット"; channelId = fam.aiChannel?.id ?: channelId; Graph.scope.launch { runCatching { ref.session.sendMessage(channelId, cmd) } } }
                    else -> FamilyTab(ref)
                }
            }
        }
    }
    tapped?.let { id -> MemberCard(ref, id, onDismiss = { tapped = null }) }
    val bg = Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background))

    if (landscape) {
        // Landscape: living room on the left (full height), conversation on the right.
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
                    androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Top))
        ) {
            Column(Modifier.weight(0.46f).fillMaxHeight().background(bg)) {
                header()
                SilentBanner(Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                room(Modifier.fillMaxWidth().weight(1f))
            }
            content(Modifier.weight(0.54f).fillMaxHeight().imePadding())
        }
    } else {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
            Box(Modifier.fillMaxWidth().background(bg).statusBarsPadding().animateContentSize()) {
                Column {
                    header()
                    SilentBanner(Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                    // The living room folds away while typing so the chat keeps its space (the stage stays loaded).
                    if (roomOpen && !imeVisible) room(Modifier.fillMaxWidth().height(250.dp))
                }
            }
            content(Modifier.weight(1f).fillMaxWidth())
        }
    }
}
