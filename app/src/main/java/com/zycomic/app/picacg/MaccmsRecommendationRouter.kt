package com.zycomic.app.picacg

import com.zycomic.app.net.MaccmsSpeedTester
import kotlinx.coroutines.CancellationException
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
    private val clients = MaccmsSpeedTester.routes.associateWith { host ->
        MaccmsRecommendationClient("https://$host/".toHttpUrl())
    }

    suspend fun recommendationIds(comicId: String): List<String> =
        recommendationIds(comicId) { host, id -> clients.getValue(host).recommendationIds(id) }

    /** Same routing path as production, with only anonymous request transport replaceable in tests. */
    internal suspend fun recommendationIds(
        comicId: String,
        fetch: suspend (String, String) -> List<String>,
    ): List<String> {
        val initialSelection = MaccmsSpeedTester.routeSelection()
        val order = listOf(initialSelection.route) + MaccmsSpeedTester.routes.filterNot { it == initialSelection.route }
        var expectedGeneration: Long? = initialSelection.generation
        var lastFailure: Throwable? = null
        for (host in order) {
            val started = System.nanoTime()
            try {
                val result = fetch(host, comicId)
                MaccmsSpeedTester.recordRuntime(
                    route = host,
                    latencyMs = elapsed(started),
                    expectedGeneration = expectedGeneration,
                )
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MaccmsRecommendationHttpException) {
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
