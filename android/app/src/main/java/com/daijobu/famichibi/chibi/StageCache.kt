package com.daijobu.famichibi.chibi

import android.view.ViewGroup
import com.daijobu.famichibi.Graph

/**
 * Keeps 3D stages (WebView + loaded VRMs) alive across recompositions, navigation, keyboard and
 * rotation, so the avatars are not downloaded/parsed/uploaded to the GPU again every time.
 * Least-recently used stages beyond [MAX] are destroyed.
 */
object StageCache {
    private const val MAX = 2
    private val stages = LinkedHashMap<String, ChibiWeb>(4, 0.75f, true)

    fun obtain(key: String, query: String): ChibiWeb {
        stages[key]?.let { return it }
        val w = ChibiWeb(Graph.app, query)
        stages[key] = w
        while (stages.size > MAX) {
            val (k, old) = stages.entries.first()
            if (old.parent != null) break // still on screen
            stages.remove(k)
            old.destroy()
        }
        return w
    }

    /** Detach from the UI but keep everything loaded; rendering pauses until it is shown again. */
    fun park(web: ChibiWeb) {
        web.pause(true)
        (web.parent as? ViewGroup)?.removeView(web)
    }

    /** Destroy stages that are not on screen (they are rebuilt from the on-disk VRM cache when shown again). */
    fun trim() {
        val hidden = stages.filterValues { it.parent == null && it.owner == null } // parked: no screen holds it
        hidden.forEach { (k, w) -> stages.remove(k); w.destroy() }
        if (hidden.isNotEmpty()) android.util.Log.i("StageCache", "trimmed ${hidden.size} hidden stage(s)")
    }

    fun clear() {
        stages.values.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        stages.clear()
    }
}
