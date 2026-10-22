package com.zycomic.app.ui.browse

import com.zycomic.app.net.RuntimeRecoveryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/** Retries one first-page read at most once for a transport failure in this loading session. */
internal class ManwaFirstPageRetry(
    private val enabled: Boolean,
    private val isCurrent: () -> Boolean,
    private val waitBeforeRetry: suspend () -> Unit = { delay(500) },
) {
    private val retryUsed = AtomicBoolean(false)

    suspend fun <T> run(read: suspend () -> T): T {
        val value = try {
            readChecked(read)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!enabled || !RuntimeRecoveryPolicy.isTransportFailure(error) || !retryUsed.compareAndSet(false, true)) {
                throw error
            }

            checkCanContinue()
            waitBeforeRetry()
            checkCanContinue()
            return readChecked(read)
        }
        return value
    }

    private suspend fun <T> readChecked(read: suspend () -> T): T {
        checkCanContinue()
        try {
            val value = read()
            checkCanContinue()
            return value
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            checkCanContinue()
            throw error
        }
    }

    private suspend fun checkCanContinue() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Manwa first-page load is no longer current")
    }
}
