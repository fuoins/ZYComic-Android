package com.zycomic.app.reader

import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.ProgressResponseBody
import okhttp3.Response

/** Observes only the response consumed by this request; it never starts or retries a call. */
internal data class PicacgImageProgress(val bytesRead: Long, val contentLength: Long) {
    val percentage: Int?
        get() = contentLength.takeIf { it > 0L }?.let {
            (bytesRead.coerceAtLeast(0L).toDouble() / it * 100).toInt().coerceIn(0, 100)
        }
}

internal fun Response.withPicacgImageProgress(
    isCurrent: () -> Boolean,
    onProgress: (PicacgImageProgress) -> Unit,
): Response = newBuilder()
    .body(
        ProgressResponseBody(
            body,
            object : ProgressListener {
                override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                    if (isCurrent()) onProgress(PicacgImageProgress(bytesRead, contentLength))
                }
            },
        ),
    )
    .build()
