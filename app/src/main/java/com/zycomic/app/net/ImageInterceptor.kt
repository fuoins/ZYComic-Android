package com.zycomic.app.net

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.source
import javax.crypto.CipherInputStream

class ImageInterceptor : Interceptor {

    companion object {
        private const val HEADER_X_REQUESTED_WITH = "X-Requested-With"
        private const val VALUE_X_REQUESTED_WITH = "zycomic.com"
        private const val MAX_ENDPOINT_ATTEMPTS = 3
        private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
        private const val SLOW_AFTER_MS = 1_500L
        private const val INVALID_IMAGE_STATUS = 502
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val originalUrl = original.url.toString()
        if (!isImageRequest(originalUrl)) return chain.proceed(original)

        val scope = original.tag(PageAttemptScope::class.java)
        ImageCacheManager.get(originalUrl)?.let { cached ->
            if (cached.length() > MAX_IMAGE_BYTES) {
                ImageCacheManager.remove(originalUrl)
                return@let
            }
            val responseFile = ImageCacheManager.createResponseTemp()
            val mime = try {
                decodeToResponseFile(cached, responseFile)
            } catch (_: Exception) {
                responseFile.delete()
                null
            }
            if (mime != null) return buildResponse(original, responseFile, mime)
            ImageCacheManager.remove(originalUrl)
        }

        val imgTarget = RouteManager.imgRewriteTarget
        val fetchUrl = if (!imgTarget.isNullOrBlank()) {
            original.url.newBuilder().host(imgTarget).build()
        } else original.url
        val imgReq = original.newBuilder()
            .url(fetchUrl)
            .header("User-Agent", ManwaInterceptor.UA)
            .header(HEADER_X_REQUESTED_WITH, VALUE_X_REQUESTED_WITH)
            .header("Referer", ManwaInterceptor.REFERER)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()

        var endpoints = resolveCandidates(imgReq.url.host)
        if (endpoints.isEmpty() && scope?.page?.manualRetryCount?.get()?.let { it > 0 } == true) {
            val recovered = tryRecoveryProbe(imgReq, imgReq.url.host, scope)
            if (recovered != null) endpoints = resolveCandidates(imgReq.url.host)
        }
        if (endpoints.isEmpty()) {
            throw IOException("图源临时不可用，正在重新测速：$originalUrl")
        }
        val outcomes = if (scope != null && endpoints.size > 1) {
            raceCandidates(imgReq, originalUrl, endpoints, scope)
        } else {
            sequentialCandidates(imgReq, originalUrl, endpoints, scope)
        }
        val success = outcomes.filterIsInstance<Outcome.Success>().firstOrNull()
        if (success != null) return buildResponse(original, success.responseFile, success.mimeType)

        val failure = outcomes.lastOrNull()
        return when (failure) {
            is Outcome.Fail.Http -> errorResponse(original, failure.code)
            is Outcome.Fail.Io -> throw failure.error
            is Outcome.Fail.Decrypt -> throw ImageDecryptFailedException()
            is Outcome.Fail.Cancelled -> {
                if (scope != null && scope.isCurrent && scope.isSecondaryVisible) throw ImageAttemptDeferredException()
                throw IOException("图片加载已取消")
            }
            is Outcome.Fail.Oversize -> throw IOException("图片超过 25MB 上限，已放弃该候选")
            is Outcome.Fail.Invalid, null -> errorResponse(original, INVALID_IMAGE_STATUS)
            is Outcome.Success -> buildResponse(original, failure.responseFile, failure.mimeType)
        }
    }

    private fun resolveCandidates(requestedDomain: String): List<DomainIp> {
        val endpoints = mutableListOf<DomainIp>()
        for (candidate in RouteManager.imageRetryEndpoints(requestedDomain)) {
            val endpoint = candidate ?: NetworkModule.resolveProbeIps(requestedDomain)
                .firstOrNull()?.let { DomainIp(requestedDomain, it) }
            if (endpoint != null && endpoint !in endpoints && RouteManager.isImgEndpointAvailable(endpoint)) {
                endpoints += endpoint
                if (endpoints.size == MAX_ENDPOINT_ATTEMPTS) break
            }
        }
        return endpoints
    }

