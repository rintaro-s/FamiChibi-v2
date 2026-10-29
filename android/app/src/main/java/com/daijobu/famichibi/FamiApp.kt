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
        CrashLog.install(this)
        Graph.init(this)
        com.daijobu.famichibi.external.ExternalChats.ensureBound(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Graph.appInForeground.value = true
                com.daijobu.famichibi.external.ExternalChats.ensureBound(this@FamiApp)
                Graph.sessions.value.forEach { it.reconnectNow() }
            }
            override fun onStop(owner: LifecycleOwner) { Graph.appInForeground.value = false }
        })
    }

    /** Each 3D stage holds several VRMs in GPU/JS memory: give hidden ones back when Android runs low. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) com.daijobu.famichibi.chibi.StageCache.trim()
    }
}

/** Restart the resident chibi after reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        if (Graph.prefs.accounts.value.isNotEmpty() && Graph.prefs.settings.value.onboarded) ChibiService.start(context)
    }
}
