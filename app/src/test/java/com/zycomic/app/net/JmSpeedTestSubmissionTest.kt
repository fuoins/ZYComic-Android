package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class JmSpeedTestSubmissionTest {
    private val identity = JmSpeedTestRunIdentity("run-1", 3, 7, 5, "config", "images", "policy", "network")

    @Test
    fun `selected endpoints enrich the published snapshot without invalidating the successful run`() {
        val measured = JmRouteManager.SpeedTestSnapshot(runId = identity.runId, completedAtEpochMs = 100)
        var published = measured
        val result = JmSpeedTestSubmission.commit(
            identity,
            true,
            { true },
            submitSnapshot = {
                published = measured.copy(
                    roundSelectedLine = DomainIp("api.example", "192.0.2.1"),
                    roundSelectedImage = DomainIp("img.example", "198.51.100.1"),
                )
                true
            },
            saveHistories = { true },
            publishedRunId = { published.runId },
        )
        assertNotEquals(measured, published)
        assertTrue(result.isComplete(published.runId, identity))
        assertEquals(JmSpeedTestSubmission.State.COMPLETE, result.state)
    }

    @Test
    fun `config gate and stale generations do not submit anything`() {
        for ((accepted, current, state) in listOf(
            Triple(false, true, JmSpeedTestSubmission.State.CONFIG_WAIT_REJECTED),
            Triple(true, false, JmSpeedTestSubmission.State.STALE_GENERATION),
        )) {
            val result = JmSpeedTestSubmission.commit(
                identity,
                accepted,
                { current },
                submitSnapshot = { error("must not submit") },
                saveHistories = { error("must not save") },
                publishedRunId = { identity.runId },
            )
            assertEquals(state, result.state)
            assertFalse(result.isComplete(identity.runId, identity))
        }
    }

    @Test
    fun `snapshot rejection must not save history or complete`() {
        val result = JmSpeedTestSubmission.commit(
            identity,
            true,
            { true },
            { false },
            { error("must not save") },
            { identity.runId },
        )
        assertEquals(JmSpeedTestSubmission.State.SNAPSHOT_REJECTED, result.state)
        assertFalse(result.snapshotSubmitted)
        assertFalse(result.historiesSaved)
        assertFalse(result.isComplete(identity.runId, identity))
    }

    @Test
    fun `history save rejection cannot publish complete after snapshot success`() {
        val result = JmSpeedTestSubmission.commit(identity, true, { true }, { true }, { false }, { identity.runId })
        assertEquals(JmSpeedTestSubmission.State.HISTORY_REJECTED, result.state)
        assertTrue(result.snapshotSubmitted)
        assertFalse(result.historiesSaved)
        assertFalse(result.isComplete(identity.runId, identity))
    }

    @Test
    fun `generation changing during snapshot submission prevents history save`() {
        var current = true
        val result = JmSpeedTestSubmission.commit(
            identity,
            true,
            { current },
            {
                current = false
                true
            },
            { error("must not save") },
            { identity.runId },
        )
        assertEquals(JmSpeedTestSubmission.State.STALE_GENERATION, result.state)
        assertFalse(result.isComplete(identity.runId, identity))
    }

    @Test
    fun `rejection after generation changes is diagnosed as stale rather than storage failure`() {
        var current = true
        val snapshotRejected = JmSpeedTestSubmission.commit(
            identity,
            true,
            { current },
            {
                current = false
                false
            },
            { error("must not save") },
            { identity.runId },
        )
        assertEquals(JmSpeedTestSubmission.State.STALE_GENERATION, snapshotRejected.state)
        current = true
        val historyRejected = JmSpeedTestSubmission.commit(
            identity,
            true,
            { current },
            { true },
            {
                current = false
                false
            },
            { identity.runId },
        )
        assertEquals(JmSpeedTestSubmission.State.STALE_GENERATION, historyRejected.state)
        assertFalse(snapshotRejected.isComplete(identity.runId, identity))
        assertFalse(historyRejected.isComplete(identity.runId, identity))
    }

    @Test
    fun `generation changing during history save or published run changing prevents completion`() {
        var current = true
        val stale = JmSpeedTestSubmission.commit(
            identity,
            true,
            { current },
            { true },
            {
                current = false
                true
            },
            { identity.runId },
        )
        assertEquals(JmSpeedTestSubmission.State.STALE_GENERATION, stale.state)
        assertFalse(stale.isComplete(identity.runId, identity))
        val replaced = JmSpeedTestSubmission.commit(identity, true, { true }, { true }, { true }, { "other-run" })
        assertEquals(JmSpeedTestSubmission.State.PUBLISHED_RUN_CHANGED, replaced.state)
        assertFalse(replaced.isComplete(identity.runId, identity))
    }

    @Test
    fun `a successful receipt cannot be reused by another run config generation or network`() {
        val complete = JmSpeedTestSubmission.commit(identity, true, { true }, { true }, { true }, { identity.runId })
        val changed = listOf(
            null,
            identity.copy(runId = "other-run"),
            identity.copy(platformRevision = 4),
            identity.copy(configRevision = 8),
            identity.copy(remoteVersion = 6),
            identity.copy(configFingerprint = "changed"),
            identity.copy(imageConfigFingerprint = "changed"),
            identity.copy(imageNetworkPolicy = "changed"),
            identity.copy(networkIdentity = "changed"),
        )
        changed.forEach { assertFalse(complete.isComplete(identity.runId, it)) }
        assertFalse(complete.isComplete("other-run", identity))
        assertFalse(complete.isComplete(null, identity))
    }

    @Test
    fun `cancellation and exceptions never become complete`() {
        assertThrows(CancellationException::class.java) {
            JmSpeedTestSubmission.commit(identity, true, { true }, { throw CancellationException() }, { true }, { identity.runId })
        }
        assertThrows(IllegalStateException::class.java) {
            JmSpeedTestSubmission.commit(identity, true, { true }, { true }, { error("save failed") }, { identity.runId })
        }
        for (state in JmSpeedTestSubmission.State.entries.filter { it != JmSpeedTestSubmission.State.COMPLETE }) {
            assertFalse(JmSpeedTestSubmission(identity, state, true, true).isComplete(identity.runId, identity))
        }
        assertFalse(JmSpeedTestSubmission(identity, JmSpeedTestSubmission.State.COMPLETE).isComplete(identity.runId, identity))
    }

    @Test
    fun `manual UI only labels the returned tested status as complete`() {
        assertTrue(jmSpeedTestIsComplete("tested"))
        listOf(null, "incomplete", "skipped", "reused", "quick", "", "unknown").forEach {
            assertFalse(jmSpeedTestIsComplete(it))
        }
    }
}
