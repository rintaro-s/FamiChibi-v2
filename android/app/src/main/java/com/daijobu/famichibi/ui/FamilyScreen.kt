package com.daijobu.famichibi.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

val FamilyTabs = listOf("チャット", "冷蔵庫メモ", "カレンダー", "やること", "おうち", "家族")

@Composable
fun FamilyScreen(familyId: String, initialChannel: String?, back: () -> Unit, openVoice: (String) -> Unit) {
    val fams by Graph.families.collectAsState()
    val ref = fams.firstOrNull { it.family.id == familyId }
    if (ref == null) {
        EmptyHint("🔌", "家族サーバーを読み込み中…")
        return
    }
    val fam = ref.family
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var roomOpen by rememberSaveable { mutableStateOf(true) }
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    var tapped by remember { mutableStateOf<String?>(null) }
    var channelId by rememberSaveable(familyId) { mutableStateOf(initialChannel ?: fam.textChannels.firstOrNull()?.id.orEmpty()) }

    DisposableEffect(familyId) {
        Graph.foregroundFamily.value = familyId
        Graph.prefs.lastFamilyId = familyId
        onDispose { if (Graph.foregroundFamily.value == familyId) Graph.foregroundFamily.value = null }
    }

    val onStageReply: (ChibiCue, String) -> Unit = { cue, text ->
        Graph.scope.launch {
            runCatching {
                if (cue.kind == "ask" && cue.askId.isNotBlank()) ref.session.answerAsk(cue.askId, text)
                else ref.session.sendMessage(cue.channelId.ifBlank { channelId }, text)
                if (cue.messageId != 0L) ref.session.ack(cue.messageId)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // header + living room
        Box(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)))
                .statusBarsPadding().animateContentSize()
        ) {
            Column {
                Box(Modifier.fillMaxWidth()) {
                    IconButton(onClick = back, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${fam.emoji} ${fam.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        PresenceStrip(fam)
                    }
                    androidx.compose.foundation.layout.Row(Modifier.align(Alignment.CenterEnd)) {
                        SilentButton()
                        IconButton(onClick = { roomOpen = !roomOpen }) {
                            Icon(if (roomOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "リビング")
                        }
                    }
                }
                // The living room folds away while typing so the chat keeps its space.
                SilentBanner(Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                if (roomOpen && !imeVisible) {
                    LivingRoom(ref, Modifier.fillMaxWidth().height(250.dp), onReply = onStageReply, onTapActor = { tapped = it })
                }
            }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.background) {
            FamilyTabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) })
            }
        }
        tapped?.let { id -> MemberCard(ref, id, onDismiss = { tapped = null }) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ChatTab(ref, channelId, onChannel = { channelId = it }, openVoice = openVoice)
                1 -> MemoTab(ref)
                2 -> CalendarTab(ref)
                3 -> TasksTab(ref)
                4 -> HomeIotTab(ref) { cmd -> tab = 0; channelId = fam.aiChannel?.id ?: channelId; Graph.scope.launch { runCatching { ref.session.sendMessage(channelId, cmd) } } }
                else -> FamilyTab(ref)
            }
        }
    }
}
