package com.zycomic.app.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Reads a bounded prefix while handling short reads correctly. If a declared response length is known and
 * EOF arrives before that length while the sample is still below the bound, the response is truncated.
 * Reaching the bound is sufficient for a range/prefix probe; this does not validate or download the full image.
 */
internal object BoundedImageProbeSampler {
    const val DEFAULT_MAX_BYTES: Int = 32 * 1024

    fun read(
        input: InputStream,
        declaredLength: Long = -1L,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(declaredLength >= -1L) { "declaredLength must be -1 or non-negative" }

        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(minOf(maxBytes, 8 * 1024))
        while (output.size() < maxBytes) {
            val remaining = maxBytes - output.size()
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            when {
                count < 0 -> {
                    if (declaredLength >= 0L && output.size().toLong() < declaredLength) {
                        throw EOFException("Image probe body ended at ${output.size()} of $declaredLength declared bytes")
                    }
                    break
                }
                count == 0 -> {
                    // InputStream implementations should not return zero for a non-empty read, but avoid a
                    // busy loop and preserve progress for unusual throttled/test streams.
                    val next = input.read()
                    if (next < 0) {
                        if (declaredLength >= 0L && output.size().toLong() < declaredLength) {
                            throw EOFException("Image probe body ended at ${output.size()} of $declaredLength declared bytes")
                        }
                        break
                    }
                    output.write(next)
                }
                else -> output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }
}
