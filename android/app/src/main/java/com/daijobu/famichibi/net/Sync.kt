package com.daijobu.famichibi.net

import android.util.Log
import com.daijobu.famichibi.data.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

/*
 * Local-first data for the family screens (fridge memos, calendar, to-dos).
 *
 *   server state ── refresh ──▶ SyncedList.base ─┐
 *                                                 ├─▶ items (what the UI shows)
 *   user actions ──▶ Outbox (persisted queue) ───┘      = base + not-yet-sent changes
 *                         │
 *                         └── flush (in order, with Idempotency-Key) ──▶ server
 *
 * - The UI never talks to the network directly: an action changes the screen at once and is queued.
 * - The queue survives app restarts and is sent when the connection is back; a retry can never create
 *   a duplicate because the server replays the first result for the same Idempotency-Key.
 * - The server copy ("base") is replaced as a whole and de-duplicated by id, and refreshes are
 *   single-flight, so concurrent refreshes (own action + WebSocket notification) cannot interleave.
 */

/** One change made in the app that the server has not confirmed yet. */
@Serializable
data class PendingOp(
    val id: String,               // also the Idempotency-Key
    val list: String,             // SyncedList key, e.g. "memos-f_123"
    val kind: String,             // add | patch | delete
    val itemId: String,           // server id, or a local id ("local-…") for items created offline
    val method: String,
    val path: String,
    val body: JsonObject? = null,
    val item: JsonObject? = null, // add: the item as shown locally; patch: the changed fields
    val resultKey: String = "",   // add: where the created item is in the server's reply
    val label: String,            // for messages to the user: "メモを貼る"
    val created: Long = System.currentTimeMillis(),
    val tries: Int = 0,
)

/** [send] performs one queued request (HostSession: api.send with the op id as Idempotency-Key). */
class Outbox(private val cache: DiskCache, private val scope: CoroutineScope, private val send: suspend (PendingOp) -> JsonObject) {
    private val _ops = MutableStateFlow(cache.read(OPS, ListSerializer(PendingOp.serializer())) ?: emptyList())
    val ops: StateFlow<List<PendingOp>> = _ops.asStateFlow()

    /** Human-readable problems ("「メモを剥がす」ができませんでした…") for the UI to show. */
    private val _problems = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val problems: SharedFlow<String> = _problems.asSharedFlow()

    private val lists = HashMap<String, SyncedList<*>>()
    private val mutex = Mutex()
    @Volatile private var again = false
    private var retryJob: Job? = null

    fun register(list: SyncedList<*>) = synchronized(lists) { lists[list.key] = list }

    fun enqueue(op: PendingOp) {
        _ops.update { ops ->
            val add = ops.firstOrNull { it.kind == "add" && it.itemId == op.itemId }
            when {
                // created and removed while offline: the server never needs to hear about it
                add != null && op.kind == "delete" -> ops.filterNot { it.itemId == op.itemId }
                // changed before it was ever sent: fold the change into the creation
                add != null && op.kind == "patch" -> ops.map {
                    if (it === add) it.copy(item = JsonObject(it.item.orEmpty() + op.item.orEmpty())) else it
                }
                else -> ops + op
            }
        }
        save()
        flush()
    }

    /** Send queued changes in order. Safe to call any time; concurrent calls are merged. */
    fun flush() {
        scope.launch {
            if (!mutex.tryLock()) { again = true; return@launch }
            val touched = HashSet<String>()
            try {
                do {
                    again = false
                    while (true) {
                        val op = _ops.value.firstOrNull() ?: break
                        if (!sendOne(op, touched)) break
                    }
                } while (again)
            } finally {
                mutex.unlock()
            }
            touched.forEach { key -> synchronized(lists) { lists[key] }?.refresh() }
            if (_ops.value.isNotEmpty()) scheduleRetry()
        }
    }

    /** @return true to continue with the next op, false to stop for now (offline / server trouble). */
    private suspend fun sendOne(op: PendingOp, touched: MutableSet<String>): Boolean {
        try {
            val r = send(op)
            done(op)
            touched += op.list
            val list = synchronized(lists) { lists[op.list] }
            when (op.kind) {
                "add" -> r[op.resultKey]?.let { list?.confirmAdd(op.itemId, it.jsonObject) }
                "delete" -> list?.confirmDelete(op.itemId)
            }
            return true
        } catch (e: IOException) {
            return false // no connection: keep it and try again later
        } catch (e: ApiException) {
            when {
                op.kind == "delete" && e.code == 404 -> { done(op); synchronized(lists) { lists[op.list] }?.confirmDelete(op.itemId); return true }
                e.code in 400..499 -> {
                    // the server refused (deleted by someone else, no permission, ...): drop it and show the real state again
                    done(op)
                    touched += op.list
                    _problems.tryEmit(if (e.code == 404) "ほかの家族がすでに削除していたため「${op.label}」はできませんでした"
                        else "「${op.label}」ができませんでした（${e.message}）。最新の状態に戻しました")
                    return true
                }
                op.tries + 1 >= MAX_TRIES -> {
                    done(op); touched += op.list
                    _problems.tryEmit("「${op.label}」をサーバーが受け付けませんでした（${e.message}）")
                    return true
                }
                else -> { _ops.update { l -> l.map { if (it.id == op.id) it.copy(tries = it.tries + 1) else it } }; save(); return false }
            }
        } catch (e: Exception) {
            Log.w(TAG, "op ${op.label}: $e")
            return false
        }
    }

