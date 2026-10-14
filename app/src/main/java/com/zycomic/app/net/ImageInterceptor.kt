package com.zycomic.app.net

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource

/**
 * 图片拦截器：加请求头（含 Referer 防盗链）+ 加密漫画图片解密 + 加密图片磁盘缓存。
 *
 * 失败兜底按测速成功的「域名 + IP」端点顺序尝试：每个请求最多尝试 3 个未冷却端点，
 * 不在同一端点做额外重试。传输层 IOException 会让该端点冷却 30 分钟；HTTP 响应和非图片内容
 * 只在当前图片请求中跳过，不会影响后续页。手动 pin 仍是本次请求首选，不会被回退结果改写。
 */
class ImageInterceptor : Interceptor {

    companion object {
        private const val HEADER_X_REQUESTED_WITH = "X-Requested-With"
        private const val VALUE_X_REQUESTED_WITH = "zycomic.com"
        private const val MAX_ENDPOINT_ATTEMPTS = 3
        private const val INVALID_IMAGE_STATUS = 502
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val originalUrl = original.url.toString()
        if (!isImageRequest(originalUrl)) return chain.proceed(original)

        // 手动 pin 优先，否则使用自动测速结果；无测速结论时保留服务端原始域名。
        val imgTarget = RouteManager.imgRewriteTarget
        val fetchUrl = if (!imgTarget.isNullOrBlank()) {
            originalUrl.replace(Regex("https?://[^/]+"), "https://$imgTarget")
        } else originalUrl

        // 缓存命中后仍需解密并校验魔数；坏缓存删除后继续尝试网络。
        ImageCacheManager.get(originalUrl)?.let { cached ->
            val bytes = try { cached.readBytes() } catch (_: Exception) { null }
            if (bytes != null) {
                val out = decryptIfNeeded(bytes, originalUrl)
                if (isImageMagic(out)) return buildResponse(original, out)
            }
            try { cached.delete() } catch (_: Exception) {}
        }

        val imgReq = original.newBuilder()
            .url(fetchUrl)
            .header("User-Agent", ManwaInterceptor.UA)
            .header(HEADER_X_REQUESTED_WITH, VALUE_X_REQUESTED_WITH)
            // 官方客户端对所有图域使用固定 canonical Referer。
            .header("Referer", ManwaInterceptor.REFERER)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()

        // 首选端点之后接测速排名；无测速端点时，先把当前域名解析成一个精确 IP 作为首选。
        // 冷却过滤发生在 take(3) 前，因此重试会自然接着未冷却的排名继续分批尝试。
        val endpoints = mutableListOf<DomainIp>()
        for (candidate in RouteManager.imageRetryEndpoints(imgReq.url.host)) {
            val endpoint = candidate ?: NetworkModule.resolveProbeIps(imgReq.url.host)
                .firstOrNull()
                ?.let { DomainIp(imgReq.url.host, it) }
            if (endpoint != null && endpoint !in endpoints && RouteManager.isImgEndpointAvailable(endpoint)) {
                endpoints += endpoint
                if (endpoints.size == MAX_ENDPOINT_ATTEMPTS) break
            }
        }
        if (endpoints.isEmpty()) {
            throw IOException("没有未冷却的测速图源端点：$originalUrl")
        }

        var lastFailure: Outcome.Fail? = null
        for (endpoint in endpoints) {
            val request = replaceHost(imgReq, endpoint.domain)
            when (val outcome = proceedOnce(request, originalUrl, endpoint)) {
                is Outcome.Success -> return buildResponse(original, outcome.bytes)
                is Outcome.Fail -> {
                    // 仅网络/传输层失败跨页冷却；404、其他 HTTP 响应及非图片仅跳过本次请求。
                    if (outcome is Outcome.Fail.IO) RouteManager.markImgEndpointFailed(endpoint)
                    lastFailure = outcome
                }
            }
        }

        return when (val failure = lastFailure) {
            is Outcome.Fail.Http -> errorResponse(original, failure.code)
            is Outcome.Fail.InvalidImage -> errorResponse(original, INVALID_IMAGE_STATUS)
            is Outcome.Fail.IO -> throw failure.error
            null -> throw IOException("图片加载失败：$originalUrl")
        }
    }

    /** 单次精确端点尝试：网络 client 只解析到该 IP，且关闭 OkHttp 同端点自动重试。 */
    private fun proceedOnce(
        request: Request,
        originalUrl: String,
        endpoint: DomainIp,
    ): Outcome {
        val client = NetworkModule.newImageClientForIp(endpoint.domain, endpoint.ip)
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Outcome.Fail.Http(response.code)

                val body = response.body ?: return Outcome.Fail.InvalidImage(response.code)
                val raw = body.source().readByteArray()
                if (raw.isEmpty()) return Outcome.Fail.InvalidImage(response.code)

                val out = decryptIfNeeded(raw, originalUrl)
                if (!isImageMagic(out)) return Outcome.Fail.InvalidImage(response.code)

                // 只有校验为有效图片才写加密缓存。
                try {
                    ImageCacheManager.putStream(originalUrl).use { it.write(raw) }
                    ImageCacheManager.commit(originalUrl)
                } catch (_: Exception) {}

                Outcome.Success(out)
            }
        } catch (io: IOException) {
            Outcome.Fail.IO(io)
        } catch (e: Exception) {
            Outcome.Fail.IO(IOException("图片端点请求失败：${endpoint.domain} (${endpoint.ip})", e))
        } finally {
            // 此客户端为单端点临时客户端，清理其独立连接池，避免下一候选复用其他 IP 的连接。
            runCatching { client.connectionPool.evictAll() }
        }
    }

    private fun replaceHost(request: Request, host: String): Request {
        val newUrl = request.url.toString().replace(Regex("https?://[^/]+"), "https://$host")
        return request.newBuilder().url(newUrl).build()
    }

    private fun buildResponse(request: Request, bytes: ByteArray): Response {
        val body = object : ResponseBody() {
            override fun contentType() = "image/webp".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun source(): BufferedSource = Buffer().apply { write(bytes) }
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body(body).build()
    }

    private fun errorResponse(request: Request, code: Int): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code).message("img fail")
            .body(ByteArray(0).toResponseBody(null))
            .build()

    /** 已加密则解密；原始即图片则原样返回。调用方负责用 [isImageMagic] 校验结果。 */
    private fun decryptIfNeeded(raw: ByteArray, url: String): ByteArray {
        if (isImageMagic(raw)) return raw
        return try { Crypto.createImageCipher().doFinal(raw) } catch (_: Exception) { raw }
    }

    private fun isImageRequest(url: String): Boolean =
        url.contains("/static/upload") ||
            url.contains(".webp") || url.contains(".jpg") ||
            url.contains(".png") || url.contains(".gif")

    private fun isImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes.size >= 12 && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) return true
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()) return true
        return false
    }

    private sealed class Outcome {
        class Success(val bytes: ByteArray) : Outcome()
        sealed class Fail : Outcome() {
            class Http(val code: Int) : Fail()
            class InvalidImage(val code: Int) : Fail()
            class IO(val error: IOException) : Fail()
        }
    }
}
