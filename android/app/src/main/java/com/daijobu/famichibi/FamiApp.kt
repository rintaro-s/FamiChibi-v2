package com.daijobu.famichibi

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.daijobu.famichibi.service.ChibiService

class FamiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Graph.appInForeground.value = true
                Graph.sessions.value.forEach { it.reconnectNow() }
            }
            override fun onStop(owner: LifecycleOwner) { Graph.appInForeground.value = false }
        })
    }
}

/** Restart the resident chibi after reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        if (Graph.prefs.accounts.value.isNotEmpty() && Graph.prefs.settings.value.onboarded) ChibiService.start(context)
    }
}
