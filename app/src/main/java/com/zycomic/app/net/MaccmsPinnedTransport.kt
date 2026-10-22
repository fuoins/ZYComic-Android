package com.zycomic.app.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Per-request/run binding: never changes URL/Host, TLS verification or SNI, and never resolves DNS. */
internal object MaccmsPinnedTransport {
    /** Cancellation closes the socket even while a response body is being consumed. */
    suspend fun <T> request(client: OkHttpClient, request: Request, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use(consume)
                        continuation.resume(result)
                    } catch (failure: Exception) {
                        continuation.resumeWithException(failure)
                    }
                }
            })
        }

    fun client(base: OkHttpClient, host: String, lines3Ip: String?): OkHttpClient = base.newBuilder()
        .dns(dns(host, lines3Ip))
        // A system HTTP proxy would resolve the hostname itself, bypassing the selected TCP IP.
        .proxy(Proxy.NO_PROXY)
        // A prior IP's pooled connection must not escape a new round's binding.
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    fun dns(host: String, lines3Ip: String?): Dns = Dns { requestedHost ->
        if (requestedHost != host || host !in MaccmsSpeedTester.routes) {
            throw UnknownHostException("Unexpected Maccms host: $requestedHost")
        }
        listOf(address(lines3Ip) ?: throw UnknownHostException("No current-run lines3 IP; Maccms not tested"))
    }

    fun address(ip: String?): InetAddress? {
        val literal = ip?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ipv4 = literal.split('.').let { parts ->
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull() in 0..255
            }
        }
        val ipv6 = ':' in literal && literal.all { it in "0123456789abcdefABCDEF:." }
        if (!ipv4 && !ipv6) return null
        // Only numeric literals reach getByName; hostnames and zone IDs cannot trigger DNS here.
        return try {
            InetAddress.getByName(literal)
        } catch (_: UnknownHostException) {
            null
        }
    }
}
