package com.daijobu.famichibi.external

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.daijobu.famichibi.FamRef
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.net.ChibiCue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "Your family keeps using LINE; on your phone their chibis bring the messages."
 *
 * Only this phone needs FamiChibi for it to be useful: notifications of chat apps (LINE, SMS) from
 * people linked to a family member become that member's chibi walking in with a speech bubble, and the
 * chibi's quick replies answer through the chat app's own reply action.
 *
 * Privacy: everything happens on the device. Message text is never stored and never sent anywhere
 * (not even to the family server); only the sender names that were seen are kept, so the user can link
 * them to family members. Notifications from senders that are not linked are ignored.
 */
object ExternalChats {
    private const val TAG = "ExternalChats"

    /** Chat apps whose notifications can be delivered by the chibis. */
    val APPS = linkedMapOf(
        "jp.naver.line.android" to "LINE",
        "com.google.android.apps.messaging" to "SMS",
        "com.samsung.android.messaging" to "SMS",
        "com.android.mms" to "SMS",
        "com.coloros.mms" to "SMS",
    ).apply {
        // debug builds: `adb shell cmd notification post -S messaging …` (posted by the shell) stands in for LINE in tests
        if (com.daijobu.famichibi.BuildConfig.DEBUG) put("com.android.shell", "テスト")
    }

