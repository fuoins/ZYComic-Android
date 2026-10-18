package com.zycomic.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Request paths and response validation shared by JM speed probes. */
internal object JmSpeedProbeProtocol {
    const val LINE_PATH = "/promote?page=0"
    const val IMAGE_PATH = "/media/albums/553653_3x4.jpg"

    /** Accepts only a complete, decrypted JSON object or array—not an API envelope or scalar. */
    fun isValidDecryptedJson(payload: String): Boolean {
        if (payload.isBlank()) return false
        val root = runCatching { Json.parseToJsonElement(payload) }.getOrNull() ?: return false
        if (root !is JsonObject && root !is kotlinx.serialization.json.JsonArray) return false
        return !root.isEncryptedApiEnvelope()
    }

    /** Requires an image media type and a recognized image file signature. */
    fun isValidImageResponse(contentType: String?, body: ByteArray): Boolean {
        val mediaType = contentType?.substringBefore(';')?.trim().orEmpty()
        if (!mediaType.startsWith("image/", ignoreCase = true)) return false
        return body.hasImageMagic()
    }

    private fun JsonElement.isEncryptedApiEnvelope(): Boolean {
        val objectValue = this as? JsonObject ?: return false
        val code = objectValue["code"] as? JsonPrimitive
        val data = objectValue["data"] as? JsonPrimitive
        return code?.content == "200" && data?.isString == true
    }

    private fun ByteArray.hasImageMagic(): Boolean =
        startsWithBytes(JPEG_MAGIC) || startsWithBytes(PNG_MAGIC) ||
            startsWithBytes(GIF87_MAGIC) || startsWithBytes(GIF89_MAGIC) ||
            (size >= 12 && startsWithBytes(RIFF_MAGIC) && copyOfRange(8, 12).contentEquals(WEBP_MAGIC))

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val GIF87_MAGIC = "GIF87a".encodeToByteArray()
    private val GIF89_MAGIC = "GIF89a".encodeToByteArray()
    private val RIFF_MAGIC = "RIFF".encodeToByteArray()
    private val WEBP_MAGIC = "WEBP".encodeToByteArray()
}
