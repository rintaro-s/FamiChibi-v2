package com.daijobu.famichibi.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.net.Api
import com.daijobu.famichibi.net.Avatar
import com.daijobu.famichibi.net.str
import kotlinx.coroutines.launch
import java.io.File

/** Pick a VRM avatar with a live 3D preview. Users can also upload their own VRM. */
@Composable
fun AvatarPicker(api: Api, selected: String, color: String, onSelect: (Avatar) -> Unit, modifier: Modifier = Modifier, previewHeight: Int = 260) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var avatars by remember { mutableStateOf<List<Avatar>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var credit by remember { mutableStateOf("") }
    val stage = remember { StageState() }

    LaunchedEffect(api.base) {
        runCatching { avatars = api.decode(api.get("/api/avatars")["avatars"]) }
            .onFailure { toast(ctx, errText(it)) }
        if (selected.isBlank()) avatars.firstOrNull()?.let(onSelect)
    }
    LaunchedEffect(stage.web, selected, avatars) {
        val web = stage.web ?: return@LaunchedEffect
        val av = avatars.firstOrNull { it.id == selected } ?: return@LaunchedEffect
        loading = true
        credit = av.credit
        val local = Graph.avatars.ensure(api.url(av.url))
        if (local != null) {
            web.clear()
            web.add("preview", local, av.name, color)
            web.js("setTimeout(()=>Chibi.deliver('preview',{emotion:'happy',motion:'wave'}), 900)")
        } else toast(ctx, "アバターを読み込めませんでした")
        loading = false
    }
    LaunchedEffect(stage) {
        stage.events.collect { ev -> if (ev.str("type") == "loaded") loading = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            runCatching {
                val tmp = File(ctx.cacheDir, "upload.vrm")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                val r = api.upload("/api/avatars", tmp, "application/octet-stream", mapOf("name" to "マイアバター"))
                val av: Avatar = api.decode(r["avatar"])
                avatars = avatars + av
                onSelect(av)
            }.onFailure { toast(ctx, "アップロード失敗: ${errText(it)}") }
            loading = false
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().height(previewHeight.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), MaterialTheme.colorScheme.surface))),
            contentAlignment = Alignment.Center,
        ) {
            ChibiStage(stage, "fraction=0.95&fps=30", Modifier.fillMaxSize())
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(avatars, key = { it.id }) { av ->
                val on = av.id == selected
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .border(if (on) 2.5.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { onSelect(av) }.padding(horizontal = 14.dp, vertical = 10.dp),
                ) { Text(av.name, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1) }
            }
            item {
                OutlinedButton(onClick = { picker.launch("*/*") }) {
                    Icon(Icons.Default.Upload, null); Text(" VRMを追加")
                }
            }
        }
        if (credit.isNotBlank()) Text("クレジット: $credit", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
