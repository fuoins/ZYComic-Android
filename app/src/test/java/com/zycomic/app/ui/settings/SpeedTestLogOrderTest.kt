package com.zycomic.app.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeedTestLogOrderTest {
    private data class Row(val domain: String, val ip: String, val delay: Long, val fresh: Boolean = true, val ok: Boolean = true)

    private fun rank(rows: List<Row>) = orderSpeedTestLogRows(
        rows = rows,
        testedThisRound = { it.fresh },
        successful = { it.ok },
        elapsedMs = { it.delay },
        domain = { it.domain },
        ip = { it.ip },
    )

    @Test
    fun `historical fastest result cannot outrank this rounds failed measurement`() {
        val historical = Row("old.example", "1", 1L, fresh = false)
        val failed = Row("failed.example", "2", Long.MAX_VALUE, ok = false)
        val fresh = Row("fresh.example", "3", 80L)
        assertEquals(listOf(fresh, failed, historical), rank(listOf(historical, failed, fresh)))
    }

    @Test
    fun `fresh successes rank by latency then case insensitive domain and IP`() {
        val slow = Row("a.example", "1", 60L)
        val secondIp = Row("A.example", "2", 20L)
        val firstIp = Row("a.EXAMPLE", "1", 20L)
        val secondDomain = Row("b.example", "1", 20L)
        assertEquals(listOf(firstIp, secondIp, secondDomain, slow), rank(listOf(slow, secondDomain, secondIp, firstIp)))
    }

    @Test
    fun `fresh failures rank by endpoint rather than misleading short failure latency`() {
        val quickFailure = Row("z.example", "1", 1L, ok = false)
        val timeout = Row("a.example", "2", Long.MAX_VALUE, ok = false)
        val noImage = Row("b.example", "1", 4L, ok = false)
        assertEquals(listOf(timeout, noImage, quickFailure), rank(listOf(quickFailure, noImage, timeout)))
    }

    @Test
    fun `history retains its published order including old failures`() {
        val oldFailure = Row("z.example", "1", Long.MAX_VALUE, fresh = false, ok = false)
        val oldFast = Row("a.example", "1", 1L, fresh = false)
        val oldSlow = Row("b.example", "1", 80L, fresh = false)
        val input = listOf(oldFailure, oldFast, oldSlow)
        assertEquals(input, rank(input))
    }

    @Test
    fun `empty logs stay empty`() {
        assertEquals(emptyList<Row>(), rank(emptyList()))
    }
}
