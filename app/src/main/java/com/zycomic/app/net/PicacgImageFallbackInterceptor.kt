package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * Tries every independently-measured Picacg mirror root in speed order for a single image.
 *
 * Cover thumbnails and reader pages are distinct services (covers on imgproxy `img.{root}`,
 * pages on the `storage-b.{root}` bucket); a mirror may be the fastest entry yet still fail to
 * fetch a specific tile (cold origin fetch, cross-bucket 502, a stalled edge). Pinning only one
 * root is what made a fixed subset of covers/pages never load. [PicacgRouteManager.candidateImageUrls]
 * returns the per-root candidates already ranked by the speed test, so this interceptor walks them
 * only after classified transport failures; a non-image response is returned without rotation.
 *
 * Non-Picacg or already-rewritten requests yield a single candidate and pass through unchanged.
 */
class PicacgImageFallbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val candidates = PicacgRouteManager.candidateImageUrls(original.url.toString())
        val candidateUrls = candidates.ifEmpty { listOf(original.url.toString()) }

        var lastError: IOException? = null
        for (candidate in candidateUrls) {
            if (chain.call().isCanceled()) throw IOException("Picacg image request was cancelled")
            val candidateResponse = try {
                chain.proceed(
                    original.newBuilder()
                        .url(candidate)
                        .header("User-Agent", UA)
                        .header("Referer", REFERER)
                        .build(),
                )
            } catch (e: IOException) {
                if (!PicacgImagePayloadPolicy.mayTryNextSource(e, chain.call().isCanceled())) throw e
                lastError = e
                continue
            }
            // Client responses are content/auth/rate policy, not evidence that another route is healthier.
            if (!candidateResponse.isSuccessful || candidateResponse.code in 400..499) return candidateResponse
            val usable = try {
                candidateResponse.isUsableImage()
            } catch (e: IOException) {
                candidateResponse.close()
                if (!PicacgImagePayloadPolicy.mayTryNextSource(e, chain.call().isCanceled())) throw e
                lastError = e
                continue
            }
            if (usable) return candidateResponse
            // A successful but empty, mislabeled, or non-image response is a payload failure, not a
            // transport failure; preserve it for the caller rather than rotating to another source.
            return candidateResponse
        }
        throw lastError ?: IOException("All Picacg image mirrors failed for ${original.url}")
    }

    private fun Response.isUsableImage(): Boolean {
        val type = header("Content-Type")
        if (!isSuccessful || !PicacgImagePayloadPolicy.isImageContentType(type)) return false
        val source = body?.source() ?: return false
        source.request(PicacgImagePayloadPolicy.MAX_HEAD_BYTES.toLong())
        val head = Buffer()
        source.buffer.copyTo(head)
        return PicacgImagePayloadPolicy.isUsableImage(code, type, head.readByteArray())
    }

    private companion object {
        const val UA = "okhttp/3.8.1"
        const val REFERER = "https://www.picacomic.com/"
    }
}
