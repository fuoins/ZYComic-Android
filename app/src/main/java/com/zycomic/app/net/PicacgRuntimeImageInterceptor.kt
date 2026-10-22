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

/** Picacg's covers and reader pages feed only Picacg's recovery window. */
internal class PicacgRuntimeImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val knownImage = PicacgRouteManager.imageHosts.any { it.equals(original.url.host, true) } ||
            PicacgBuiltinNetwork.isCoverHost(original.url.host)
        if (!knownImage) return chain.proceed(original)
        val source = PicacgRouteManager.currentImageHost ?: return chain.proceed(original)
        val configRevision = PicacgRouteManager.configRevision
        val networkRevision = RuntimeNetworkGate.revision
        fun mayReport() = PicacgRouteManager.configRevision == configRevision &&
            RuntimeNetworkGate.revision == networkRevision && PicacgRouteManager.currentImageHost.equals(source, true)
        val targetUrl = if (PicacgBuiltinNetwork.isCoverHost(original.url.host)) {
            PicacgBuiltinNetwork.coverHostFor(source)?.let { original.url.newBuilder().host(it).build() }
                ?: original.url
        } else {
            original.url.newBuilder().host(source).build()
        }
        val request = original.newBuilder().url(targetUrl).build()
        RuntimeImageRateLimit.blockedResponse(RuntimeRecoveryPolicy.Platform.PICACG, request)?.let { return it }
        val attempt = original.tag(PageAttemptScope::class.java)
        val readerRequest = original.tag(ReaderPage::class.java) != null
        if (attempt?.isCurrent == false) throw IOException("图片加载已取消")
        val registration = attempt?.registerCall(chain.call(), -1)
        val reported = AtomicBoolean()
        fun report(error: IOException) {
            if (!mayReport() || chain.call().isCanceled() || attempt?.isCurrent == false ||
                !RuntimeRecoveryPolicy.isTransportFailure(error) || !reported.compareAndSet(false, true)
            ) {
                return
            }
            if (attempt == null && !readerRequest) {
                RuntimeImageRecovery.coverSample(RuntimeRecoveryPolicy.Platform.PICACG, source, original.url.toString(), true)
            } else if (attempt != null && attempt.page.readerVisible) {
                RuntimeImageRecovery.visibleReaderTransportFailure(RuntimeRecoveryPolicy.Platform.PICACG, source, original.url.toString()) {
                    RuntimeImageRecovery.retryVisiblePageAfterAttempt(RuntimeRecoveryPolicy.Platform.PICACG, source, attempt)
                }
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
        if (!response.isSuccessful || response.header("Content-Type")?.startsWith("image/", true) != true || response.body == null) {
            if (mayReport() && attempt == null && !readerRequest) {
                RuntimeImageRecovery.coverSample(RuntimeRecoveryPolicy.Platform.PICACG, source, original.url.toString(), false)
            }
            registration?.close()
            return response
        }
        val body = response.body!!
        val observed = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = try {
                super.read(sink, byteCount).also { count ->
                    if (mayReport() && count == -1L && attempt == null && !readerRequest && !chain.call().isCanceled() && reported.compareAndSet(false, true)) {
                        RuntimeImageRecovery.coverSample(RuntimeRecoveryPolicy.Platform.PICACG, source, original.url.toString(), false)
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
