package com.daijobu.famichibi.net

import com.daijobu.famichibi.data.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The local-first sync behind the memo / calendar / to-do tabs (the screens that crashed in review):
 * no duplicates under concurrent refreshes, offline changes queued and sent exactly once, failures reported.
 */
class SyncTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope

    // a fake server
    private val server = mutableListOf(task("tk_1", "お風呂掃除"), task("tk_2", "宿題"))
    @Volatile private var online = true
    private val sent = mutableListOf<PendingOp>()
    private var reply: (PendingOp) -> JsonObject = { op ->
        when (op.kind) {
            "add" -> {
                val t = task("tk_${server.size + 10}", op.body!!["title"].toString().trim('"'))
                server += t
                buildJsonObject { put("task", JsonCfg.encodeToJsonElement(FamTask.serializer(), t)) }
            }
            "delete" -> { if (server.none { it.id == op.itemId }) throw ApiException(404, "見つかりません"); server.removeAll { it.id == op.itemId }; EmptyJson }
            else -> { server.replaceAll { if (it.id == op.itemId) it.copy(done = 1) else it }; EmptyJson }
        }
    }

    @Before fun setUp() {
        dir = Files.createTempDirectory("sync").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private fun task(id: String, title: String) = FamTask(id = id, family_id = "f_1", title = title)

    private fun newOutbox(cache: DiskCache) = Outbox(cache, scope) { op ->
        if (!online) throw IOException("offline")
        synchronized(sent) { sent += op }
        synchronized(server) { reply(op) }
    }

    private fun newList(cache: DiskCache, outbox: Outbox, fetches: AtomicInteger = AtomicInteger()) =
        SyncedList("tasks-f_1", FamTask.serializer(), { it.id }, outbox, cache, scope) {
            fetches.incrementAndGet()
            if (!online) throw IOException("offline")
            delay(50) // slow network: refreshes overlap
            synchronized(server) { server.toList() }
        }

    private fun op(kind: String, itemId: String, title: String = "") = PendingOp(
        id = java.util.UUID.randomUUID().toString(), list = "tasks-f_1", kind = kind, itemId = itemId,
        method = when (kind) { "add" -> "POST"; "delete" -> "DELETE"; else -> "PATCH" }, path = "/x",
        body = buildJsonObject { put("title", title); put("done", true) },
        item = if (kind == "add") buildJsonObject { put("id", itemId); put("family_id", "f_1"); put("title", title) } else buildJsonObject { put("done", 1) },
        resultKey = "task", label = kind,
    )

    private fun waitUntil(what: String, cond: () -> Boolean) = runBlocking {
        try { withTimeout(5000) { while (!cond()) delay(20) } } catch (e: Exception) { throw AssertionError("timed out waiting for: $what") }
    }

    @Test fun concurrentRefreshesNeverProduceDuplicates() {
        val cache = DiskCache(dir, scope)
        val fetches = AtomicInteger()
        val list = newList(cache, newOutbox(cache), fetches)
        repeat(20) { list.refresh() } // own action + WebSocket notifications arriving together
        waitUntil("first load") { list.items.value.size == 2 }
        Thread.sleep(300)
        val ids = list.items.value.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue("refreshes were collapsed, got ${fetches.get()}", fetches.get() <= 3)
    }

    @Test fun offlineChangesShowAtOnceAndAreSentExactlyOnceWhenBackOnline() {
        val cache = DiskCache(dir, scope)
        val outbox = newOutbox(cache)
        val list = newList(cache, outbox)
        list.refresh(); waitUntil("load") { list.items.value.size == 2 }

        online = false
        outbox.enqueue(op("add", "local-1", "洗濯物たたむ"))
        outbox.enqueue(op("patch", "tk_1"))
        outbox.enqueue(op("delete", "tk_2"))
        // the screen already shows the result
        waitUntil("optimistic state") { list.items.value.map { it.title } == listOf("お風呂掃除", "洗濯物たたむ") }
        assertEquals(1, list.items.value.first { it.id == "tk_1" }.done)
        Thread.sleep(200)
        assertEquals(3, outbox.ops.value.size) // kept while offline

        online = true
        outbox.flush()
        waitUntil("queue drained") { outbox.ops.value.isEmpty() }
        waitUntil("server copy") { list.items.value.none { it.id.startsWith("local-") } && list.items.value.size == 2 }
        assertEquals(listOf("POST", "PATCH", "DELETE"), sent.map { it.method }) // in order, once each
        assertEquals(listOf("お風呂掃除", "洗濯物たたむ"), server.map { it.title })
    }

    @Test fun createdThenDeletedOfflineIsNeverSent() {
        val cache = DiskCache(dir, scope)
        val outbox = newOutbox(cache)
        online = false
        outbox.enqueue(op("add", "local-9", "まちがい"))
        outbox.enqueue(op("delete", "local-9"))
        assertTrue(outbox.ops.value.isEmpty())
        online = true; outbox.flush(); Thread.sleep(200)
        assertTrue(sent.isEmpty())
    }

    @Test fun deletingSomethingAlreadyGoneIsFineAndRefusalsAreReported() {
        val cache = DiskCache(dir, scope)
        val outbox = newOutbox(cache)
        val problems = mutableListOf<String>()
        val job = scope.launch { outbox.problems.collect { problems += it } }
        outbox.enqueue(op("delete", "tk_404"))          // someone else already deleted it: 404 = done
        waitUntil("404 delete done") { outbox.ops.value.isEmpty() }
        assertTrue(problems.isEmpty())
        reply = { throw ApiException(403, "権限がありません") }
        outbox.enqueue(op("patch", "tk_1"))
        waitUntil("refusal reported") { problems.isNotEmpty() && outbox.ops.value.isEmpty() }
        assertTrue(problems.single().contains("権限がありません"))
        job.cancel()
    }

    @Test fun queuedChangesSurviveAnAppRestart() {
        online = false
        val cache1 = DiskCache(dir, scope)
        newOutbox(cache1).enqueue(op("add", "local-5", "ゴミ出し"))
        Thread.sleep(300) // queue writes are immediate (no debounce)
        val restarted = newOutbox(DiskCache(dir, scope))
        assertEquals(listOf("local-5"), restarted.ops.value.map { it.itemId })
        online = true; restarted.flush()
        waitUntil("sent after restart") { restarted.ops.value.isEmpty() }
        assertTrue(server.any { it.title == "ゴミ出し" })
    }
}
