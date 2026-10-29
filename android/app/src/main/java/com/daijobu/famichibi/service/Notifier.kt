package com.daijobu.famichibi.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.daijobu.famichibi.LockScreenActivity
import com.daijobu.famichibi.MainActivity
import com.daijobu.famichibi.R
import com.daijobu.famichibi.net.ChibiCue

/** System notifications: a safety net for people who miss the chibi (and for wearables / the lock screen). */
class Notifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "ちびっこ常駐", NotificationManager.IMPORTANCE_MIN).apply {
            description = "ちびっこが画面にいるための通知"; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_FAMILY, "家族からの連絡", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "家族のメッセージ・メモ"
        })
        nm.createNotificationChannel(NotificationChannel(CH_URGENT, "急ぎの連絡", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "迎えに来て・至急など、見逃すと困る連絡"; enableVibration(true); lightColor = Color.RED
            vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 600)
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "静かなお知らせ", NotificationManager.IMPORTANCE_LOW))
    }

    /** Status notification with one-tap silent toggles. */
    fun updateService(silent: Boolean) {
        val mode = if (com.daijobu.famichibi.Graph.prefs.settings.value.silentShowChibi) "音だけオフ" else "ちびっこもお休み"
        val text = if (silent) "🌙 サイレント中・$mode（${com.daijobu.famichibi.data.Silent.label()}）" else "ちびっこが見守っています"
        val b = NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_chibi).setContentTitle("FamiChibi").setContentText(text)
            .setOngoing(true).setContentIntent(openIntent(null)).setPriority(NotificationCompat.PRIORITY_MIN)
        fun act(label: String, action: String, minutes: Int = 0, code: Int) {
            val i = Intent(ctx, ChibiService::class.java).setAction(action).putExtra("minutes", minutes)
            b.addAction(R.drawable.ic_chibi, label, PendingIntent.getService(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (silent) act("サイレント解除", ChibiService.ACTION_SILENT_OFF, code = 30)
        else { act("1時間サイレント", ChibiService.ACTION_SILENT, 60, 31); act("明日の朝まで", ChibiService.ACTION_SILENT, -1, 32) }
        runCatching { nm.notify(SERVICE_ID, b.build()) }
    }

    fun serviceNotification(text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_chibi)
            .setContentTitle("FamiChibi")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent(null))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun openIntent(cue: ChibiCue?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        cue?.let { i.putExtra("family", it.familyId).putExtra("channel", it.channelId) }
        return PendingIntent.getActivity(ctx, (cue?.messageId ?: 0).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun post(cue: ChibiCue, senderName: String, familyName: String, fullScreen: Boolean) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val channel = when {
            cue.urgent -> CH_URGENT
            cue.quiet -> CH_QUIET
            else -> CH_FAMILY
        }
        val person = Person.Builder().setName(senderName).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("自分").build())
            .setConversationTitle("$familyName")
            .setGroupConversation(true)
            .addMessage(cue.text, System.currentTimeMillis(), person)
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_chibi)
            .setStyle(style)
            .setContentIntent(openIntent(cue))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (cue.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(cue.familyId)
        if (cue.channelId.isNotBlank() || cue.kind == "ask") {
            val remote = RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("返信")
                .setChoices(cue.replies.take(3).toTypedArray()).build()
            val pi = PendingIntent.getBroadcast(ctx, notifId(cue), ReplyReceiver.intent(ctx, cue),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_chibi, "返信", pi).addRemoteInput(remote)
                .setAllowGeneratedReplies(true).build())
        }
        if (fullScreen) {
            val li = Intent(ctx, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setFullScreenIntent(PendingIntent.getActivity(ctx, 7, li, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT), true)
        }
        runCatching { nm.notify(notifId(cue), b.build()) }
    }

    fun cancel(cue: ChibiCue) = nm.cancel(notifId(cue))

    private fun notifId(cue: ChibiCue) = if (cue.messageId != 0L) cue.messageId.toInt() else (cue.familyId + cue.kind + cue.askId).hashCode()

    companion object {
        const val CH_SERVICE = "service"
        const val CH_FAMILY = "family"
        const val CH_URGENT = "urgent"
        const val CH_QUIET = "quiet"
        const val SERVICE_ID = 1
    }
}
