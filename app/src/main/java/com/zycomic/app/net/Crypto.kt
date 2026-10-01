package com.zycomic.app.net

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加解密工具类。
 *
 * - MD5：32 位小写 hex。
 * - 接口响应：AES-256-ECB，key = MD5(timestamp + RESPONSE_SALT) 的 32 位 hex 字符串按 UTF-8 编码（32 字节）。
 * - 图片：AES/CBC/NOPADDING，key = iv = "my2ecret782ecret"（16 字节）。
 */
object Crypto {

    /** 接口响应解密 salt */
    const val RESPONSE_SALT = ",noiusdfy73osadjap012njdsfn"

    /** X-Token 计算 salt */
    const val XTOKEN_SALT = ",jsdaghuiaonfyudsfnkgjdfkdd"

    /** 图片解密 key（同时作为 iv），16 字节 */
    const val IMG_KEY = "my2ecret782ecret"

    /** 计算 MD5 并返回 32 位小写 hex */
    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 解密接口响应。
     * 模式 AES-256-ECB/PKCS5Padding；
     * 密钥 = MD5(timestamp + RESPONSE_SALT) 的 32 位 hex 字符串按 UTF-8 编码得到 32 字节。
     *
     * 输入是 base64 编码的加密数据，可能带 JSON 转义（\" \/）和首尾引号，需先清理。
     * timestamp 必须与请求 URL 中的 timestamp 完全一致（毫秒级）。
     */
    fun decryptResponse(cipherB64: String, timestamp: String): String {
        val keyHex = md5Hex(timestamp + RESPONSE_SALT)
        val key = SecretKeySpec(keyHex.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        // 清理：去首尾引号、JSON 转义的 \/ -> /、\" -> "
        val cleaned = cipherB64.trim()
            .removeSurrounding("\"")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
        val ct = Base64.decode(cleaned, Base64.DEFAULT)
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /**
     * 解密图片字节（一次性全量，不推荐用于大图；流式请用 [createImageCipher]）。
     * AES/CBC/NOPADDING，key = iv = [IMG_KEY]。
     * NOPADDING 解密后末尾可能保留填充字节，图片解码器会自动忽略。
     */
    fun decryptImage(encrypted: ByteArray): ByteArray {
        val cipher = createImageCipher()
        return cipher.doFinal(encrypted)
    }

    /**
     * 创建 AES/CBC/NOPADDING 解密 Cipher，用于 Okio CipherSource 流式解密。
     * key = iv = [IMG_KEY]。
     */
    fun createImageCipher(): Cipher {
        val k = IMG_KEY.toByteArray(Charsets.UTF_8)
        val key = SecretKeySpec(k, "AES")
        val cipher = Cipher.getInstance("AES/CBC/NOPADDING")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(k))
        return cipher
    }

    /** 判断字节数组是否是已加密图片（不是常见图片魔数即视为加密） */
    fun isEncryptedImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0]
        val b1 = bytes[1]
        // JPEG FF D8 / PNG 89 50 / WEBP 'R''I''F''F' / GIF 'G''I''F'
        val isJpeg = b0 == 0xFF.toByte() && b1 == 0xD8.toByte()
        val isPng = b0 == 0x89.toByte() && b1 == 0x50.toByte()
        val isWebp = b0 == 'R'.code.toByte() && b1 == 'I'.code.toByte()
        val isGif = b0 == 'G'.code.toByte() && b1 == 'I'.code.toByte()
        return !(isJpeg || isPng || isWebp || isGif)
    }
}
