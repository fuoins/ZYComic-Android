package com.zycomic.app.net

import android.net.Uri
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * 详情页封面长按菜单：下载封面字节并保存/分享。
 * 下载走 [NetworkModule.imageClient]，由 ImageInterceptor 自动补 Referer/UA 并解密。
 */
object CoverArtHelper {

    /** 拉取封面字节；非 2xx / 空 / 解密后非图片均抛 IOException。 */
    suspend fun fetchCover(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        NetworkModule.imageClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("封面请求失败: HTTP ${resp.code}")
            }
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) {
                throw IOException("封面内容为空")
            }
            if (!isImageMagic(bytes)) {
                throw IOException("封面不是有效图片")
            }
            bytes
        }
    }

    /** 保存封面到相册(Pictures)，返回 Uri。 */
    fun save(bytes: ByteArray, name: String): Uri {
        return Injekt.get<ImageSaver>().save(
            Image.Page(
                inputStream = { ByteArrayInputStream(bytes) },
                name = name,
                location = Location.Pictures.create(),
            ),
        )
    }

    /** 把封面写到 Cache 并返回可分享的 Uri。 */
    fun shareToCache(bytes: ByteArray, name: String): Uri {
        return Injekt.get<ImageSaver>().save(
            Image.Page(
                inputStream = { ByteArrayInputStream(bytes) },
                name = name,
                location = Location.Cache,
            ),
        )
    }

    private fun isImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes.size >= 12 && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) return true
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()) return true
        return false
    }
}