    private fun done(op: PendingOp) { _ops.update { l -> l.filterNot { it.id == op.id } }; save() }

    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch { delay(15_000); flush() }
    }

    private fun save() = cache.write(OPS, ListSerializer(PendingOp.serializer()), _ops.value, delayMs = 0)

    companion object {
        private const val TAG = "Outbox"
        private const val OPS = "outbox"
        private const val MAX_TRIES = 5
    }
}

/**
 * A server collection mirrored locally. [items] = last server copy + pending local changes.
 */
class SyncedList<T>(
    val key: String,
    private val serializer: KSerializer<T>,
    private val idOf: (T) -> String,
    private val outbox: Outbox,
    private val cache: DiskCache,
    private val scope: CoroutineScope,
    private val newestFirst: Boolean = false,
    private val fetch: suspend () -> List<T>,
) {
    private val listSer = ListSerializer(serializer)
    private val base = MutableStateFlow(cache.read(key, listSer) ?: emptyList())

    val items: StateFlow<List<T>> = combine(base, outbox.ops) { b, ops -> overlay(b, ops) }
        .stateIn(scope, SharingStarted.Eagerly, overlay(base.value, outbox.ops.value))

    /** null = fine; otherwise why the last refresh failed (shown as "offline" in the UI). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val refreshing = Mutex()
    @Volatile private var again = false

    init { outbox.register(this) }

    /** Fetch the server copy. Concurrent calls collapse into at most one extra fetch. */
    fun refresh(): Job = scope.launch {
        if (!refreshing.tryLock()) { again = true; return@launch }
        try {
            do {
                again = false
                try {
                    setBase(fetch())
                    _error.value = null
                } catch (e: Exception) {
                    _error.value = if (e is IOException) "オフライン" else e.message ?: e.toString()
                    Log.w("SyncedList", "$key: $e")
                }
            } while (again)
        } finally {
            refreshing.unlock()
        }
    }

    fun confirmAdd(localId: String, created: JsonObject) {
        val item = runCatching { JsonCfg.decodeFromJsonElement(serializer, created) }.getOrNull() ?: return
        base.update { l -> upsert(l.filterNot { idOf(it) == localId }, item) }
        save()
    }

    fun confirmDelete(id: String) { base.update { l -> l.filterNot { idOf(it) == id } }; save() }

    private fun setBase(list: List<T>) { base.value = list.distinctBy(idOf); save() }

    private fun save() = cache.write(key, listSer, base.value)

    private fun upsert(list: List<T>, item: T): List<T> {
        val id = idOf(item)
        return if (list.any { idOf(it) == id }) list.map { if (idOf(it) == id) item else it }
        else if (newestFirst) listOf(item) + list else list + item
    }

    private fun overlay(server: List<T>, ops: List<PendingOp>): List<T> {
        var list = server
        for (op in ops) {
            if (op.list != key) continue
            list = when (op.kind) {
                "add" -> op.item?.let { runCatching { JsonCfg.decodeFromJsonElement(serializer, it) }.getOrNull() }
                    ?.let { if (list.any { x -> idOf(x) == op.itemId }) list else upsert(list, it) } ?: list
                "patch" -> list.map { if (idOf(it) == op.itemId) merge(it, op.item.orEmpty()) else it }
                "delete" -> list.filterNot { idOf(it) == op.itemId }
                else -> list
            }
        }
        return list.distinctBy(idOf)
    }

    private fun merge(item: T, fields: Map<String, kotlinx.serialization.json.JsonElement>): T = runCatching {
        val obj = JsonCfg.encodeToJsonElement(serializer, item).jsonObject
        JsonCfg.decodeFromJsonElement(serializer, JsonObject(obj + fields))
    }.getOrDefault(item)
}

/** Memos, calendar and to-dos of one family: local-first, synced with the server. */
class FamilyStore(private val session: HostSession, val fid: String, cache: DiskCache, private val outbox: Outbox, scope: CoroutineScope) {
    private val api get() = session.api

