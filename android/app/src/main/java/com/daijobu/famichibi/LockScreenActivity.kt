package com.daijobu.famichibi

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daijobu.famichibi.net.ChibiCue
import com.daijobu.famichibi.service.ChibiService
import com.daijobu.famichibi.service.LockBoard
import com.daijobu.famichibi.ui.ChibiStage
import com.daijobu.famichibi.ui.FamiTheme
import com.daijobu.famichibi.ui.StageState
import com.daijobu.famichibi.ui.Urgent
import com.daijobu.famichibi.ui.parseColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chibis on the lock screen (answer to the review comment: "通知に気付かなかった人への対応策…ロック画面への常時表示").
 * Shown over the keyguard for urgent messages / AI questions, or when the screen turns on with unread messages.
 * Quick replies work without unlocking.
 */
class LockScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        val preview = intent.getBooleanExtra("preview", false)
        if (preview) {
            val ref = Graph.families.value.firstOrNull()
            if (ref != null) LockBoard.add(ChibiCue(from = ref.family.members.firstOrNull { it.user_id != ref.session.userId }?.user_id ?: "ai",
                text = "今から帰る。駅まで迎えに来て！雨やばい☔", emotion = "worried", motion = "knock", fx = "rain", urgent = true,
                replies = listOf("今行く！", "10分待って", "ごめん無理"), familyId = ref.family.id, kind = "preview",
                reasons = listOf("「迎えに来て」→ 急ぎの連絡と判断")))
        }
        setContent { FamiTheme { LockUi(onClose = { finishAndUnlock(false) }, onOpen = { finishAndUnlock(true) }) } }
    }

    private fun finishAndUnlock(open: Boolean) {
        val km = getSystemService(KeyguardManager::class.java)
        if (open && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    startActivity(android.content.Intent(this@LockScreenActivity, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            })
        } else {
            if (open) startActivity(android.content.Intent(this, MainActivity::class.java))
            finish()
        }
    }
}

@Composable
private fun LockUi(onClose: () -> Unit, onOpen: () -> Unit) {
    val items by LockBoard.items.collectAsState()
    val stage = remember { StageState() }
    var now by remember { mutableStateOf(Date()) }
    val party = LockBoard.party
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(10_000) } }

    // senders' chibis gather on the lock screen
    val senders = items.map { it.familyId to (it.from ?: "ai") }.distinct().take(4)
    LaunchedEffect(stage.web, senders, party) {
        val web = stage.web ?: return@LaunchedEffect
        val actors = if (party != null) Graph.famRef(party.familyId)?.family?.members?.mapNotNull { ChibiService.actorFor(party.familyId, it.user_id) } ?: emptyList()
        else senders.mapNotNull { (fid, from) -> ChibiService.actorFor(fid, from) }
        for (a in actors) Graph.avatars.ensure(a.avatarUrl ?: continue)?.let { web.add(a.id, it, a.name, a.color, a.ai) }
        web.arrangeRow(actors.map { it.id })
        delay(1500)
        web.arrangeRow(actors.map { it.id })
        if (party != null) {
            web.party()
            party.lines.forEachIndexed { i, (from, text) ->
                web.js("setTimeout(()=>Chibi.say(${JSONObject.quote(from)},{text:${JSONObject.quote(text)},duration:5200}), ${1500 + i * 5500})")
            }
            party.lines.forEach { (from, text) ->
                val a = actors.firstOrNull { it.id == from }
                Graph.speech.speak(text, a?.pitch ?: 1f)
            }
            LockBoard.party = null
        } else {
            // the newest message per sender is shown as a bubble; urgent ones knock on the glass
            items.groupBy { it.familyId to (it.from ?: "ai") }.forEach { (key, list) ->
                val c = list.last()
                val id = ChibiService.actorFor(key.first, key.second)?.id ?: return@forEach
                web.deliver(id, c.text, c.emotion, if (c.urgent) "knock" else c.motion, c.fx, c.urgent, emptyList(), emptyList(),
                    badge = if (list.size > 1) "${list.size}件" else null, sticky = true)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1C2340), Color(0xFF3A2F4F), Color(0xFF5B3A4A))))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(SimpleDateFormat("H:mm", Locale.JAPAN).format(now), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 18.dp))
            Text(SimpleDateFormat("M月d日(E)", Locale.JAPAN).format(now), color = Color.White.copy(alpha = .8f))
            Text(if (party != null) party.title else if (items.isEmpty()) "新しい連絡はありません" else "家族から ${items.size} 件の連絡",
                color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                ChibiStage(stage, "layout=row&tex=512&fraction=0.58&fps=30&theme=dark", Modifier.fillMaxSize())
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items.reversed(), key = { it.familyId + it.kind + it.messageId + it.askId + it.text.hashCode() }) { c -> LockCard(c) }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { items.forEach { ChibiService.instance?.onSeen(it) }; LockBoard.clear(); onClose() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("見たよ（閉じる）") }
                Button(onClick = onOpen) { Text("アプリで開く") }
            }
        }
    }
}

@Composable
private fun LockCard(c: ChibiCue) {
    val fam = Graph.famRef(c.familyId)?.family
    val m = fam?.member(c.from)
    val color = parseColor(m?.color)
    Surface(color = Color.White.copy(alpha = .94f), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().then(if (c.urgent) Modifier.border(3.dp, Urgent, RoundedCornerShape(20.dp)) else Modifier)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m?.label ?: fam?.ai_name ?: "", color = color, fontWeight = FontWeight.Bold)
                if (c.urgent) Text("急ぎ", color = Color.White, fontSize = 11.sp, modifier = Modifier.background(Urgent, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp))
                Text(fam?.name.orEmpty(), color = Color.Gray, fontSize = 11.sp)
            }
            Text(c.text, color = Color(0xFF2B2B33), fontSize = 18.sp)
            if (c.replies.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.replies.take(3).forEach { r ->
                    OutlinedButton(onClick = {
                        val svc = ChibiService.instance
                        if (svc != null) svc.onReply(c, r) else if (c.kind != "external") Graph.scope.launch { Graph.famRef(c.familyId)?.session?.sendMessage(c.channelId, r) }
                        LockBoard.remove(c)
                    }) { Text(r) }
                }
            }
        }
    }
}
