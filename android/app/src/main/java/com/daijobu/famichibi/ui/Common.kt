package com.daijobu.famichibi.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daijobu.famichibi.net.ApiException
import com.daijobu.famichibi.net.Member
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun parseColor(hex: String?, fallback: Color = Peach): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex ?: return fallback))
}.getOrDefault(fallback)

@Composable
fun AvatarDot(member: Member?, size: Dp = 36.dp, online: Boolean? = null, modifier: Modifier = Modifier) {
    val c = parseColor(member?.color, if (member?.ai == true) AiBlue else Peach)
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.18f)).border(2.dp, c, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (member?.ai == true) "AI" else (member?.label?.take(1) ?: "?"),
                color = c, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp, textAlign = TextAlign.Center,
            )
        }
        if (online != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.3f).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape)
                    .background(if (online) Mint else Color(0xFFB9B3B0))
            )
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        shadowElevation = 1.dp, modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun EmptyHint(emoji: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 44.sp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun RowCenter(content: @Composable () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

fun errText(t: Throwable): String = when (t) {
    is ApiException -> t.message ?: "エラー"
    is java.net.ConnectException, is java.net.SocketTimeoutException -> "サーバーにつながりません"
    else -> t.message ?: t.javaClass.simpleName
}

private val WD = "日月火水木金土"
fun fmtTime(ts: Double): String {
    val d = Date((ts * 1000).toLong())
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { time = d }
    return if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR))
        SimpleDateFormat("H:mm", Locale.JAPAN).format(d)
    else SimpleDateFormat("M/d H:mm", Locale.JAPAN).format(d)
}

fun fmtDateTime(ts: Double): String {
    val d = Date((ts * 1000).toLong())
    val c = Calendar.getInstance().apply { time = d }
    return SimpleDateFormat("M/d", Locale.JAPAN).format(d) + "(" + WD[c.get(Calendar.DAY_OF_WEEK) - 1] + ") " + SimpleDateFormat("H:mm", Locale.JAPAN).format(d)
}

/** Invite codes: accept full-width / lower-case / spaces from any keyboard (normalized only on submit, not while the IME composes). */
fun normCode(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).uppercase().filter { it.isLetterOrDigit() }
