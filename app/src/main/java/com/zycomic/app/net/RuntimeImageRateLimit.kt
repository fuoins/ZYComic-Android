package com.zycomic.app.net

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

/** Rate limits belong to a platform/host, not one page or one DNS address. */
internal object RuntimeImageRateLimit {
    private val policies = ConcurrentHashMap<RuntimeRecoveryPolicy.Platform, RuntimeRetryAfterPolicy>()

    fun record(platform: RuntimeRecoveryPolicy.Platform, response: Response) {
        if (response.code == 429) {
            policy(platform).record(DomainIp(response.request.url.host, ""), response.header("Retry-After"))
        }
    }

    fun blockedResponse(platform: RuntimeRecoveryPolicy.Platform, request: Request): Response? {
        val remaining = policy(platform).remainingDelayMs(DomainIp(request.url.host, ""))
        if (remaining <= 0L) return null
        val seconds = remaining / 1_000L + if (remaining % 1_000L == 0L) 0L else 1L
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("图源限流，请稍后重试")
            .header("Retry-After", seconds.toString())
            .body(ByteArray(0).toResponseBody(null))
            .build()
    }

    private fun policy(platform: RuntimeRecoveryPolicy.Platform): RuntimeRetryAfterPolicy =
        policies.computeIfAbsent(platform) { RuntimeRetryAfterPolicy() }
}
