package com.zycomic.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/** Cancels only a probe's own socket, including a blocked response-body read, when its run retires. */
internal object SpeedTestProbeCall {
    fun <T> execute(call: Call, owner: Job?, consume: (Response) -> T): T {
        // Unconfined cancellation runs immediately even when the probe worker is blocked in socket IO.
        val cancellation = owner?.let {
            CoroutineScope(it).launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        }
        return try {
            owner?.ensureActive()
            call.execute().use(consume)
        } catch (error: Exception) {
            // Socket close caused by retiring this run is cancellation, not a new probe failure.
            owner?.ensureActive()
            throw error
        } finally {
            cancellation?.cancel()
        }
    }
}
