package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class JmApiEndpointRecoveryTest {
    private val current = DomainIp("api.example", "192.0.2.1")
    private val next = DomainIp("api.example", "192.0.2.2")

    @Test
    fun `requires three consecutive transport failures and any HTTP response resets the streak`() {
        val recovery = JmApiEndpointRecovery<String>()

        assertFalse(recovery.recordConnectionFailure(current))
        assertFalse(recovery.recordConnectionFailure(current))
        assertTrue(recovery.recordConnectionFailure(current))
        recovery.recordResponse(current)
        assertFalse(recovery.recordConnectionFailure(current))
        assertFalse(recovery.hasFailureThreshold(current))
    }

    @Test
    fun `validates only captured endpoint then commits once`() {
        val clock = AtomicLong(0)
        val recovery = JmApiEndpointRecovery<String>(cooldownMs = 100, nowMs = clock::get)
        val validated = mutableListOf<DomainIp>()
        val committed = mutableListOf<JmApiEndpointRecovery.Candidate<String>>()

        assertTrue(
            recovery.tryRecover(
                capture = { JmApiEndpointRecovery.Candidate(next, "unchanged-config") },
                validate = { endpoint ->
                    validated += endpoint
                    true
                },
                commit = { candidate ->
                    committed += candidate
                    true
                },
            ),
        )

        assertEquals(listOf(next), validated)
        assertEquals(listOf(JmApiEndpointRecovery.Candidate(next, "unchanged-config")), committed)
        assertFalse(
            recovery.tryRecover(
                capture = { JmApiEndpointRecovery.Candidate(current, "unchanged-config") },
                validate = {
                    validated += it
                    true
                },
                commit = { true },
            ),
            "cooldown must prevent probing a second endpoint",
        )
        assertEquals(listOf(next), validated)
    }

    @Test
    fun `failed validation and rejected atomic commit both remain in cooldown`() {
        val clock = AtomicLong(0)
        val recovery = JmApiEndpointRecovery<String>(cooldownMs = 100, nowMs = clock::get)
        val probes = AtomicInteger()
        val commits = AtomicInteger()
        val capture = { JmApiEndpointRecovery.Candidate(next, "route-revision-7") }

        assertFalse(
            recovery.tryRecover(capture, {
                probes.incrementAndGet()
                false
            }, {
                commits.incrementAndGet()
                true
            }),
        )
        assertFalse(
            recovery.tryRecover(capture, {
                probes.incrementAndGet()
                true
            }, {
                commits.incrementAndGet()
                true
            }),
        )
        assertEquals(1, probes.get(), "a failed health check must not create a retry storm")
        assertEquals(0, commits.get())

        clock.set(100)
        assertFalse(
            recovery.tryRecover(capture, {
                probes.incrementAndGet()
                true
            }, { candidate ->
                assertEquals("route-revision-7", candidate.state)
                commits.incrementAndGet()
                false
            }),
            "a stale atomic guard must reject switching even after a successful probe",
        )
        assertEquals(2, probes.get())
        assertEquals(1, commits.get())
    }

    @Test
    fun `concurrent failures share a single in flight health probe`() {
        val clock = AtomicLong(0)
        val recovery = JmApiEndpointRecovery<String>(cooldownMs = 100, nowMs = clock::get)
        val enteredProbe = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val probes = AtomicInteger()
        val ownerResult = AtomicInteger()
        val owner = Thread {
            val result = recovery.tryRecover(
                capture = { JmApiEndpointRecovery.Candidate(next, "same-route") },
                validate = {
                    probes.incrementAndGet()
                    enteredProbe.countDown()
                    releaseProbe.await(2, TimeUnit.SECONDS)
                    true
                },
                commit = { true },
            )
            ownerResult.set(if (result) 1 else -1)
        }
        owner.start()
        assertTrue(enteredProbe.await(2, TimeUnit.SECONDS))
        assertFalse(
            recovery.tryRecover(
                capture = { JmApiEndpointRecovery.Candidate(current, "same-route") },
                validate = {
                    probes.incrementAndGet()
                    true
                },
                commit = { true },
            ),
        )
        releaseProbe.countDown()
        owner.join(2_000)

        assertEquals(1, probes.get())
        assertEquals(1, ownerResult.get())
    }
}
