package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StartupCheckStatusTest {
    @Test
    fun `six stages start in fixed order with zero passes`() {
        val statuses = initialStartupCheckStatuses()

        assertEquals(
            listOf(
                StartupCheckPhase.MANWA_LINE,
                StartupCheckPhase.MANWA_IMAGE,
                StartupCheckPhase.PICACG_LINE,
                StartupCheckPhase.PICACG_IMAGE,
                StartupCheckPhase.JM_LINE,
                StartupCheckPhase.JM_IMAGE,
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
}
