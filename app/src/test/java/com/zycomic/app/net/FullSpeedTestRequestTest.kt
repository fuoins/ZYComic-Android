package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FullSpeedTestRequestTest {
    @Test
    fun `duplicates arriving while owner is queued share the eventual result`() = runBlocking {
        val request = FullSpeedTestRequest<Int>()
        val queued = CompletableDeferred<Unit>()
        val startWork = CompletableDeferred<Unit>()
        var calls = 0

        coroutineScope {
            val owner = async {
                request.run {
                    calls++
                    queued.complete(Unit)
                    startWork.await()
                    42
                }
            }
            queued.await()
            val duplicate = async(start = CoroutineStart.UNDISPATCHED) {
                request.run {
                    calls++
                    99
                }
            }
            assertEquals(1, calls)
            startWork.complete(Unit)
            assertEquals(42, withTimeout(1_000) { owner.await() })
            assertEquals(42, withTimeout(1_000) { duplicate.await() })
            assertEquals(1, calls)
        }
    }

    @Test
    fun `cancelling a duplicate waiter does not cancel the owner`() = runBlocking {
        val request = FullSpeedTestRequest<Int>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0

        coroutineScope {
            val owner = async {
                request.run {
                    calls++
                    started.complete(Unit)
                    release.await()
                    7
                }
            }
            started.await()
            val duplicate = launch(start = CoroutineStart.UNDISPATCHED) {
                request.run {
                    calls++
                    8
                }
            }
            duplicate.cancelAndJoin()
            assertFalse(owner.isCancelled)
            release.complete(Unit)
            assertEquals(7, withTimeout(1_000) { owner.await() })
            assertEquals(1, calls)
        }
    }

    @Test
    fun `owner cancellation releases pending request for a later caller`() = runBlocking {
        val request = FullSpeedTestRequest<Int>()
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()

        val owner = async {
            request.run {
                started.complete(Unit)
                never.await()
                1
            }
        }
        started.await()
        owner.cancelAndJoin()
        assertTrue(owner.isCancelled)
        assertEquals(2, request.run { 2 })
    }

    @Test
    fun `failure is shared with duplicates and clears pending request`() = runBlocking {
        val request = FullSpeedTestRequest<Int>()
        val started = CompletableDeferred<Unit>()
        val fail = CompletableDeferred<Unit>()
        val failure = IllegalStateException("probe failed")
        var calls = 0

        supervisorScope {
            val owner = async {
                request.run {
                    calls++
                    started.complete(Unit)
                    fail.await()
                    throw failure
                }
            }
            started.await()
            val duplicate = async(start = CoroutineStart.UNDISPATCHED) {
                request.run {
                    calls++
                    2
                }
            }
            fail.complete(Unit)
            val ownerError = runCatching { owner.await() }.exceptionOrNull()
            val duplicateError = runCatching { duplicate.await() }.exceptionOrNull()
            assertSame(failure, ownerError)
            assertSame(failure, duplicateError)
            assertEquals(1, calls)
        }

        assertEquals(3, request.run { 3 })
    }
}