    /** One bounded 2KB probe, reserved at most once per domain/minute and only after manual retry. */
    private fun tryRecoveryProbe(request: Request, domain: String, scope: PageAttemptScope): DomainIp? {
        if (!scope.coordinator.tryAcquirePrimary()) return null
        var client: okhttp3.OkHttpClient? = null
        var registration: Closeable? = null
        var rawProbe: File? = null
        var decodedProbe: File? = null
        return try {
            val endpoint = RouteManager.reserveImgRecoveryProbe(domain) ?: return null
            val samplePath = RouteManager.loadProbeImgPath() ?: request.url.encodedPath
            val path = samplePath.substringBefore('?').takeIf { it.startsWith("/") } ?: request.url.encodedPath
            val query = samplePath.substringAfter('?', "").takeIf { it.isNotBlank() }
            val probeUrl = request.url.newBuilder()
                .host(endpoint.domain)
                .encodedPath(path)
                .encodedQuery(query)
                .build()
            val probeRequest = request.newBuilder()
                .url(probeUrl)
                .header("Range", "bytes=0-2047")
                .build()
            val imageClient = NetworkModule.newImageClientForIp(endpoint.domain, endpoint.ip)
            client = imageClient
            val call = imageClient.newCall(probeRequest)
            registration = scope.registerCall(call, -2)
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                val input = response.body?.byteStream() ?: return null
                val rawFile = ImageCacheManager.createResponseTemp()
                rawProbe = rawFile
                val decodedFile = ImageCacheManager.createResponseTemp()
                decodedProbe = decodedFile
                input.use { source ->
                    rawFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(512)
                        var total = 0
                        while (total < 2 * 1024) {
                            val read = source.read(buffer, 0, minOf(buffer.size, 2 * 1024 - total))
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            total += read
                        }
                    }
                }
                if (decodeToResponseFile(rawFile, decodedFile) == null) return null
                RouteManager.markImgEndpointHealthy(endpoint)
                endpoint
            }
        } catch (_: Exception) {
            null
        } finally {
            rawProbe?.delete()
            decodedProbe?.delete()
            registration?.close()
            scope.coordinator.releasePrimaryAttempt()
            client?.let { runCatching { it.connectionPool.evictAll() } }
        }
    }

    private fun sequentialCandidates(
        request: Request,
        originalUrl: String,
        endpoints: List<DomainIp>,
        scope: PageAttemptScope?,
    ): List<Outcome> {
        val outcomes = mutableListOf<Outcome>()
        endpoints.forEachIndexed { index, endpoint ->
            val acquired = scope?.let { acquirePermit(it) } ?: true
            if (!acquired) return outcomes + Outcome.Fail.Cancelled
            val result = try {
                executeSafely(request, originalUrl, endpoint, scope, index, endpoints.size)
            } finally {
                if (scope != null) scope.coordinator.releasePrimaryAttempt()
            }
            outcomes += result
            if (result is Outcome.Success) return outcomes
            if (scope?.isCurrent == false) return outcomes
        }
        return outcomes
    }

    /** First endpoint stays on the calling OkHttp worker; at most two extra attempts use the bounded race pool. */
    private fun raceCandidates(
        request: Request,
        originalUrl: String,
        endpoints: List<DomainIp>,
        scope: PageAttemptScope,
    ): List<Outcome> {
        val outcomes = mutableListOf<Outcome>()
        val completed = LinkedBlockingQueue<Outcome>()
        val active = AtomicInteger(1)
        val nextIndex = AtomicInteger(1)
        val lastProgressAt = AtomicLong(0L)
        val primaryStartedAt = AtomicLong(0L)
        val primaryAcquired = acquirePermit(scope)
        if (!primaryAcquired) return listOf(Outcome.Fail.Cancelled)
        active.set(1)
        var primaryPermitHeld = true
        val raceStateLock = Any()
        var raceWindowOpen = true
        val watch = runCatching {
            scope.coordinator.scheduleWatchdog {
                var index = -1
                var permitAcquired = false
                var activeReserved = false
                var submitted = false
                try {
                    synchronized(raceStateLock) {
                        if (!raceWindowOpen || !scope.isCurrent || !scope.canRace || nextIndex.get() >= endpoints.size || active.get() >= 3) return@scheduleWatchdog
                        val requestStartedAt = primaryStartedAt.get()
                        if (requestStartedAt == 0L) return@scheduleWatchdog
                        val now = System.nanoTime()
                        val noProgressFor = TimeUnit.NANOSECONDS.toMillis(now - lastProgressAt.get())
                        val elapsed = TimeUnit.NANOSECONDS.toMillis(now - requestStartedAt)
                        if (elapsed < SLOW_AFTER_MS || noProgressFor < SLOW_AFTER_MS) return@scheduleWatchdog
                        val candidateIndex = nextIndex.getAndIncrement()
                        if (candidateIndex >= endpoints.size) return@scheduleWatchdog
                        index = candidateIndex
                        if (!scope.coordinator.tryAcquireRace()) return@scheduleWatchdog
                        permitAcquired = true
                        active.incrementAndGet()
                        activeReserved = true
                        scope.beginRace()
                        lastProgressAt.set(System.nanoTime())
                        submitted = scope.coordinator.submitRace {
                            var result: Outcome = Outcome.Fail.Invalid
                            try {
                                result = executeSafely(request, originalUrl, endpoints[candidateIndex], scope, candidateIndex, endpoints.size)
                            } finally {
                                try {
                                    completed.offer(result)
                                } finally {
                                    active.decrementAndGet()
                                    scope.coordinator.releaseRaceAttempt()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Keep the periodic watcher alive; the primary attempt and remaining serial fallbacks still proceed.
                } finally {
                    if (!submitted) {
                        if (activeReserved) active.decrementAndGet()
                        if (permitAcquired) scope.coordinator.releaseRaceAttempt()
                        if (index >= 0) nextIndex.compareAndSet(index + 1, index)
                    }
                }
            }
        }.getOrNull()

        try {
            val primary = try {
                executeSafely(
                    request,
                    originalUrl,
                    endpoints[0],
                    scope,
                    0,
                    endpoints.size,
                    progress = { bytes, length ->
                        scope.reportProgress(bytes, length)
                        lastProgressAt.set(System.nanoTime())
                    },
                    onRequestStarted = {
                        val now = System.nanoTime()
                        primaryStartedAt.compareAndSet(0L, now)
                        lastProgressAt.compareAndSet(0L, now)
                    },
                )
            } finally {
                if (primaryPermitHeld) {
                    primaryPermitHeld = false
                    scope.coordinator.releasePrimaryAttempt()
                    active.decrementAndGet()
                }
            }
            outcomes += primary
            if (primary is Outcome.Success) {
                scope.cancelLosers(0)
                return outcomes
            }

            while (scope.isCurrent && (active.get() > 0 || completed.isNotEmpty())) {
                val result = completed.poll(200, TimeUnit.MILLISECONDS) ?: continue
                outcomes += result
                if (result is Outcome.Success) {
                    scope.cancelLosers(result.attemptId)
                    return outcomes
                }
            }

            // If no stalled attempt launched a race (or all racing candidates failed quickly), continue the remaining endpoints serially.
            synchronized(raceStateLock) { raceWindowOpen = false }
            watch?.cancel(false)
            while (scope.isCurrent && (active.get() > 0 || completed.isNotEmpty())) {
                val result = completed.poll(200, TimeUnit.MILLISECONDS) ?: continue
                outcomes += result
                if (result is Outcome.Success) {
                    scope.cancelLosers(result.attemptId)
                    return outcomes
                }
            }
            scope.endRace()
            var index = nextIndex.get()
            while (scope.isCurrent && index < endpoints.size) {
                val acquired = acquirePermit(scope)
                if (!acquired) break
                val result = try {
                    executeSafely(request, originalUrl, endpoints[index], scope, index, endpoints.size)
                } finally {
                    scope.coordinator.releasePrimaryAttempt()
                }
                outcomes += result
                if (result is Outcome.Success) return outcomes
                index++
                nextIndex.set(index)
            }
            return outcomes
        } finally {
            synchronized(raceStateLock) { raceWindowOpen = false }
            watch?.cancel(false)
            if (primaryPermitHeld) {
                scope.coordinator.releasePrimaryAttempt()
                active.decrementAndGet()
            }
            scope.cancelLosers(Int.MIN_VALUE)
            scope.endRace()
        }
    }

    private fun acquirePermit(scope: PageAttemptScope): Boolean {
        if (scope.isSecondaryVisible) {
            val acquired = scope.coordinator.tryAcquirePrimary()
            if (!acquired) scope.waitingForRequestPermit()
            return acquired
        }
        while (scope.isCurrent) {
            if (scope.coordinator.tryAcquirePrimary()) return true
            scope.waitingForRequestPermit()
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun executeSafely(
        request: Request,
        originalUrl: String,
        endpoint: DomainIp,
        scope: PageAttemptScope?,
        attemptId: Int,
        totalAttempts: Int,
        progress: (Long, Long) -> Unit = { bytes, length -> scope?.reportProgress(bytes, length) },
        onRequestStarted: () -> Unit = {},
    ): Outcome = try {
        executeOnce(request, originalUrl, endpoint, scope, attemptId, totalAttempts, progress, onRequestStarted)
    } catch (e: Exception) {
        if (scope?.isCurrent == false) Outcome.Fail.Cancelled
        else Outcome.Fail.Io(e as? IOException ?: IOException("图片请求准备失败", e))
    }

    private fun executeOnce(
        baseRequest: Request,
        originalUrl: String,
        endpoint: DomainIp,
        scope: PageAttemptScope?,
        attemptId: Int,
        totalAttempts: Int,
        progress: (Long, Long) -> Unit = { bytes, length -> scope?.reportProgress(bytes, length) },
        onRequestStarted: () -> Unit = {},
    ): Outcome {
        val rawTemp = ImageCacheManager.beginAttempt(originalUrl, scope?.generation ?: 0, attemptId, endpoint.ip)
        val responseTemp = try {
            ImageCacheManager.createResponseTemp()
        } catch (e: Exception) {
            ImageCacheManager.abortAttempt(rawTemp)
            throw e
        }
        val client = try {
            NetworkModule.newImageClientForIp(endpoint.domain, endpoint.ip)
        } catch (e: Exception) {
            responseTemp.delete()
            ImageCacheManager.abortAttempt(rawTemp)
            throw e
        }
        val call = try {
            client.newCall(replaceHost(baseRequest, endpoint.domain))
        } catch (e: Exception) {
            responseTemp.delete()
            ImageCacheManager.abortAttempt(rawTemp)
            runCatching { client.connectionPool.evictAll() }
            throw e
        }
        val registration: Closeable? = scope?.registerCall(call, attemptId)
        var responseTransferred = false
        var endpointRequestStarted = false
        try {
            scope?.endpointRequestStarted(attemptId, totalAttempts)
            endpointRequestStarted = scope != null
            onRequestStarted()
            call.execute().use { response ->
                if (!response.isSuccessful) return Outcome.Fail.Http(response.code)
                val body = response.body ?: return Outcome.Fail.Invalid
                val contentLength = body.contentLength()
                if (contentLength > MAX_IMAGE_BYTES) return Outcome.Fail.Oversize
                var bytesRead = 0L
                rawTemp.outputStream().buffered().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            if (scope?.isCurrent == false) return Outcome.Fail.Cancelled
                            if (call.isCanceled()) {
                                if (scope?.reasonFor(call) == null) throw SocketTimeoutException("Image call ended without an owner cancellation")
                                return Outcome.Fail.Cancelled
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            bytesRead += read
                            if (bytesRead > MAX_IMAGE_BYTES) return Outcome.Fail.Oversize
                            output.write(buffer, 0, read)
                            progress(bytesRead, contentLength)
                        }
                        output.flush()
                    }
                }
                if (bytesRead == 0L) return Outcome.Fail.Invalid
                val mimeType = try {
                    decodeToResponseFile(rawTemp, responseTemp)
                } catch (_: Exception) {
                    return Outcome.Fail.Decrypt
                } ?: return Outcome.Fail.Invalid

                if (scope != null && !scope.claimWinner()) {
                    responseTemp.delete()
                    return Outcome.Fail.Cancelled
                }
                val committed = ImageCacheManager.commitAttempt(
                    url = originalUrl,
                    temporary = rawTemp,
                    stillOwner = { scope?.isCurrent != false },
                    ownerLock = scope?.page,
                )
                if (!committed) {
                    responseTemp.delete()
                    scope?.releaseWinnerClaim()
                    return Outcome.Fail.Cancelled
                }
                RouteManager.markImgEndpointHealthy(endpoint)
                scope?.cancelLosers(attemptId)
                responseTransferred = true
                return Outcome.Success(responseTemp, mimeType, endpoint, attemptId)
            }
        } catch (io: IOException) {
            responseTemp.delete()
            ImageCacheManager.abortAttempt(rawTemp)
            val cancellation = scope?.reasonFor(call)
            if (cancellation != null || scope?.isCurrent == false) return Outcome.Fail.Cancelled
            val kind = when (io) {
                is SocketTimeoutException -> ImgEndpointFailure.SLOW
                is ConnectException, is java.net.UnknownHostException -> ImgEndpointFailure.DEAD
                else -> ImgEndpointFailure.TRANSIENT
            }
            RouteManager.markImgEndpointFailed(endpoint, kind)
            return Outcome.Fail.Io(io)
        } catch (e: Exception) {
            responseTemp.delete()
            ImageCacheManager.abortAttempt(rawTemp)
            if (scope?.isCurrent == false) return Outcome.Fail.Cancelled
            return Outcome.Fail.Invalid
        } finally {
            if (endpointRequestStarted) scope?.endpointRequestFinished()
            if (!responseTransferred) responseTemp.delete()
            registration?.close()
            runCatching { client.connectionPool.evictAll() }
            // A successful raw file was renamed on commit; failures must not leave attempt files behind.
            if (rawTemp.exists() && rawTemp.name.contains(".tmp-")) ImageCacheManager.abortAttempt(rawTemp)
        }
    }

    private fun replaceHost(request: Request, host: String): Request =
        request.newBuilder().url(request.url.newBuilder().host(host).build()).build()

    /** Materialize raw or CBC-encrypted bytes, requiring a complete body and a recognized image header. */
    private fun decodeToResponseFile(raw: File, output: File): MediaType? {
        val rawHeader = readHeader(raw)
        val rawMime = imageMime(rawHeader)
        if (rawMime != null) {
            raw.inputStream().buffered().use { input ->
                output.outputStream().buffered().use { target -> input.copyTo(target) }
            }
            return rawMime
        }
        val header = ByteArray(16)
        var headerSize = 0
        try {
            CipherInputStream(raw.inputStream().buffered(), Crypto.createImageCipher()).use { input ->
                output.outputStream().buffered().use { target ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        val copy = minOf(read, header.size - headerSize)
                        if (copy > 0) {
                            buffer.copyInto(header, headerSize, 0, copy)
                            headerSize += copy
                        }
                        target.write(buffer, 0, read)
                    }
                    target.flush()
                }
            }
        } catch (e: Exception) {
            output.delete()
            throw ImageDecryptFailedException(e)
        }
        val mime = imageMime(header.copyOf(headerSize))
        if (mime == null || output.length() == 0L) {
            output.delete()
            throw ImageDecryptFailedException()
        }
        return mime
    }

    private fun readHeader(file: File): ByteArray {
        val header = ByteArray(16)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            return header.copyOf(offset)
        }
    }

    private fun imageMime(bytes: ByteArray): MediaType? {
        if (bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)) &&
            bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50))
        ) return "image/webp".toMediaTypeOrNull()
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) {
            return "image/png".toMediaTypeOrNull()
        }
        if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) {
            return "image/jpeg".toMediaTypeOrNull()
        }
        if (bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()) {
            return "image/gif".toMediaTypeOrNull()
        }
        return null
    }

    private fun buildResponse(request: Request, responseFile: File, mimeType: MediaType): Response {
        val responseBody = object : ResponseBody() {
            private val buffered = object : ForwardingSource(responseFile.source()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        responseFile.delete()
                    }
                }
            }.buffer()

            override fun contentType(): MediaType = mimeType
            override fun contentLength(): Long = responseFile.length()
            override fun source(): BufferedSource = buffered
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody)
            .build()
    }

    private fun errorResponse(request: Request, code: Int): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("img fail")
            .body(ByteArray(0).toResponseBody(null))
            .build()

    private fun isImageRequest(url: String): Boolean =
        url.contains("/static/upload") || url.contains(".webp") || url.contains(".jpg") ||
            url.contains(".jpeg") || url.contains(".png") || url.contains(".gif")

    private sealed interface Outcome {
        data class Success(val responseFile: File, val mimeType: MediaType, val endpoint: DomainIp, val attemptId: Int) : Outcome
        sealed interface Fail : Outcome {
            data class Http(val code: Int) : Fail
            data class Io(val error: IOException) : Fail
            data object Decrypt : Fail
            data object Invalid : Fail
            data object Oversize : Fail
            data object Cancelled : Fail
        }
    }
}

class ImageDecryptFailedException(cause: Throwable? = null) :
    IOException("图片解密/签名链路失败，请检查图源线路或重新测速", cause)

class ImageAttemptDeferredException : IOException("双页副页等待主页释放图片加载额度")
