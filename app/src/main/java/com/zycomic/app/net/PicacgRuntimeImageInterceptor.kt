package com.zycomic.app.net

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Picacg covers and reader pages feed independent runtime recovery tracks. */
internal class PicacgRuntimeImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val knownImage = PicacgRouteManager.imageHosts.any { it.equals(original.url.host, true) } ||
            PicacgBuiltinNetwork.isCoverHost(original.url.host)
        if (!knownImage) return chain.proceed(original)
        val readerRequest = original.tag(ReaderPage::class.java) != null
        val service = if (readerRequest || PicacgBuiltinNetwork.isContentPath(original.url.encodedPath)) {
            PicacgImageService.BODY
        } else {
            PicacgImageService.COVER
        }
        val content = service == PicacgImageService.BODY
        val source = PicacgRouteManager.imageSourceFor(content) ?: return chain.proceed(original)
        val configRevision = PicacgRouteManager.configRevision
        val networkRevision = RuntimeNetworkGate.revision
        fun mayReport() = PicacgRouteManager.configRevision == configRevision &&
            RuntimeNetworkGate.revision == networkRevision && PicacgRouteManager.imageSourceFor(content).equals(source, true)
        val targetUrl = if (PicacgBuiltinNetwork.isCoverHost(original.url.host)) {
            PicacgBuiltinNetwork.coverHostFor(source)?.let { original.url.newBuilder().host(it).build() }
                ?: original.url
        } else {
            original.url.newBuilder().host(source).build()
        }
        val request = original.newBuilder().url(targetUrl).build()
        RuntimeImageRateLimit.blockedResponse(RuntimeRecoveryPolicy.Platform.PICACG, request)?.let { return it }
        val attempt = original.tag(PageAttemptScope::class.java)
        if (attempt?.isCurrent == false) throw IOException("图片加载已取消")
        val registration = attempt?.registerCall(chain.call(), -1)
        val reported = AtomicBoolean()
        fun report(error: IOException) {
            if (!mayReport() || chain.call().isCanceled() || attempt?.isCurrent == false ||
                !RuntimeRecoveryPolicy.isTransportFailure(error) || !reported.compareAndSet(false, true)
            ) {
                return
            }
            when {
                service == PicacgImageService.COVER && attempt == null && !readerRequest ->
                    RuntimeImageRecovery.coverSample(
                        RuntimeRecoveryPolicy.Platform.PICACG,
                        source,
                        original.url.toString(),
                        failed = true,
                        service = service,
                    )
                service == PicacgImageService.BODY && attempt != null && attempt.page.readerVisible ->
                    RuntimeImageRecovery.visibleReaderTransportFailure(
                        RuntimeRecoveryPolicy.Platform.PICACG,
                        source,
                        original.url.toString(),
                        retryPage = {
                            RuntimeImageRecovery.retryVisiblePageAfterAttempt(
                                RuntimeRecoveryPolicy.Platform.PICACG,
                                source,
                                attempt,
                                service = service,
                            )
                        },
                        service = service,
                    )
            }
        }
        fun reportCoverSuccess(head: ByteArray, contentType: String?, actualHost: String) {
            if (service == PicacgImageService.COVER &&
                actualHost.equals(PicacgBuiltinNetwork.coverHostFor(source) ?: source, true) &&
                PicacgImagePayloadPolicy.isUsableImage(200, contentType, head) &&
                mayReport() && !chain.call().isCanceled() &&
                reported.compareAndSet(false, true)
            ) {
                RuntimeImageRecovery.coverSample(
                    RuntimeRecoveryPolicy.Platform.PICACG,
                    source,
                    original.url.toString(),
                    failed = false,
                    service = service,
                )
            }
        }
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            report(error)
            registration?.close()
            throw error
        }
        RuntimeImageRateLimit.record(RuntimeRecoveryPolicy.Platform.PICACG, response)
        // HTTP errors (including 429) and bad payload/content-type responses must never drive rotation.
        if (!response.isSuccessful || response.header("Content-Type")?.startsWith("image/", true) != true || response.body == null) {
            registration?.close()
            return response
        }
        val body = response.body!!
        val imageHead = Buffer()
        val observed = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = try {
                val offset = sink.size
                super.read(sink, byteCount).also { count ->
                    if (count > 0L && imageHead.size < PicacgImagePayloadPolicy.MAX_HEAD_BYTES) {
                        sink.copyTo(
                            imageHead,
                            offset = offset,
                            byteCount = minOf(count, PicacgImagePayloadPolicy.MAX_HEAD_BYTES - imageHead.size),
                        )
                    } else if (count == -1L) {
                        reportCoverSuccess(imageHead.readByteArray(), response.header("Content-Type"), response.request.url.host)
                    }
                }
            } catch (error: IOException) {
                report(error)
                throw error
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    registration?.close()
                }
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = observed
        }).build()
    }
}
