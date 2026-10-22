package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** API methods that are safe to retry after a transport failure or redirect. */
internal fun isSafeApiRead(method: String): Boolean = method.equals("GET", true) || method.equals("HEAD", true)

/**
 * Prevents a POST/other write from being repeated by redirects or hidden transport retry,
 * and makes an outcome uncertain after a transport error visible to the caller.
 */
internal class ApiWriteSafetyInterceptor(
    private val ambiguousMessage: () -> String = RuntimeNetworkGate::ambiguousWriteMessage,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (isSafeApiRead(request.method)) return chain.proceed(request)
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            throw ambiguousWrite(error)
        }
        val originalCode = response.header(UnsafeWriteRedirectNetworkInterceptor.ORIGINAL_STATUS_HEADER)?.toIntOrNull()
        val visibleResponse = if (originalCode != null) {
            response.newBuilder()
                .code(originalCode)
                .removeHeader(UnsafeWriteRedirectNetworkInterceptor.ORIGINAL_STATUS_HEADER)
                .build()
        } else {
            response
        }
        if (visibleResponse.code in 300..399 || visibleResponse.header(UnsafeWriteRedirectNetworkInterceptor.BLOCKED_HEADER) != null) {
            val code = visibleResponse.code
            visibleResponse.close()
            throw IOException("${ambiguousMessage()} (HTTP $code)")
        }
        val body = visibleResponse.body
        val guardedBody = body?.let {
            val guardedSource = object : ForwardingSource(it.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount)
                } catch (error: IOException) {
                    throw ambiguousWrite(error)
                }
            }.buffer()
            object : ResponseBody() {
                override fun contentType() = it.contentType()
                override fun contentLength() = it.contentLength()
                override fun source() = guardedSource
            }
        }
        return visibleResponse.newBuilder()
            .removeHeader(UnsafeWriteRedirectNetworkInterceptor.BLOCKED_HEADER)
            .apply { if (guardedBody != null) body(guardedBody) }
            .build()
    }

    private fun ambiguousWrite(cause: IOException) = IOException(ambiguousMessage(), cause)
}

/** Network interceptor removes redirect instructions before OkHttp can replay a write. */
internal class UnsafeWriteRedirectNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request()).newBuilder()
            .removeHeader(BLOCKED_HEADER)
            .removeHeader(ORIGINAL_STATUS_HEADER)
            .build()
        if (isSafeApiRead(response.request.method)) return response
        if (response.code in 300..399) {
            return response.newBuilder()
                .headers(response.headers.newBuilder().removeAll("Location").add(BLOCKED_HEADER, "true").build())
                .build()
        }
        if (response.code in setOf(408, 421, 503)) {
            return response.newBuilder()
                .code(400)
                .header(ORIGINAL_STATUS_HEADER, response.code.toString())
                .build()
        }
        return response
    }

    companion object {
        const val BLOCKED_HEADER = "X-ZYComic-Unsafe-Redirect-Blocked"
        const val ORIGINAL_STATUS_HEADER = "X-ZYComic-Original-Status"
    }
}
