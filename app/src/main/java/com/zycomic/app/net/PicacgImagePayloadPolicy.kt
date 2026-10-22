package com.zycomic.app.net

import java.io.IOException

/** Shared, side-effect-free image response checks used by speed probes and runtime image paths. */
internal object PicacgImagePayloadPolicy {
    const val MAX_HEAD_BYTES = 32 * 1024

    fun isImageContentType(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.startsWith("image/", ignoreCase = true) == true

    /** JPEG, PNG, GIF, and WEBP signatures; content-type alone is never proof of image data. */
    fun hasImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return true
        }
        if (bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return true
        return bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
    }

    fun isUsableImage(statusCode: Int, contentType: String?, head: ByteArray): Boolean =
        statusCode in 200..299 && isImageContentType(contentType) && hasImageMagic(head)

    fun mayTryNextSource(error: IOException, cancelled: Boolean): Boolean =
        !cancelled && RuntimeRecoveryPolicy.isTransportFailure(error)
}
