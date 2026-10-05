package com.zycomic.app.net

import android.content.Context
import java.io.File
import java.io.OutputStream

object ImageCacheManager {
    private const val CACHE_DIR = "image_encrypted"
    private const val MAX_SIZE = 200L * 1024 * 1024
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    private lateinit var cacheDir: File

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, CACHE_DIR)
        cacheDir.mkdirs()
        cleanupExpired()
    }

    fun get(url: String): File? {
        val f = File(cacheDir, md5(url))
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > TTL_MS) {
            f.delete()
            return null
        }
        return f
    }

    fun putStream(url: String): OutputStream =
        File(cacheDir, md5(url) + ".tmp").outputStream()

    fun commit(url: String) {
        File(cacheDir, md5(url) + ".tmp").renameTo(File(cacheDir, md5(url)))
        if (cacheSize() > MAX_SIZE) evictLRU()
    }

    private fun md5(url: String): String = Crypto.md5Hex(url)

    private fun cacheSize(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun evictLRU() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = cacheSize()
        for (f in files) {
            if (total <= MAX_SIZE * 0.8) break
            total -= f.length()
            f.delete()
        }
    }

    private fun cleanupExpired() {
        cacheDir.listFiles()?.forEach { f ->
            if (System.currentTimeMillis() - f.lastModified() > TTL_MS) f.delete()
        }
    }
}