    data class Incoming(val ref: FamRef, val cue: ChibiCue)

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 16)
    /** Collected by ChibiService, which shows the cue like a family message. */
    val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    /** What the chibi needs to act on the original notification (reply / open), by cue messageId. */
    private class Handle(val reply: Notification.Action?, val open: PendingIntent?)
    private val handles = object : LinkedHashMap<Long, Handle>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Handle>?) = size > 30
    }
    private var nextId = -1L // negative ids never collide with family-server message ids
    private val lastSeen = HashMap<String, Long>() // notification key -> newest message time already delivered

    fun senderKey(app: String, sender: String) = "${APPS[app] ?: app}|$sender"

    fun hasAccess(ctx: Context): Boolean {
        val enabled = AndroidSettings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(ComponentName(ctx, ChatNotificationListener::class.java).flattenToString())
    }

    /** Set by the listener; false after the app was updated or force-stopped until Android binds it again. */
    @Volatile var connected = false

    /**
     * After an update or a force stop Android may leave the listener unbound. Ask for a rebind; some
     * phones (Xiaomi) ignore that, so also flip the component off and on, which makes the system bind it again.
     */
    fun ensureBound(ctx: Context) {
        if (connected || !Graph.prefs.settings.value.externalChats || !hasAccess(ctx)) return
        val comp = ComponentName(ctx, ChatNotificationListener::class.java)
        runCatching {
            val pm = ctx.packageManager
            pm.setComponentEnabledSetting(comp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(comp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            android.service.notification.NotificationListenerService.requestRebind(comp)
        }.onFailure { Log.w(TAG, "rebind: $it") }
    }

    fun accessSettingsIntent(): Intent = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------ incoming notifications
    fun onPosted(sbn: StatusBarNotification) {
        val settings = Graph.prefs.settings.value
        if (!settings.externalChats || sbn.packageName !in APPS) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return Unit.also { Log.d(TAG, "skip summary/ongoing flags=${n.flags}") }

        val (sender, text, time) = latestMessage(sbn) ?: return Unit.also { Log.d(TAG, "no message in ${sbn.packageName} notification") }
        if (time <= (lastSeen[sbn.key] ?: 0L)) return // the same notification re-posted (LINE updates it a lot)
        lastSeen[sbn.key] = time

        val key = senderKey(sbn.packageName, sender)
        remember(key)
        val msg = Msg(key, sender, APPS[sbn.packageName] ?: "チャット", text, Handle(replyAction(n), n.contentIntent))
        when (val link = settings.externalLinks[key]) {
            null -> askWho(msg)      // someone new: the AI chibi asks who this is
            IGNORED -> return        // the user said "not family"
            else -> deliver(msg, link)
        }
    }

    /** One incoming chat message (kept in memory only, never written anywhere). */
    private class Msg(val key: String, val sender: String, val app: String, val text: String, val handle: Handle)

    /** externalLinks value for "this sender is not family: don't ask again". */
    const val IGNORED = "-"
    private const val NOT_FAMILY = "家族じゃない"

    private fun deliver(msg: Msg, link: String) {
        val (fid, uid) = link.split("|").takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return
        val ref = Graph.famRef(fid) ?: return Unit.also { Log.d(TAG, "family $fid not loaded") }
        if (ref.family.members.none { it.user_id == uid }) return
        val id = synchronized(handles) { nextId--.also { handles[it] = msg.handle } }
        val a = LocalAnalyzer.analyze(msg.text)
        val cue = ChibiCue(
            from = uid, text = msg.text, emotion = a.emotion, motion = a.motion, fx = a.fx, urgent = a.urgent,
            replies = if (msg.handle.reply != null) (a.replies + Graph.prefs.settings.value.quickReplies).distinct().take(4) else emptyList(),
            reasons = listOf("${msg.app}で「${msg.sender}」から届いた通知をちびっこが代わりに届けています（端末内だけで処理）") + a.reasons,
            familyId = fid, messageId = id, kind = "external",
        )
        _incoming.tryEmit(Incoming(ref, cue))
    }

    /** A question cue: which family member is this sender? Answers are the cue's quick replies. */
    private class Question(val msg: Msg, val choices: Map<String, String>) // reply label -> "fid|uid"
    private val questions = HashMap<Long, Question>()
    private val askedAt = HashMap<String, Long>()

    private fun askWho(msg: Msg) {
        val now = System.currentTimeMillis()
        if (now - (askedAt[msg.key] ?: 0L) < 10 * 60_000) return // asked a moment ago: don't nag on every message
        askedAt[msg.key] = now
        val ref = Graph.families.value.firstOrNull { it.family.ai_enabled } ?: Graph.families.value.firstOrNull() ?: return
        val members = Graph.families.value.flatMap { r -> r.family.members.filter { it.user_id != r.session.userId }.map { r to it } }
        if (members.isEmpty()) return
        // a member whose name matches goes first
        val sorted = members.sortedByDescending { (_, m) -> listOf(m.name, m.nickname, m.label).any { it.isNotBlank() && (it in msg.sender || msg.sender in it) } }
        val choices = LinkedHashMap<String, String>()
        sorted.take(3).forEach { (r, m) -> choices[m.label] = "${r.family.id}|${m.user_id}" }
        val id = synchronized(handles) { nextId-- }
        questions[id] = Question(msg, choices)
        _incoming.tryEmit(Incoming(ref, ChibiCue(
            from = "ai", text = "${msg.app}で「${msg.sender}」からメッセージが届いたよ。家族のだれかな？ 教えてくれたら、次からその人のちびっこが届けるね",
            emotion = "happy", motion = "point", fx = "question", replies = choices.keys.toList() + NOT_FAMILY,
            reasons = listOf("まだ家族と結び付いていない送り主です（設定 →「家族のLINEも、ちびっこが届ける」でも変えられます）"),
            familyId = ref.family.id, messageId = id, kind = "external_who", duration = 30_000, // a question: give time to answer
        )))
    }

    /** The user answered the "who is this?" question. */
    fun answerWho(cue: ChibiCue, answer: String) {
        val q = questions.remove(cue.messageId) ?: return
        val link = q.choices[answer]
        Graph.prefs.updateSettings { it.copy(externalLinks = it.externalLinks + (q.msg.key to (link ?: IGNORED))) }
        if (link != null) deliver(q.msg, link) // and bring the message that started it
    }

    /** sender, text, time of the newest message in the notification. */
    private fun latestMessage(sbn: StatusBarNotification): Triple<String, String, Long>? {
        val n = sbn.notification
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.let { style ->
            val m = style.messages.lastOrNull() ?: return null
            val who = m.person?.name?.toString() ?: return null // null person = a message I sent myself
            val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return null
            return Triple(who, text, m.timestamp.takeIf { it > 0 } ?: sbn.postTime)
        }
        val extras: Bundle = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return null
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()
        if (title.isBlank() || text.isNullOrBlank()) return null
        return Triple(title, text, sbn.postTime)
    }

    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    private fun remember(key: String) {
        val seen = Graph.prefs.settings.value.externalSeen
        if (seen.firstOrNull() == key) return
        Graph.prefs.updateSettings { it.copy(externalSeen = (listOf(key) + it.externalSeen.filterNot { k -> k == key }).take(30)) }
    }

    // ------------------------------------------------------------------ actions from the chibi
    /** Reply through the chat app itself (the notification's own reply action). */
    fun reply(ctx: Context, cue: ChibiCue, text: String): Boolean {
        val action = synchronized(handles) { handles[cue.messageId] }?.reply ?: return false
        return runCatching {
            val inputs = action.remoteInputs ?: return false
            val intent = Intent()
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(ctx, 0, intent)
            true
        }.onFailure { Log.w(TAG, "reply: $it") }.getOrDefault(false)
    }

    /** Open the conversation in the chat app. */
    fun open(cue: ChibiCue): Boolean = runCatching {
        synchronized(handles) { handles[cue.messageId] }?.open?.send() ?: return false
        true
    }.getOrDefault(false)
}
