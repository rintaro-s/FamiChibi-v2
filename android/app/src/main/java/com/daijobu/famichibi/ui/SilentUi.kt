package com.daijobu.famichibi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daijobu.famichibi.data.Silent

private val Night = Color(0xFF5B5BD6)

/** Moon button: tap to choose how long to be silent, or to turn it off. */
@Composable
fun SilentButton() {
    val on by Silent.on.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(if (on) Icons.Filled.Bedtime else Icons.Outlined.Bedtime, "サイレントモード", tint = if (on) Night else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (on) {
                DropdownMenuItem(text = { Text("サイレントを解除（${Silent.label()}）") }, onClick = { Silent.disable(); open = false })
                HorizontalDivider()
            }
            Text("🌙 サイレントモード", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Silent.presets.forEach { (label, m) -> DropdownMenuItem(text = { Text(label) }, onClick = { Silent.enable(m); open = false }) }
            DropdownMenuItem(text = { Text("明日の朝7時まで") }, onClick = { Silent.enableUntilMorning(); open = false })
            DropdownMenuItem(text = { Text("オフにするまで") }, onClick = { Silent.enable(null); open = false })
        }
    }
}

/** Banner shown while silent mode is on. */
@Composable
fun SilentBanner(modifier: Modifier = Modifier) {
    val on by Silent.on.collectAsState()
    if (!on) return
    Surface(color = Night.copy(alpha = .12f), shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🌙 サイレント中（${Silent.label()}）ちびっこはお休み、通知は音なし", color = Night, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { Silent.disable() }) { Text("解除") }
        }
    }
}
