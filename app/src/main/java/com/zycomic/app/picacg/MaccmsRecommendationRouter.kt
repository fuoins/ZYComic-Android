package com.zycomic.app.picacg

import com.zycomic.app.net.MaccmsSpeedTester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Routes anonymous Maccms recommendation requests across its own fixed host pool. */
object MaccmsRecommendationRouter {
    suspend fun recommendationIds(comicId: String): List<String> {
        val selection = MaccmsSpeedTester.routeSelection()
        val ip = selection.lines3Ip ?: throw IOException("No current-run lines3 IP; Maccms not tested")
        return recommendationIds(comicId, selection) { host, id ->
            MaccmsRecommendationClient("https://$host/".toHttpUrl(), ip).recommendationIds(id)
        }
    }

    /** Same routing path as production, with only anonymous request transport replaceable in tests. */
    internal suspend fun recommendationIds(
        comicId: String,
        fetch: suspend (String, String) -> List<String>,
    ): List<String> = recommendationIds(comicId, MaccmsSpeedTester.routeSelection(), fetch)

    private suspend fun recommendationIds(
        comicId: String,
        initialSelection: MaccmsSpeedTester.RouteSelection,
        fetch: suspend (String, String) -> List<String>,
    ): List<String> {
        val order = listOf(initialSelection.route) + MaccmsSpeedTester.routes.filterNot { it == initialSelection.route }
        var expectedGeneration: Long? = initialSelection.generation
        var lastFailure: Throwable? = null
        for (host in order) {
            currentCoroutineContext().ensureActive()
            if (initialSelection.lines3Ip != null &&
                (expectedGeneration == null || MaccmsSpeedTester.routeSelection().generation != expectedGeneration)
            ) {
                throw IOException("Maccms lines3 selection was superseded; no stale-IP retry")
            }
            val started = System.nanoTime()
            try {
                val result = fetch(host, comicId)
                currentCoroutineContext().ensureActive()
                MaccmsSpeedTester.recordRuntime(
                    route = host,
                    latencyMs = elapsed(started),
                    expectedGeneration = expectedGeneration,
                )
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MaccmsRecommendationHttpException) {
                currentCoroutineContext().ensureActive()
                val status = failure.statusCode
                val shouldFailover = status in 500..599
                expectedGeneration = MaccmsSpeedTester.recordRuntime(
                    route = host,
                    latencyMs = elapsed(started),
                    statusCode = status,
                    failure = failure,
                    failover = shouldFailover,
                    expectedGeneration = expectedGeneration,
                )
                if (!shouldFailover) throw failure
                lastFailure = failure
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                val transportFailure = failure.isTransportFailure()
                expectedGeneration = MaccmsSpeedTester.recordRuntime(
                    route = host,
                    latencyMs = elapsed(started),
                    failure = failure,
                    failover = transportFailure,
                    expectedGeneration = expectedGeneration,
                )
                if (!transportFailure) throw failure
                lastFailure = failure
            }
        }
        throw lastFailure ?: IOException("No Maccms recommendation route is available")
    }

    private fun elapsed(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun IOException.isTransportFailure(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is SocketTimeoutException || cause is ConnectException || cause is UnknownHostException ||
                cause is SocketException || cause is SSLException || cause is java.io.InterruptedIOException
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}