    val memos = SyncedList("memos-$fid", Memo.serializer(), { it.id }, outbox, cache, scope, newestFirst = true) {
        api.decode(api.get("/api/families/$fid/memos")["memos"])
    }
    val tasks = SyncedList("tasks-$fid", FamTask.serializer(), { it.id }, outbox, cache, scope) {
        api.decode(api.get("/api/families/$fid/tasks")["tasks"])
    }

    /** The calendar shows one month at a time; the window is a little wider than the month. */
    @Volatile private var window = defaultWindow()
    val events = SyncedList("events-$fid", CalEvent.serializer(), { it.id }, outbox, cache, scope) {
        val (a, b) = window
        api.decode(api.get("/api/families/$fid/events?start=$a&end=$b")["events"])
    }

    fun showMonth(monthStartSec: Double) {
        val w = monthStartSec - 7 * 86400 to monthStartSec + 43 * 86400
        if (w == window) return
        window = w
        events.refresh()
    }

    fun refreshAll() { memos.refresh(); tasks.refresh(); events.refresh() }

    // ------------------------------------------------------------------ fridge memos
    fun addMemo(text: String, color: String, target: String?) {
        val id = localId()
        val body = buildJsonObject { put("text", text); put("color", color); target?.let { put("target_id", it) } }
        val item = buildJsonObject {
            put("id", id); put("family_id", fid); put("author_id", session.userId); put("text", text); put("color", color)
            target?.let { put("target_id", it) }; put("created_at", System.currentTimeMillis() / 1000.0)
        }
        outbox.enqueue(PendingOp(newOp(), memos.key, "add", id, "POST", "/api/families/$fid/memos", body, item, "memo", "メモを貼る"))
    }

    fun setMemoDone(m: Memo, done: Boolean) = patch(memos.key, m.id, "/api/memos/${m.id}", "done", done, if (done) "メモを完了にする" else "メモを戻す")

    fun deleteMemo(m: Memo) = outbox.enqueue(PendingOp(newOp(), memos.key, "delete", m.id, "DELETE", "/api/memos/${m.id}", label = "メモを剥がす"))

    // ------------------------------------------------------------------ to-dos
    fun addTask(title: String, assignee: String?) {
        val id = localId()
        val body = buildJsonObject { put("title", title); assignee?.let { put("assignee_id", it) } }
        val item = buildJsonObject { put("id", id); put("family_id", fid); put("title", title); assignee?.let { put("assignee_id", it) }; put("created_by", session.userId) }
        outbox.enqueue(PendingOp(newOp(), tasks.key, "add", id, "POST", "/api/families/$fid/tasks", body, item, "task", "やることを追加"))
    }

    fun setTaskDone(t: FamTask, done: Boolean) = patch(tasks.key, t.id, "/api/tasks/${t.id}", "done", done, if (done) "やることを完了にする" else "やることを戻す")

    fun deleteTask(t: FamTask) = outbox.enqueue(PendingOp(newOp(), tasks.key, "delete", t.id, "DELETE", "/api/tasks/${t.id}", label = "やることを削除"))

    // ------------------------------------------------------------------ calendar
    fun addEvent(title: String, start: Double, end: Double?, private: Boolean, shared: Boolean) {
        val id = localId()
        val body = buildJsonObject { put("title", title); put("start", start); end?.let { put("end", it) }; put("private", private); put("shared", shared) }
        val item = buildJsonObject {
            put("id", id); put("family_id", fid); if (!shared) put("user_id", session.userId); put("title", title); put("start", start)
            end?.let { put("end", it) }; put("private", if (private) 1 else 0)
        }
        outbox.enqueue(PendingOp(newOp(), events.key, "add", id, "POST", "/api/families/$fid/events", body, item, "event", "予定を追加"))
    }

    fun deleteEvent(e: CalEvent) = outbox.enqueue(PendingOp(newOp(), events.key, "delete", e.id, "DELETE", "/api/events/${e.id}", label = "予定を削除"))

    private fun patch(list: String, id: String, path: String, field: String, value: Boolean, label: String) {
        // the server stores flags as 0/1; the local copy must look the same
        val local = JsonObject(mapOf(field to JsonPrimitive(if (value) 1 else 0)))
        outbox.enqueue(PendingOp(newOp(), list, "patch", id, "PATCH", path, buildJsonObject { put(field, value) }, local, label = label))
    }

    private fun newOp() = UUID.randomUUID().toString()
    private fun localId() = "local-" + UUID.randomUUID().toString().take(13)

    private companion object {
        fun defaultWindow(): Pair<Double, Double> {
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            val s = c.timeInMillis / 1000.0
            return s - 7 * 86400 to s + 43 * 86400
        }
    }
}
