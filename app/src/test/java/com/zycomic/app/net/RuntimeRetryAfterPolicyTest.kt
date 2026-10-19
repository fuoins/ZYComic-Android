package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeRetryAfterPolicyTest {
    private val endpoint = DomainIp("api.example", "192.0.2.1")

    @Test
    fun `seconds deadline blocks only that endpoint and never shortens an existing wait`() {
        var now = 100L
        val policy = RuntimeRetryAfterPolicy(monotonicMs = { now }, epochMs = { 0L })
        policy.record(endpoint, "120")
        assertTrue(policy.isBlocked(endpoint))
        assertFalse(policy.isBlocked(endpoint.copy(ip = "192.0.2.2")))
        now = 1_100L
        policy.record(endpoint, "1")
        now = 120_099L
        assertTrue(policy.isBlocked(endpoint))
        now = 120_100L
        assertFalse(policy.isBlocked(endpoint))
    }

    @Test
    fun `HTTP date uses wall time once then waits on monotonic time`() {
        var now = 0L
        var wall = 0L
        val policy = RuntimeRetryAfterPolicy(monotonicMs = { now }, epochMs = { wall })
        policy.record(endpoint, "Thu, 1 Jan 1970 00:01:00 GMT")
        wall = 9_000_000L
        now = 59_999L
        assertTrue(policy.isBlocked(endpoint))
        now = 60_000L
        assertFalse(policy.isBlocked(endpoint))
    }

    @Test
    fun `missing malformed negative and expired values never create a block`() {
        val policy = RuntimeRetryAfterPolicy(monotonicMs = { 0L }, epochMs = { 120_000L })
        listOf(null, "", "invalid", "-1", "0", "Thu, 1 Jan 1970 00:01:00 GMT").forEach {
            policy.record(endpoint, it)
            assertFalse(policy.isBlocked(endpoint))
        }
    }
}
