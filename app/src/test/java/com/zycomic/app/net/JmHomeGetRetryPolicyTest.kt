package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException

class JmHomeGetRetryPolicyTest {
    @Test
    fun `retries only first current pinned promote GET after transport disconnect`() {
        assertTrue(
            JmHomeGetRetryPolicy.shouldRetry(
                path = "/promote",
                method = "GET",
                attempt = 0,
                error = EOFException("unexpected end of stream"),
                endpointPinned = true,
                isCurrent = true,
            ),
        )
        assertTrue(
            JmHomeGetRetryPolicy.shouldRetry(
                path = "/promote_list",
                method = "GET",
                attempt = 0,
                error = IOException("socket closed", java.net.SocketException("Connection reset")),
                endpointPinned = true,
                isCurrent = true,
            ),
        )
    }

    @Test
    fun `rejects writes unrelated APIs subsequent attempts stale owners and unpinned routes`() {
        val transportError = EOFException("unexpected end of stream")
        fun eligible(path: String = "/promote", method: String = "GET", attempt: Int = 0, pinned: Boolean = true, current: Boolean = true) =
            JmHomeGetRetryPolicy.shouldRetry(path, method, attempt, transportError, pinned, current)

        assertFalse(eligible(method = "POST"))
        assertFalse(eligible(method = "PUT"))
        assertFalse(eligible(path = "/album"))
        assertFalse(eligible(path = "/setting"))
        assertFalse(eligible(attempt = 1))
        assertFalse(eligible(pinned = false))
        assertFalse(eligible(current = false))
    }

    @Test
    fun `does not retry HTTP decode or malformed protocol errors`() {
        assertFalse(
            JmHomeGetRetryPolicy.shouldRetry(
                "/promote",
                "GET",
                0,
                IOException("HTTP 503"),
                endpointPinned = true,
                isCurrent = true,
            ),
        )
        assertFalse(
            JmHomeGetRetryPolicy.shouldRetry(
                "/promote",
                "GET",
                0,
                IOException("invalid JSON"),
                endpointPinned = true,
                isCurrent = true,
            ),
        )
        assertFalse(
            JmHomeGetRetryPolicy.shouldRetry(
                "/promote",
                "GET",
                0,
                ProtocolException("unexpected protocol version"),
                endpointPinned = true,
                isCurrent = true,
            ),
        )
    }

    @Test
    fun `offline contract keeps route rebind from cancelling active calls and rebuilds GET call`() {
        val root = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
            .first { java.nio.file.Files.isDirectory(it.resolve("app/src/main/java/com/zycomic/app/net")) }
        val module = root.resolve("app/src/main/java/com/zycomic/app/net/JmNetworkModule.kt").toFile().readText()
        assertTrue(module.contains("fun rebindApiEndpoint() = prepareApiRoute(JmRouteManager.lineHost)"))
        assertTrue(module.contains("fun rebindImageEndpoint() = prepareImageRoute(JmRouteManager.activeImageDomain)"))
        val apiBinding = module.substringAfter("internal fun prepareApiRoute(host: String) {").substringBefore("\n    }")
        val imageBinding = module.substringAfter("internal fun prepareImageRoute(host: String) {").substringBefore("\n    }")
        assertTrue(apiBinding.contains("evictIdleEndpointPools()"))
        assertTrue(imageBinding.contains("evictIdleEndpointPools()"))
        assertTrue(apiBinding.contains("configuredLineUrl(host)"))
        assertTrue(imageBinding.contains("currentImageNetworkPolicy()"))
        assertFalse(module.substringAfter("fun rebindApiEndpoint() =").substringBefore("\n").contains("cancelAll"))
        assertFalse(module.substringAfter("fun rebindImageEndpoint() =").substringBefore("\n").contains("cancelAll"))
        val source = root.resolve("app/src/main/java/com/zycomic/app/reader/JmSource.kt").toFile().readText()
        assertTrue(source.contains("JmHomeGetRetryPolicy.run(path, \"GET\", requestScope"))
        assertTrue(source.contains("client.newCall(request).awaitSuccess()"))
        assertTrue(source.contains(".get().headers(headers).build()"))
        val policy = root.resolve("app/src/main/java/com/zycomic/app/net/JmHomeGetRetryPolicy.kt").toFile().readText()
        assertTrue(policy.contains("JmAuthStore.sessionRevision"))
        assertTrue(policy.contains("JmRouteManager.currentLineEndpoint(expectedHost)"))
        assertTrue(policy.contains("delay(BACKOFF_MS)"))
    }
}
