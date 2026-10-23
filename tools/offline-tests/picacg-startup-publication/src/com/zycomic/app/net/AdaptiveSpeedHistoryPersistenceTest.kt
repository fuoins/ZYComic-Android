package com.zycomic.app.net

import android.content.Context
import android.content.MemoryContext
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/** Uses the production JSON/preferences store, not the policy's in-memory Store fake. */
class AdaptiveSpeedHistoryPersistenceTest {
    @BeforeEach
    fun reset() {
        DevConfig.appContext = MemoryContext()
    }

    @Test
    fun `quota round trips separately from endpoint health and survives endpoint eviction`() {
        val quota = AdaptiveSpeedProbe.ExplorationBudget(123L, 2, 456L)
        AdaptiveSpeedHistory.writeExploration("exploration-budget:scope", quota)
        repeat(1_030) { index ->
            AdaptiveSpeedHistory.write("endpoint-$index", AdaptiveSpeedProbe.History(2, 30L, index.toLong()))
        }
        assertEquals(quota, AdaptiveSpeedHistory.readExploration("exploration-budget:scope"))
        val preferences = DevConfig.appContext!!.getSharedPreferences("adaptive_speed_probe_history_v1", Context.MODE_PRIVATE)
        assertTrue(preferences.all.size <= 1_024)
        assertFalse(preferences.all.keys.any { it.contains("endpoint") || it.contains("scope") })
    }

    @Test
    fun `cancelled background attempt is reserved durably before probing and a new session cannot repeat it`() = runBlocking {
        val at = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        fun session() = AdaptiveSpeedProbe.Session(
            "wifi", at, AdaptiveSpeedHistory, now = { at }, zone = ZoneId.of("UTC"),
        )
        var calls = 0
        try {
            session().run(
                scope = "MANWA:image", candidates = listOf("failed-pool"), key = { it }, quick = true,
                success = { _: String -> true }, elapsedMs = { 10L }, explorationOnly = true,
            ) {
                calls++
                throw CancellationException("cancel after durable reservation")
            }
        } catch (_: CancellationException) {
            // No successful or failed health sample was written, but the quota was already persisted.
        }
        val oldContext = DevConfig.appContext!!
        DevConfig.appContext = object : Context() {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = oldContext.getSharedPreferences(name, mode)
        }
        val results = session().run(
            scope = "MANWA:image", candidates = listOf("failed-pool"), key = { it }, quick = true,
            success = { _: String -> true }, elapsedMs = { 10L }, explorationOnly = true,
        ) {
            calls++
            "ok"
        }
        assertTrue(results.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun `new foreground session preempts background exploration without recording endpoint failure`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val session = AdaptiveSpeedHistory.session("wifi")
        session.deferExploration(
            scope = "PICACG:cover", candidates = listOf("background-peer"), key = { it },
            success = { _: String -> true }, elapsedMs = { 10L },
        ) {
            entered.complete(Unit)
            try {
                CompletableDeferred<String>().await()
            } finally {
                stopped.complete(Unit)
            }
        }
        AdaptiveSpeedHistory.launchExploration(session)
        withTimeout(1_000L) { entered.await() }
        AdaptiveSpeedHistory.session("wifi")
        withTimeout(1_000L) { stopped.await() }
        val preferences = DevConfig.appContext!!.getSharedPreferences("adaptive_speed_probe_history_v1", Context.MODE_PRIVATE)
        assertEquals(1, preferences.all.size, "only the durable reservation remains, not a cancelled health sample")
    }

    @Test
    fun `storage unavailable prevents unrecorded exploration but not foreground recovery`() = runBlocking {
        DevConfig.appContext = null
        assertThrows(IllegalStateException::class.java) {
            AdaptiveSpeedHistory.writeExploration("quota", AdaptiveSpeedProbe.ExplorationBudget(1L, 1, 1L))
        }
        val result = AdaptiveSpeedHistory.session("wifi").run(
            scope = "JM:line", candidates = listOf("recovery"), key = { it }, quick = true,
            success = { _: String -> true }, elapsedMs = { 10L },
        ) { "ok" }
        assertEquals(listOf("recovery"), result.map { it.first })
    }
}
