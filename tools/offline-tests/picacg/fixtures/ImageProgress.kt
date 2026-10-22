package com.zycomic.app.reader

import okhttp3.Response

internal data class PicacgImageProgress(val bytesRead: Long, val contentLength: Long) {
    val percentage: Int? = if (contentLength > 0) ((bytesRead * 100) / contentLength).toInt() else null
}

internal fun Response.withPicacgImageProgress(
    isCurrent: () -> Boolean,
    onProgress: (PicacgImageProgress) -> Unit,
): Response = this
