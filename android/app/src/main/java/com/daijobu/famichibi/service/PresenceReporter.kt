package com.daijobu.famichibi.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.daijobu.famichibi.Graph
import com.daijobu.famichibi.net.Api
import com.daijobu.famichibi.net.HostSession
import com.daijobu.famichibi.net.strOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

/**
 * "けはい" (presence): a coarse, privacy-friendly status that lets the family's living room show what
 * everyone is doing - without location or content.
 *
 *   active : screen on / unlocked in the last few minutes
 *   moving : the step detector fired recently (walking)
 *   sleep  : screen off for a long time at night
 *   idle   : otherwise
 *   at_home: this phone can reach the family server's LAN address (= connected to the home Wi-Fi)
 */
class PresenceReporter(private val ctx: Context) : SensorEventListener {
    private var lastActive = System.currentTimeMillis()
    private var screenOffAt = 0L
    private var lastStep = 0L
    private var atHome: Boolean? = null
    private var lastSent = ""
    private var lastSentAt = 0L
    private var job: Job? = null
    private var receiver: BroadcastReceiver? = null

    fun start() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> { lastActive = System.currentTimeMillis(); screenOffAt = 0 }
                    Intent.ACTION_SCREEN_OFF -> screenOffAt = System.currentTimeMillis()
                }
                push()
            }
        }
        receiver = r
        ContextCompat.registerReceiver(ctx, r, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            val sm = ctx.getSystemService(SensorManager::class.java)
            sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        job = Graph.scope.launch {
            while (isActive) {
                checkHome()
                push(force = System.currentTimeMillis() - lastSentAt > 5 * 60_000)
                delay(60_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        runCatching { ctx.getSystemService(SensorManager::class.java).unregisterListener(this) }
    }

    /** Called on network changes too. */
    fun networkChanged() {
        Graph.scope.launch { delay(1500); checkHome(); push() }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val first = System.currentTimeMillis() - lastStep > 120_000
        lastStep = System.currentTimeMillis()
        if (first) push()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private suspend fun checkHome() {
        val sessions = Graph.sessions.value
        if (sessions.isEmpty()) return
        var home: Boolean? = null
        for (s in sessions) {
            val info = runCatching { s.api.get("/api/info") }.getOrNull() ?: continue
            val lan = (info["lan_urls"] as? JsonArray)?.mapNotNull { it.strOrNull() } ?: continue
            if (lan.isEmpty()) continue
            home = lan.any { u -> withTimeoutOrNull(1500) { runCatching { Api(u).get("/api/info") }.isSuccess } == true }
            if (home == true) break
        }
        atHome = home
    }

    private fun state(): String {
        val now = System.currentTimeMillis()
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm.isInteractive) lastActive = now
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            now - lastStep < 120_000 -> "moving"
            now - lastActive < 3 * 60_000 -> "active"
            screenOffAt > 0 && now - screenOffAt > 40 * 60_000 && (hour >= 22 || hour < 9) -> "sleep"
            else -> "idle"
        }
    }

    fun push(force: Boolean = false) {
        if (!Graph.prefs.settings.value.sharePresence) return
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val st = state()
        val key = "$st|$atHome|${battery / 5}|$charging"
        if (!force && key == lastSent) return
        lastSent = key
        lastSentAt = System.currentTimeMillis()
        val msg = buildJsonObject {
            put("type", "presence_report"); put("state", st); put("battery", battery); put("charging", charging)
            atHome?.let { put("at_home", it) }
        }
        Graph.sessions.value.forEach { s: HostSession -> s.send(msg) }
    }
}
