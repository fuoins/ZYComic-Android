package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeedTestLogSummaryTest {
    private fun result(fresh: Boolean, success: Boolean, quick: Boolean = false) =
        SpeedTestLogSummary.Result(fresh, success, quick)

    @Test
    fun `four successful quick probes out of five exclude historical successes`() {
        val probes = List(4) { result(true, true, true) } + result(true, false, true) +
            List(7) { result(false, true) }

        assertEquals("本轮快测：本轮成功 4/5；历史沿用 7 条（本轮未测）", SpeedTestLogSummary(probes).text())
    }

    @Test
    fun `all failed quick probes remain zero successes despite usable history`() {
        val probes = List(5) { result(true, false, true) } + result(false, true)

        assertEquals("本轮快测：本轮成功 0/5；历史沿用 1 条（本轮未测）", SpeedTestLogSummary(probes).text())
    }

    @Test
    fun `reused results never claim fresh tests or retain old quick status`() {
        val probes = listOf(result(false, true, true), result(false, false))

        assertEquals("本轮未测：本轮成功 0/0；历史沿用 2 条（本轮未测）", SpeedTestLogSummary(probes).text())
    }

    @Test
    fun `full round counts only fresh measurements`() {
        val probes = listOf(result(true, true), result(true, false))

        assertEquals("本轮测速：本轮成功 1/2；历史沿用 0 条（本轮未测）", SpeedTestLogSummary(probes).text())
    }

    @Test
    fun `configuration reconciliation keeps historical and fresh entries separate`() {
        val probes = listOf(result(true, true), result(false, true), result(false, false))

        assertEquals("本轮测速：本轮成功 1/1；历史沿用 2 条（本轮未测）", SpeedTestLogSummary(probes).text())
    }

    @Test
    fun `empty phase never claims it ran`() {
        assertEquals("本轮未测：本轮成功 0/0；历史沿用 0 条（本轮未测）", SpeedTestLogSummary(emptyList()).text())
    }
}
