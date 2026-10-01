package com.daijobu.famichibi.service

import com.daijobu.famichibi.net.ChibiCue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Messages that arrived while the phone was locked / not yet seen: shown by the lock-screen chibis. */
object LockBoard {
    data class Party(val familyId: String, val title: String, val lines: List<Pair<String, String>>, val at: Long)

    private val _items = MutableStateFlow<List<ChibiCue>>(emptyList())
    val items: StateFlow<List<ChibiCue>> = _items.asStateFlow()
    @Volatile var party: Party? = null

    fun add(c: ChibiCue) {
        if (c.kind == "acked" || c.kind == "test") return
        _items.update { l -> (l.filterNot { same(it, c) } + c).takeLast(20) }
    }

    fun remove(c: ChibiCue) = _items.update { l -> l.filterNot { same(it, c) } }
    fun hasPending() = _items.value.isNotEmpty() || party != null
    fun takePending(): List<ChibiCue> = _items.value.also { _items.value = emptyList() }
    fun clear() { _items.value = emptyList() }

    private fun same(a: ChibiCue, b: ChibiCue) =
        a.familyId == b.familyId && a.kind == b.kind && a.messageId == b.messageId && a.askId == b.askId && (a.messageId != 0L || a.askId.isNotEmpty() || a.text == b.text)
}
