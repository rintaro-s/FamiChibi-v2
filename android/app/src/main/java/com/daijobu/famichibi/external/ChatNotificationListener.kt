package com.daijobu.famichibi.external

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.daijobu.famichibi.Graph

/** Receives chat-app notifications (only while the user has granted "notification access"). */
class ChatNotificationListener : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }

    override fun onListenerConnected() { ExternalChats.connected = true; Log.i("ChatListener", "connected") }
    override fun onListenerDisconnected() { ExternalChats.connected = false; Log.i("ChatListener", "disconnected") }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { ExternalChats.onPosted(sbn) }.onFailure { Log.w("ChatListener", "posted: $it") }
    }
}
