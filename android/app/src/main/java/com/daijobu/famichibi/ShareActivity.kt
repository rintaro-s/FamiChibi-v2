package com.daijobu.famichibi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daijobu.famichibi.ui.FamiTheme
import com.daijobu.famichibi.ui.toast
import kotlinx.coroutines.launch

/**
 * "ちびっこに届けてもらう": share text from any app (LINE, browser, maps...) straight to the family.
 * Answers the review concern about juggling several apps: FamiChibi does not have to replace them.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        val shared = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n")
        setContent {
            FamiTheme {
                val fams by Graph.families.collectAsState()
                var text by remember { mutableStateOf(shared) }
                var selected by remember { mutableStateOf(Graph.prefs.lastFamilyId) }
                AlertDialog(onDismissRequest = { finish() }, title = { Text("ちびっこに届けてもらう") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (fams.isEmpty()) Text("家族サーバーに接続中…")
                        fams.forEach { r -> FilterChip(selected == r.family.id, { selected = r.family.id }, label = { Text("${r.family.emoji} ${r.family.name}") }) }
                        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), minLines = 3)
                    }
                }, confirmButton = {
                    TextButton(enabled = text.isNotBlank() && fams.any { it.family.id == selected }, onClick = {
                        val ref = fams.first { it.family.id == selected }
                        val cid = ref.family.textChannels.firstOrNull()?.id ?: return@TextButton
                        Graph.scope.launch {
                            runCatching { ref.session.sendMessage(cid, text) }
                                .onSuccess { toast(this@ShareActivity, "${ref.family.name}に届けたよ") }
                                .onFailure { toast(this@ShareActivity, "送れませんでした") }
                            finish()
                        }
                    }) { Text("届ける") }
                }, dismissButton = { TextButton(onClick = { finish() }) { Text("やめる") } })
            }
        }
    }
}
