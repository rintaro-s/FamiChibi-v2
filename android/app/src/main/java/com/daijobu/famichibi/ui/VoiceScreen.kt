package com.daijobu.famichibi.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.service.ChibiService
import com.daijobu.famichibi.voice.VoiceCall

@Composable
fun VoiceScreen(familyId: String, channelId: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val fams by Graph.families.collectAsState()
    val ref = fams.firstOrNull { it.family.id == familyId } ?: return
    val fam = ref.family
    val channel = fam.channels.firstOrNull { it.id == channelId }
    val call by VoiceCall.active.collectAsState()
    val inThis = call?.channelId == channelId
    val roster by ref.session.voice.collectAsState()
    val users = roster[channelId].orEmpty()
    val levels = call?.levels?.collectAsState()?.value ?: emptyMap()
    val muted = call?.muted?.collectAsState()?.value ?: false
    val stage = remember { StageState() }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) VoiceCall.join(ctx, ref.session, familyId, channelId)
    }

    // participants' chibis stand on the stage; their mouths follow the live audio level
    LaunchedEffect(stage.web, users) {
        val web = stage.web ?: return@LaunchedEffect
        val actors = users.mapNotNull { ChibiService.actorFor(familyId, it) }
        web.js("Chibi.ids().forEach(id=>{ if(!${org.json.JSONArray(actors.map { it.id })}.includes(id)) Chibi.remove(id) })")
        for (a in actors) Graph.avatars.ensure(a.avatarUrl ?: continue)?.let { web.add(a.id, it, a.name, a.color) }
        web.arrangeRow(actors.map { it.id })
        web.js("setTimeout(()=>Chibi.arrangeRow(${org.json.JSONArray(actors.map { it.id })}),1200)")
    }
    LaunchedEffect(levels) {
        val web = stage.web ?: return@LaunchedEffect
        levels.forEach { (uid, lv) -> web.mouth(uid, (lv * 4f).coerceAtMost(1f)) }
    }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color2.top, MaterialTheme.colorScheme.background))).safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
            Column {
                Text("🔊 ${channel?.name ?: "通話"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${users.size}人が参加中", style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            ChibiStage(stage, "layout=row&tex=512&fraction=0.9&fps=30", Modifier.fillMaxSize())
            if (users.isEmpty()) Text("まだだれもいません", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(12.dp)) {
            users.forEach { uid ->
                val m = fam.member(uid)
                val lv = levels[uid] ?: 0f
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AvatarDot(m, (44 + lv * 40).dp)
                    Text(m?.label ?: "?", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Box(Modifier.weight(1f))
        if (!inThis) {
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    VoiceCall.join(ctx, ref.session, familyId, channelId)
                else mic.launch(Manifest.permission.RECORD_AUDIO)
            }, modifier = Modifier.padding(24.dp).fillMaxWidth().height(60.dp), shape = RoundedCornerShape(30.dp)) { Text("参加する", style = MaterialTheme.typography.titleMedium) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(28.dp)) {
                FilledIconButton(onClick = { call?.toggleMute() }, modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer)) {
                    Icon(if (muted) Icons.Default.MicOff else Icons.Default.Mic, "ミュート")
                }
                FilledIconButton(onClick = { call?.leave() }, modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Urgent)) { Icon(Icons.Default.CallEnd, "退出") }
            }
        }
    }
}

private object Color2 {
    val top = androidx.compose.ui.graphics.Color(0xFFDFF3FF)
}
