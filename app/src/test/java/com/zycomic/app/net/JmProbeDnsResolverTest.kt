package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

class JmProbeDnsResolverTest {
    @Test
    fun `returns resolved addresses and converts lookup failure to no endpoints`() = runBlocking {
        assertEquals(listOf("192.0.2.1"), JmProbeDnsResolver(lookup = { listOf("192.0.2.1") }).resolve("api.example"))
        assertTrue(JmProbeDnsResolver(lookup = { throw java.net.UnknownHostException(it) }).resolve("missing.example").isEmpty())
    }

    @Test
    fun `timeout bounds waiting even when system resolver ignores interruption`() = runBlocking {
        val release = CountDownLatch(1)
        val resolver = JmProbeDnsResolver(timeoutMs = 100, lookup = {
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) { }
            }
            listOf("192.0.2.1")
        })
        try {
            assertTrue(withTimeout(2_000) { resolver.resolve("slow.example") }.isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `cancellation stops waiting without inventing successful DNS results`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val resolver = JmProbeDnsResolver(lookup = {
            started.complete(Unit)
            release.await()
            listOf("192.0.2.1")
        })
        val task = async { resolver.resolve("cancel.example") }
        try {
            withTimeout(2_000) { started.await() }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
        } finally {
            release.countDown()
        }
    }
}
