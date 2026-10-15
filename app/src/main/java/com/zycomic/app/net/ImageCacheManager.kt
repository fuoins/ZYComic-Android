package com.zycomic.app.net

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object ImageCacheManager {
    private const val CACHE_DIR = "image_encrypted"
    private const val MAX_SIZE = 200L * 1024 * 1024
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    @Volatile
    private var cacheDir: File? = null
    private val urlLocks = ConcurrentHashMap<String, Any>()
    private val temporaryIds = AtomicLong()

    @Synchronized
    fun init(context: Context) {
        val dir = File(context.cacheDir, CACHE_DIR)
        dir.mkdirs()
        cacheDir = dir
        cleanupExpiredAndTemporary(dir)
    }

    fun get(url: String): File? {
        val file = File(cacheDirectory(), md5(url))
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > TTL_MS) {
            file.delete()
            return null
        }
        return file
    }

    /** Allocate a unique, uncommitted raw-image file for one endpoint attempt. */
    fun beginAttempt(url: String, generation: Int, sequence: Int, ip: String): File {
        val safeIp = ip.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val unique = temporaryIds.incrementAndGet()
        return File(cacheDirectory(), "${md5(url)}.tmp-$generation-$sequence-$safeIp-$unique").apply {
            delete()
            parentFile?.mkdirs()
        }
    }

    /** Commit only a fully validated winner whose page-generation still owns the write. */
    fun commitAttempt(
        url: String,
        temporary: File,
        stillOwner: () -> Boolean,
        ownerLock: Any? = null,
    ): Boolean {
        val key = md5(url)
        val lock = lockFor(key)
        val commit = {
            synchronized(lock) {
                if (!stillOwner()) {
                    temporary.delete()
                    false
                } else {
                    val target = File(cacheDirectory(), key)
                    if (target.exists()) target.delete()
                    if (!temporary.renameTo(target)) {
                        temporary.delete()
                        false
                    } else {
                        target.setLastModified(System.currentTimeMillis())
                        if (cacheSize() > MAX_SIZE) evictLRU()
                        true
                    }
                }
            }
        }
        return if (ownerLock == null) commit() else synchronized(ownerLock) { commit() }
    }

    fun abortAttempt(temporary: File?) {
        temporary?.delete()
    }

    /** Temporary plaintext response file; ResponseBody.close() removes it. */
    fun createResponseTemp(): File = File.createTempFile("zy-response-", ".tmp", cacheDirectory())

    fun remove(url: String): Boolean {
        val key = md5(url)
        val lock = lockFor(key)
        synchronized(lock) {
            val removed = File(cacheDirectory(), key).delete()
            cacheDirectory().listFiles()?.filter { it.name.startsWith("$key.tmp-") }?.forEach(File::delete)
            return removed
        }
    }

    /** Compatibility helpers retained for existing internal call sites. */
    fun putStream(url: String): OutputStream = File(cacheDirectory(), "${md5(url)}.tmp-legacy").outputStream()

    fun commit(url: String) {
        val temp = File(cacheDirectory(), "${md5(url)}.tmp-legacy")
        commitAttempt(url, temp) { true }
    }

    private fun cacheDirectory(): File = cacheDir ?: error("ImageCacheManager.init() must be called first")

    private fun lockFor(key: String): Any = synchronized(urlLocks) {
        urlLocks[key] ?: Any().also { urlLocks[key] = it }
    }

    private fun md5(url: String): String = Crypto.md5Hex(url)

    private fun cacheSize(): Long = cacheDirectory().listFiles()
        ?.asSequence()
        ?.filterNot(::isTemporary)
        ?.sumOf(File::length)
        ?: 0L

    private fun evictLRU() {
        val files = cacheDirectory().listFiles()
            ?.filterNot(::isTemporary)
            ?.sortedBy(File::lastModified)
            ?: return
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= MAX_SIZE * 0.8) break
            total -= file.length()
            file.delete()
        }
    }

    private fun cleanupExpiredAndTemporary(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (isTemporary(file) || now - file.lastModified() > TTL_MS) file.delete()
        }
    }

    private fun isTemporary(file: File): Boolean =
        file.name.contains(".tmp-") || file.name.startsWith("zy-response-")
}
