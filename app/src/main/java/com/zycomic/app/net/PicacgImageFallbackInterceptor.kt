package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Tries every independently-measured Picacg mirror root in speed order for a single image.
 *
 * Cover thumbnails and reader pages are distinct services (covers on imgproxy `img.{root}`,
 * pages on the `storage-b.{root}` bucket); a mirror may be the fastest entry yet still fail to
 * fetch a specific tile (cold origin fetch, cross-bucket 502, a stalled edge). Pinning only one
 * root is what made a fixed subset of covers/pages never load. [PicacgRouteManager.candidateImageUrls]
 * returns the per-root candidates already ranked by the speed test, so this interceptor walks them
 * on a non-image / error response and returns the first that actually serves an image.
 *
 * Non-Picacg or already-rewritten requests yield a single candidate and pass through unchanged.
 */
class PicacgImageFallbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val candidates = PicacgRouteManager.candidateImageUrls(original.url.toString())
        val target = candidates.firstOrNull() ?: original.url.toString()

        if (candidates.size <= 1) {
            return chain.proceed(
                original.newBuilder()
                    .url(target)
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .build(),
            )
        }

        var lastError: IOException? = null
        for (candidate in candidates) {
            val response = try {
                chain.proceed(
                    original.newBuilder()
                        .url(candidate)
                        .header("User-Agent", UA)
                        .header("Referer", REFERER)
                        .build(),
                )
            } catch (e: IOException) {
                lastError = e
                continue
            }
            if (response.isUsableImage()) {
                return response
            }
            response.close()
        }
        throw lastError ?: IOException("All Picacg image mirrors failed for ${original.url}")
    }

    private fun Response.isUsableImage(): Boolean {
        if (!isSuccessful) return false
        val type = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        // Storage buckets and imgproxy always answer image/* on success; a 200 HTML/text body is an error page.
        return type == null || type.startsWith("image/")
    }

    private companion object {
        const val UA = "okhttp/3.8.1"
        const val REFERER = "https://www.picacomic.com/"
    }
}
