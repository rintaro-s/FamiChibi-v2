package com.daijobu.famichibi.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.net.ChibiCue
import com.daijobu.famichibi.net.JsonCfg
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Inline reply from a system notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
        val cue = intent.getStringExtra("cue")?.let { runCatching { JsonCfg.decodeFromString<ChibiCue>(it) }.getOrNull() } ?: return
        if (text.isEmpty()) return
        val pending = goAsync()
        Graph.scope.launch {
            try {
                val svc = ChibiService.instance
                if (svc != null) svc.onReply(cue, text)
                else Graph.famRef(cue.familyId)?.session?.let { s ->
                    if (cue.kind == "ask") s.answerAsk(cue.askId, text) else s.sendMessage(cue.channelId, text)
                }
                context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_TEXT = "reply"
        fun intent(ctx: Context, cue: ChibiCue) = Intent(ctx, ReplyReceiver::class.java).putExtra("cue", JsonCfg.encodeToString(cue))
    }
}
