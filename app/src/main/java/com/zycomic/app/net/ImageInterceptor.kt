package com.zycomic.app.net

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
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.CipherInputStream

class ImageInterceptor : Interceptor {

    companion object {
        private const val HEADER_X_REQUESTED_WITH = "X-Requested-With"
        private const val VALUE_X_REQUESTED_WITH = "zycomic.com"
        private const val MAX_ENDPOINT_ATTEMPTS = 3
        private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
        private const val INVALID_IMAGE_STATUS = 502
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val originalUrl = original.url.toString()
        if (!isImageRequest(originalUrl)) return chain.proceed(original)

        val scope = original.tag(PageAttemptScope::class.java)
        val cacheReadStartedAt = System.nanoTime()
        val cachedImage = ImageCacheManager.get(originalUrl)
        scope?.addCacheTime(System.nanoTime() - cacheReadStartedAt)
        cachedImage?.let { cached ->
            if (cached.length() > MAX_IMAGE_BYTES) {
                ImageCacheManager.remove(originalUrl)
                return@let
            }
            val responseFile = ImageCacheManager.createResponseTemp()
            val decryptStartedAt = System.nanoTime()
            val mime = try {
                decodeToResponseFile(cached, responseFile)
            } catch (_: Exception) {
                responseFile.delete()
                null
            }
            scope?.addDecryptTime(System.nanoTime() - decryptStartedAt)
            val validationStartedAt = System.nanoTime()
            val valid = mime != null && validateDecodedImage(responseFile)
            scope?.addValidationTime(System.nanoTime() - validationStartedAt)
            if (valid && mime != null) return buildResponse(original, responseFile, mime)
            responseFile.delete()
            ImageCacheManager.remove(originalUrl)
        }

        val imgTarget = RouteManager.imgRewriteTarget
        val fetchUrl = if (!imgTarget.isNullOrBlank()) {
            original.url.newBuilder().host(imgTarget).build()
        } else {
            original.url
        }
        val imgReq = original.newBuilder()
            .url(fetchUrl)
            .header("User-Agent", ManwaInterceptor.UA)
            .header(HEADER_X_REQUESTED_WITH, VALUE_X_REQUESTED_WITH)
            .header("Referer", ManwaInterceptor.REFERER)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()

        var endpoints = resolveCandidates(imgReq.url.host, scope)
        if (endpoints.isEmpty() && scope?.page?.manualRetryCount?.get()?.let { it > 0 } == true) {
            val recovered = tryRecoveryProbe(imgReq, imgReq.url.host, scope)
            if (recovered != null) endpoints = resolveCandidates(imgReq.url.host, scope)
        }
        if (endpoints.isEmpty()) {
            throw IOException("图源临时不可用，正在重新测速：$originalUrl")
        }
        val outcomes = if (scope?.canRace == true && endpoints.size > 1) {
            stagedRaceCandidates(imgReq, originalUrl, endpoints, scope)
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

    private fun resolveCandidates(requestedDomain: String, scope: PageAttemptScope?): List<DomainIp> {
        val endpoints = mutableListOf<DomainIp>()
        if (scope != null && scope.page.endpointsAttemptedInRound.get() >= PageAttemptScope.MAX_ENDPOINTS_PER_ROUND) {
            scope.beginNextAutomaticRound()
        }
        val roundCapacity = if (scope == null) {
            MAX_ENDPOINT_ATTEMPTS
        } else {
            (PageAttemptScope.MAX_ENDPOINTS_PER_ROUND - scope.page.endpointsAttemptedInRound.get()).coerceAtLeast(0)
        }
        val limit = minOf(if (scope?.canRace == true) MAX_ENDPOINT_ATTEMPTS else 1, roundCapacity)
        if (limit == 0) return emptyList()
        val tried = scope?.page?.attemptedImageEndpoints?.mapTo(HashSet()) { it.normalizedForComparison() }.orEmpty()
        for (candidate in RouteManager.imageRetryEndpoints(requestedDomain, tried)) {
            val endpoint = candidate ?: NetworkModule.resolveProbeIps(requestedDomain)
                .firstOrNull()?.let { DomainIp(requestedDomain, it) }
            if (endpoint != null && endpoint.normalizedForComparison() !in tried &&
                endpoints.none { it.normalizedForComparison() == endpoint.normalizedForComparison() } &&
                RouteManager.isImgEndpointAvailable(endpoint)
            ) {
                endpoints += endpoint
                if (endpoints.size == limit) break
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

    /** Visible pages keep the primary alive and add up to two candidates after 1.5 seconds without new bytes. */
    private fun stagedRaceCandidates(
        request: Request,
        originalUrl: String,
        endpoints: List<DomainIp>,
        scope: PageAttemptScope,
    ): List<Outcome> {
        val outcomes = mutableListOf<Outcome>()
        val completed = LinkedBlockingQueue<Outcome>()
        val activeRaces = AtomicInteger(0)
        val raceStarted = AtomicBoolean(false)
        val primaryFinished = AtomicBoolean(false)
        val submittedIds = ConcurrentHashMap.newKeySet<Int>()
        if (!acquirePermit(scope)) return listOf(Outcome.Fail.Cancelled)

        fun launchOtherCandidates() {
            if (primaryFinished.get() || !scope.isCurrent || !scope.canRace ||
                !raceStarted.compareAndSet(false, true)
            ) return

            var openedRace = false
            for (index in 1 until minOf(endpoints.size, MAX_ENDPOINT_ATTEMPTS)) {
                if (!scope.coordinator.tryAcquireRace()) continue
                var raceCounted = false
                var submitted = false
                try {
                    activeRaces.incrementAndGet()
                    raceCounted = true
                    if (!openedRace) {
                        scope.beginRace()
                        openedRace = true
                    }
                    submitted = scope.coordinator.submitRace {
                        var outcome: Outcome = Outcome.Fail.Invalid
                        try {
                            outcome = executeSafely(request, originalUrl, endpoints[index], scope, index, endpoints.size)
                        } finally {
                            completed.offer(outcome)
                            activeRaces.decrementAndGet()
                            scope.coordinator.releaseRaceAttempt()
                        }
                    }
                    if (submitted) submittedIds += index
                } finally {
                    if (!submitted) {
                        if (raceCounted) activeRaces.decrementAndGet()
                        scope.coordinator.releaseRaceAttempt()
                    }
                }
            }
            if (activeRaces.get() == 0) scope.endRace()
        }

        val hedgeWatch = scope.coordinator.scheduleWatchdog {
            val lastProgress = scope.lastProgressAtNanos.get()
            if (!primaryFinished.get() && scope.isCurrent && scope.canRace &&
                scope.hasActiveEndpointRequest && lastProgress > 0L &&
                System.nanoTime() - lastProgress >= TimeUnit.MILLISECONDS.toNanos(1_500L)
            ) {
                launchOtherCandidates()
            }
        }

        try {
            val primary = try {
                executeSafely(request, originalUrl, endpoints[0], scope, 0, endpoints.size)
            } finally {
                scope.coordinator.releasePrimaryAttempt()
                primaryFinished.set(true)
            }
            outcomes += primary
            if (primary is Outcome.Success) return outcomes
            if (!scope.isCurrent) return outcomes
            if (primary is Outcome.Fail.Cancelled && !raceStarted.get()) return outcomes

            if (!raceStarted.get()) {
                // A clear primary failure beats the hedge timer: immediately try candidates serially.
                for (index in 1 until endpoints.size) {
                    if (!scope.isCurrent || !acquirePermit(scope)) return outcomes
                    val result = try {
                        executeSafely(request, originalUrl, endpoints[index], scope, index, endpoints.size)
                    } finally {
                        scope.coordinator.releasePrimaryAttempt()
                    }
                    outcomes += result
                    if (result is Outcome.Success || result is Outcome.Fail.Cancelled || !scope.isCurrent) {
                        return outcomes
                    }
                }
                return outcomes
            }

            while (scope.isCurrent && (activeRaces.get() > 0 || completed.isNotEmpty())) {
                val result = completed.poll(200, TimeUnit.MILLISECONDS) ?: continue
                outcomes += result
                if (result is Outcome.Success) return outcomes
            }
            scope.endRace()

            // If a race slot was unavailable, try that candidate serially on this existing worker.
            endpoints.indices.filter { it > 0 && it !in submittedIds }.forEach { index ->
                if (!scope.isCurrent || !acquirePermit(scope)) return outcomes
                val result = try {
                    executeSafely(request, originalUrl, endpoints[index], scope, index, endpoints.size)
                } finally {
                    scope.coordinator.releasePrimaryAttempt()
                }
                outcomes += result
                if (result is Outcome.Success || result is Outcome.Fail.Cancelled || !scope.isCurrent) return outcomes
            }
            return outcomes
        } finally {
            hedgeWatch?.cancel(false)
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
        if (scope?.isCurrent == false) {
            Outcome.Fail.Cancelled
        } else {
            Outcome.Fail.Io(e as? IOException ?: IOException("图片请求准备失败", e))
        }
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
        var networkStartedAt = 0L
        var firstByteAtNanos = 0L
        var bytesRead = 0L
        var downloadCompleted = false
        val pageLabel = scope?.page?.index?.plus(1)?.toString() ?: "unknown"
        val retryRound = scope?.page?.automaticRetryRound?.get() ?: 1
        try {
            if (scope != null && !scope.recordEndpointAttempt(endpoint)) return Outcome.Fail.Cancelled
            scope?.endpointRequestStarted(attemptId, totalAttempts)
            endpointRequestStarted = scope != null
            onRequestStarted()
            networkStartedAt = System.nanoTime()
            logcat { "Image request started: page=$pageLabel attempt=${attemptId + 1} round=$retryRound" }
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    scope?.addNetworkTime(System.nanoTime() - networkStartedAt)
                    networkStartedAt = 0L
                    return Outcome.Fail.Http(response.code)
                }
                val body = response.body ?: return Outcome.Fail.Invalid
                val contentLength = body.contentLength()
                if (contentLength > MAX_IMAGE_BYTES) return Outcome.Fail.Oversize
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
                            if (read > 0 && firstByteAtNanos == 0L) {
                                firstByteAtNanos = System.nanoTime()
                                logcat {
                                    "Image first byte: page=$pageLabel attempt=${attemptId + 1} " +
                                        "elapsed=${TimeUnit.NANOSECONDS.toMillis(firstByteAtNanos - networkStartedAt)}ms"
                                }
                            }
                            bytesRead += read
                            if (bytesRead > MAX_IMAGE_BYTES) return Outcome.Fail.Oversize
                            output.write(buffer, 0, read)
                            progress(bytesRead, contentLength)
                        }
                        output.flush()
                    }
                }
                downloadCompleted = true
                logcat {
                    "Image download completed: page=$pageLabel attempt=${attemptId + 1} " +
                        "elapsed=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - networkStartedAt)}ms bytes=$bytesRead"
                }
                scope?.addNetworkTime(System.nanoTime() - networkStartedAt)
                networkStartedAt = 0L
                if (bytesRead == 0L) return Outcome.Fail.Invalid
                val decryptStartedAt = System.nanoTime()
                val mimeType = try {
                    decodeToResponseFile(rawTemp, responseTemp)
                } catch (_: Exception) {
                    scope?.addDecryptTime(System.nanoTime() - decryptStartedAt)
                    return Outcome.Fail.Decrypt
                }
                scope?.addDecryptTime(System.nanoTime() - decryptStartedAt)
                if (mimeType == null) return Outcome.Fail.Invalid

                val validationStartedAt = System.nanoTime()
                val isValidImage = validateDecodedImage(responseTemp)
                scope?.addValidationTime(System.nanoTime() - validationStartedAt)
                if (!isValidImage) return Outcome.Fail.Invalid

                if (scope != null && !scope.claimWinner()) {
                    responseTemp.delete()
                    return Outcome.Fail.Cancelled
                }
                val cacheStartedAt = System.nanoTime()
                val committed = ImageCacheManager.commitAttempt(
                    url = originalUrl,
                    temporary = rawTemp,
                    stillOwner = { scope?.isCurrent != false },
                    ownerLock = scope?.page,
                )
                scope?.addCacheTime(System.nanoTime() - cacheStartedAt)
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
            if (networkStartedAt != 0L) {
                scope?.addNetworkTime(System.nanoTime() - networkStartedAt)
                networkStartedAt = 0L
            }
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
            if (networkStartedAt != 0L) {
                scope?.addNetworkTime(System.nanoTime() - networkStartedAt)
                networkStartedAt = 0L
            }
            responseTemp.delete()
            ImageCacheManager.abortAttempt(rawTemp)
            if (scope?.isCurrent == false) return Outcome.Fail.Cancelled
            return Outcome.Fail.Invalid
        } finally {
            val cancellationReason = scope?.reasonFor(call)
            if (call.isCanceled() || cancellationReason != null) {
                logcat {
                    "Image request cancelled: page=$pageLabel attempt=${attemptId + 1} " +
                        "reason=${cancellationReason ?: "unknown"} bytes=$bytesRead downloadCompleted=$downloadCompleted"
                }
            }
            if (networkStartedAt != 0L) scope?.addNetworkTime(System.nanoTime() - networkStartedAt)
            if (endpointRequestStarted) scope?.endpointRequestFinished()
            if (!responseTransferred) responseTemp.delete()
            registration?.close()
            runCatching { client.connectionPool.evictAll() }
            // A successful raw file was renamed on commit; failures must not leave attempt files behind.
            if (rawTemp.exists() && rawTemp.name.contains(".tmp-")) ImageCacheManager.abortAttempt(rawTemp)
        }
    }

    /** Decode a bounded preview so corrupt pixels cannot win against another candidate. */
    private fun validateDecodedImage(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return try {
            file.inputStream().use { input ->
                val decoder = ImageDecoder.newInstance(input, false, null) ?: return false
                try {
                    val width = decoder.width
                    val height = decoder.height
                    if (width <= 0 || height <= 0) return false
                    var sampleSize = 1
                    val maxDimension = maxOf(width, height)
                    while (maxDimension / sampleSize > 512) sampleSize *= 2
                    val bitmap = decoder.decode(sampleSize = sampleSize) ?: return false
                    bitmap.recycle()
                    true
                } finally {
                    decoder.recycle()
                }
            }
        } catch (_: Exception) {
            false
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
        ) {
            return "image/webp".toMediaTypeOrNull()
        }
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
