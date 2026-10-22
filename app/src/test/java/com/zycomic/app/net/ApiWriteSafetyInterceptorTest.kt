package com.zycomic.app.net

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ApiWriteSafetyInterceptorTest {
    @Test
    fun `POST redirects are never followed and surface uncertain outcome`() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            withServer(code, mapOf("Location" to "/again")) { url, count ->
                val error = runCatching { safeClient().newCall(post(url)).execute().close() }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error?.message.orEmpty().contains("可能已成功，请刷新确认"))
                assertEquals(1, count.get(), "HTTP $code must not replay the POST")
            }
        }
    }

    @Test
    fun `GET and HEAD redirects remain followable`() {
        for (method in listOf("GET", "HEAD")) {
            withServer(302, mapOf("Location" to "/again")) { url, count ->
                val request = Request.Builder().url(url).method(method, null).build()
                safeClient().newCall(request).execute().use { assertEquals(200, it.code) }
                assertEquals(2, count.get())
            }
        }
    }

    @Test
    fun `408 421 and 503 retry after zero do not replay writes and retain status`() {
        for (code in listOf(408, 421, 503)) {
            withServer(code, mapOf("Retry-After" to "0")) { url, count ->
                safeClient().newCall(post(url)).execute().use { assertEquals(code, it.code) }
                assertEquals(1, count.get())
            }
        }
    }

    @Test
    fun `write transport failure after server receives body sends only once`() {
        rawDisconnect(false)
    }

    @Test
    fun `write response body disconnect reports possible success without replay`() {
        rawDisconnect(true)
    }

    private fun rawDisconnect(partialResponse: Boolean) {
        ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 500
            val count = AtomicInteger()
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    count.incrementAndGet()
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    if (partialResponse) {
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nabc".toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
                try {
                    server.accept().use { count.incrementAndGet() }
                } catch (_: SocketTimeoutException) {
                    // No second connection is the expected result.
                }
            }
            val error = runCatching {
                safeClient().newCall(post("http://127.0.0.1:${server.localPort}/write")).execute().use { it.body!!.string() }
            }.exceptionOrNull()
            worker.join(3_000)
            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("可能已成功，请刷新确认"))
            assertEquals(1, count.get())
        }
    }

    private fun withServer(code: Int, headers: Map<String, String>, block: (String, AtomicInteger) -> Unit) {
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val first = count.incrementAndGet() == 1
            exchange.requestBody.use { it.readBytes() }
            if (first) headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(if (first) code else 200, -1)
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/write", count)
        } finally {
            server.stop(0)
        }
    }

    private fun post(url: String) = Request.Builder().url(url).post("body".toRequestBody()).build()

    private fun safeClient() = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .callTimeout(3, TimeUnit.SECONDS)
        .addInterceptor(ApiWriteSafetyInterceptor { "请求结果不确定，可能已成功，请刷新确认" })
        .addNetworkInterceptor(UnsafeWriteRedirectNetworkInterceptor())
        .build()
}
