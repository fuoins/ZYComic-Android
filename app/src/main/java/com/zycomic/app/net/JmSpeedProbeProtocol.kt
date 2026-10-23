package com.zycomic.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Request paths and response validation shared by JM speed probes. */
internal object JmSpeedProbeProtocol {
    // /setting 是客户端配置接口（version/store_link/img_host 等扁平键值），解密后约 2KB 且 code=200；
    // 相比首页推荐聚合页 /promote（解密后约 43KB）更轻量，同时仍走完整签名+AES 解密链路，适合线路探活。
    const val LINE_PATH = "/setting?app_img_shunt=1&express=&lang=TW"
    const val IMAGE_PATH = "/media/albums/553653_3x4.jpg"

    /** Bounded image-body sample; this verifies a prefix, not a full download or full image decode. */
    const val IMAGE_PROBE_BYTES = BoundedImageProbeSampler.DEFAULT_MAX_BYTES * 1L

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
