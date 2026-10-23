package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StartupCheckStatusTest {
    @Test
    fun `seven stages start in fixed order with zero passes`() {
        val statuses = initialStartupCheckStatuses()

        assertEquals(
            listOf(
                StartupCheckPhase.MANWA_LINE,
                StartupCheckPhase.MANWA_IMAGE,
                StartupCheckPhase.PICACG_LINE,
                StartupCheckPhase.PICACG_IMAGE,
                StartupCheckPhase.JM_LINE,
                StartupCheckPhase.JM_IMAGE,
                StartupCheckPhase.MACCMS_LINE,
            ),
            statuses.map { it.phase },
        )
        assertEquals(0, startupChecksPassed(statuses))
    }

    @Test
    fun `only explicit current round successes increment pass count`() {
        val initial = initialStartupCheckStatuses()
        val failed = updateStartupCheckStatus(initial, StartupCheckPhase.MANWA_LINE, StartupCheckResult.FAILED)
        val disabled = updateStartupCheckStatus(failed, StartupCheckPhase.MANWA_IMAGE, StartupCheckResult.DISABLED)
        val blocked = updateStartupCheckStatus(disabled, StartupCheckPhase.PICACG_IMAGE, StartupCheckResult.BLOCKED)
        val notTested = updateStartupCheckStatus(blocked, StartupCheckPhase.JM_LINE, StartupCheckResult.NOT_TESTED)

        assertEquals(0, startupChecksPassed(notTested))
        val passed = updateStartupCheckStatus(notTested, StartupCheckPhase.PICACG_LINE, StartupCheckResult.SUCCESS)
        assertEquals(1, startupChecksPassed(passed))
        assertEquals(StartupCheckResult.BLOCKED, passed.first { it.phase == StartupCheckPhase.PICACG_IMAGE }.result)
    }

    @Test
    fun `all non ready states keep fixed denominator without counting as passes`() {
        StartupCheckResult.entries.filter { !it.isReady }.forEach { result ->
            val statuses = initialStartupCheckStatuses().map { it.copy(result = result) }
            assertEquals(0, startupChecksPassed(statuses), result.name)
            assertEquals(0f, startupChecksProgress(statuses), result.name)
        }
        val partial = updateStartupCheckStatus(initialStartupCheckStatuses(), StartupCheckPhase.JM_LINE, StartupCheckResult.SUCCESS)
        assertEquals(1f / 7f, startupChecksProgress(partial))
        assertEquals(1f, startupChecksProgress(initialStartupCheckStatuses().map { it.copy(result = StartupCheckResult.SUCCESS) }))
    }

    @Test
    fun `reused history is ready progress but remains distinct from fresh success and failure`() {
        assertEquals("历史有效沿用", StartupCheckResult.REUSED.label)
        assertEquals(true, StartupCheckResult.REUSED.isReady)
        assertEquals(true, StartupCheckResult.SUCCESS.isReady)
        listOf(
            StartupCheckResult.FAILED,
            StartupCheckResult.DISABLED,
            StartupCheckResult.BLOCKED,
            StartupCheckResult.NO_SAMPLE,
            StartupCheckResult.INTERRUPTED,
        ).forEach { assertEquals(false, it.isReady, it.name) }

        val reused = updateStartupCheckStatus(
            initialStartupCheckStatuses(),
            StartupCheckPhase.MANWA_LINE,
            StartupCheckResult.REUSED,
        )
        assertEquals(1, startupChecksPassed(reused))
        assertEquals(1f / 7f, startupChecksProgress(reused))
        assertEquals(StartupCheckResult.REUSED, reused.first { it.phase == StartupCheckPhase.MANWA_LINE }.result)
    }

    @Test
    fun `new round clears prior successes and duplicate phases do not advance twice`() {
        val old = initialStartupCheckStatuses().map { it.copy(result = StartupCheckResult.SUCCESS) }
        assertEquals(7, startupChecksPassed(old + old))
        assertEquals(0f, startupChecksProgress(initialStartupCheckStatuses()))
        val current = updateStartupCheckStatus(initialStartupCheckStatuses(), StartupCheckPhase.PICACG_IMAGE, StartupCheckResult.NO_SAMPLE)
        assertEquals(StartupCheckResult.NO_SAMPLE, current.first { it.phase == StartupCheckPhase.PICACG_IMAGE }.result)
        assertEquals(0, startupChecksPassed(current))
    }

    @Test
    fun `maccms success does not change picacg api or image check states`() {
        val initial = initialStartupCheckStatuses()
        val updated = updateStartupCheckStatus(initial, StartupCheckPhase.MACCMS_LINE, StartupCheckResult.SUCCESS)
        assertEquals(initial.filter { it.phase != StartupCheckPhase.MACCMS_LINE }, updated.filter { it.phase != StartupCheckPhase.MACCMS_LINE })
        assertEquals(1, startupChecksPassed(updated))
        assertEquals(1f / 7f, startupChecksProgress(updated))
    }
}
