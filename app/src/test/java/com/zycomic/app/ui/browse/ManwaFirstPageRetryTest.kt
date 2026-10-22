package com.zycomic.app.ui.browse

import com.zycomic.app.net.RuntimeRecoveryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.CancellationException

class ManwaFirstPageRetryTest {
    @Test
    fun `successful read is invoked once`() = runBlocking {
        var reads = 0
        val retry = ManwaFirstPageRetry(enabled = true, isCurrent = { true }, waitBeforeRetry = {})

        assertEquals(
            "page",
            retry.run {
                reads++
                "page"
            },
        )
        assertEquals(1, reads)
    }

    @Test
    fun `socket abort is retried once and returns the successful read`() = runBlocking {
        var reads = 0
        var waits = 0
        val retry = ManwaFirstPageRetry(enabled = true, isCurrent = { true }) { waits++ }

        assertEquals(
            "page",
            retry.run {
                reads++
                if (reads == 1) throw SocketException("connection reset")
                "page"
            },
        )
        assertEquals(2, reads)
        assertEquals(1, waits)
    }

    @Test
    fun `persistent socket failure is not retried more than once`() = runBlocking {
        var reads = 0
        val failure = SocketException("connection reset")
        val retry = ManwaFirstPageRetry(enabled = true, isCurrent = { true }, waitBeforeRetry = {})

        val thrown = assertThrows(SocketException::class.java) {
            runBlocking {
                retry.run<String> {
                    reads++
                    throw failure
                }
            }
        }
        assertSame(failure, thrown)
        assertEquals(2, reads)
    }

    @Test
    fun `wrapped socket failure is recognized as transport failure`() = runBlocking {
        var reads = 0
        val retry = ManwaFirstPageRetry(enabled = true, isCurrent = { true }, waitBeforeRetry = {})

        assertEquals(
            "recovered",
            retry.run {
                reads++
                if (reads == 1) throw IllegalStateException("wrapped", SocketException("connection reset"))
                "recovered"
            },
        )
        assertEquals(2, reads)
    }

    @Test
    fun `generic IO and HTTP failures do not retry`() = runBlocking {
        for (failure in listOf(IOException("disk read failed"), IOException("HTTP 503"))) {
            var reads = 0
            var waits = 0
            val retry = ManwaFirstPageRetry(enabled = true, isCurrent = { true }) { waits++ }

            val thrown = assertThrows(IOException::class.java) {
                runBlocking {
                    retry.run<String> {
                        reads++
                        throw failure
                    }
                }
            }
            assertSame(failure, thrown)
            assertEquals(1, reads)
            assertEquals(0, waits)
            assertTrue(!RuntimeRecoveryPolicy.isTransportFailure(failure))
        }
    }

    @Test
    fun `disabled recovery does not retry a transport failure`() = runBlocking {
        var reads = 0
        val failure = SocketException("connection reset")
        val retry = ManwaFirstPageRetry(enabled = false, isCurrent = { true }, waitBeforeRetry = {})

        assertSame(
            failure,
            assertThrows(SocketException::class.java) {
                runBlocking {
                    retry.run<String> {
                        reads++
                        throw failure
                    }
                }
            },
        )
        assertEquals(1, reads)
    }

    @Test
    fun `cancellation from read is rethrown without retry`() = runBlocking {
        var reads = 0
        val cancellation = CancellationException("cancelled")
        val retry = ManwaFirstPageRetry(enabled = true, isCurrent = { true }, waitBeforeRetry = {})

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                retry.run<String> {
                    reads++
                    throw cancellation
                }
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(1, reads)
    }

    @Test
    fun `cancellation during retry wait prevents the second read`() = runBlocking {
        var reads = 0
        val cancellation = CancellationException("wait cancelled")
        val retry = ManwaFirstPageRetry(enabled = true, isCurrent = { true }) { throw cancellation }

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                retry.run<String> {
                    reads++
                    throw SocketException("connection reset")
                }
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(1, reads)
    }

    @Test
    fun `stale load is rejected before read and after read failure`() = runBlocking {
        var reads = 0
        val staleBeforeRead = ManwaFirstPageRetry(enabled = true, isCurrent = { false }, waitBeforeRetry = {})
        assertThrows(CancellationException::class.java) {
            runBlocking {
                staleBeforeRead.run {
                    reads++
                    "page"
                }
            }
        }
        assertEquals(0, reads)

        var current = true
        val staleDuringRead = ManwaFirstPageRetry(enabled = true, isCurrent = { current }, waitBeforeRetry = {})
        assertThrows(CancellationException::class.java) {
            runBlocking {
                staleDuringRead.run<String> {
                    reads++
                    current = false
                    throw SocketException("connection reset")
                }
            }
        }
        assertEquals(1, reads)
    }

    @Test
    fun `load becoming stale during retry wait is rejected before second read`() = runBlocking {
        var current = true
        var reads = 0
        val retry = ManwaFirstPageRetry(enabled = true, isCurrent = { current }) { current = false }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                retry.run<String> {
                    reads++
                    throw SocketException("connection reset")
                }
            }
        }
        assertEquals(1, reads)
    }

    @Test
    fun `retry budget is shared across runs and does not repeat a successful page`() = runBlocking {
        var waits = 0
        val calls = mutableListOf<String>()
        val retry = ManwaFirstPageRetry(enabled = true, isCurrent = { true }) { waits++ }

        assertEquals(
            "page-1",
            retry.run {
                calls += "page-1"
                "page-1"
            },
        )
        var pageTwoAttempts = 0
        assertEquals(
            "page-2",
            retry.run {
                calls += "page-2"
                pageTwoAttempts++
                if (pageTwoAttempts == 1) throw SocketException("connection reset")
                "page-2"
            },
        )
        val failure = SocketException("later page reset")
        assertSame(
            failure,
            assertThrows(SocketException::class.java) {
                runBlocking {
                    retry.run<String> {
                        calls += "page-3"
                        throw failure
                    }
                }
            },
        )

        assertEquals(listOf("page-1", "page-2", "page-2", "page-3"), calls)
        assertEquals(1, waits)
    }
}
