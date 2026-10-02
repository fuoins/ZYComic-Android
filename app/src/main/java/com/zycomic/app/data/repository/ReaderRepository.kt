package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.ChapterContent
import com.zycomic.app.net.NetworkModule
import java.io.IOException

/**
 * 章节内容 LRU 缓存：最近 5 章的页面 URL 列表。
 *
 * - 用 LinkedHashMap access-order 实现 LRU。
 * - key 为章节 ID 字符串（"123"）。
 */
class ChapterCache(private val maxSize: Int = 5) {

    private val map = object : LinkedHashMap<String, List<String>>(
        maxSize, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
            return size > maxSize
        }
    }

    /**
     * 获取缓存的页面 URL 列表。命中时移到 MRU 位置（不删除）。
     * @return 缓存的页面 URL 列表，未命中返回 null
     */
    @Synchronized
    fun get(chapterId: String): List<String>? = map[chapterId]

    /**
     * 写入缓存。已存在则覆盖并移到 MRU 位置；超过容量自动淘汰 LRU 条目。
     */
    @Synchronized
    fun put(chapterId: String, pages: List<String>) {
        map[chapterId] = pages
    }

    /** 是否包含某章缓存 */
    @Synchronized
    fun contains(chapterId: String): Boolean = map.containsKey(chapterId)

    /** 清空缓存 */
    @Synchronized
    fun clear() = map.clear()
}

/**
 * 阅读器仓库：章节内容获取 + 图片 URL 拼接 + LRU 缓存。
 */
object ReaderRepository {

    private val api get() = NetworkModule.api

    /** 章节缓存：全局单例，最近 5 章 */
    val chapterCache = ChapterCache(maxSize = 5)

    // ==================== 章节内容 ====================

    /** 获取章节内容（piclist + img_domains）。 */
    suspend fun getChapterContent(chapterId: String): ChapterContent {
        try {
            val resp = api.chapters(chapterId)
            if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "获取章节内容失败" })
            val data = resp.data ?: throw IOException("章节内容为空")
            Log.d("ReaderRepo", "章节$chapterId: piclist=${data.piclist.size}, imgDomains=${data.imgDomains}, current=${data.currentImgDomain}")
            return data
        } catch (e: Exception) {
            Log.e("ReaderRepo", "获取章节内容失败 chapterId=$chapterId", e)
            throw e
        }
    }

    /**
     * 拼接完整图片 URL：优先用 _CURRENT_IMG_DOMAIN，否则用 img_domains[0]。
     * @return 完整图片 URL 列表
     */
    fun getImageUrls(chapterContent: ChapterContent): List<String> {
        val domain = chapterContent.currentImgDomain.ifBlank {
            chapterContent.imgDomains.firstOrNull() ?: return emptyList()
        }
        return chapterContent.piclist.map { path ->
            // 兼容路径是否已带斜杠
            val sep = if (domain.endsWith("/") || path.startsWith("/")) "" else "/"
            "https://$domain$sep$path"
        }
    }

    // ==================== 缓存 ====================

    /** 从缓存获取某章已拼接好的页面 URL 列表。 */
    fun getCachedPages(chapterId: String): List<String>? = chapterCache.get(chapterId)

    /** 写入某章页面 URL 列表到缓存。 */
    fun putCachedPages(chapterId: String, pages: List<String>) {
        chapterCache.put(chapterId, pages)
    }

    /**
     * 获取章节页面 URL：先查缓存，未命中则拉取章节内容并拼接 URL，写入缓存。
     */
    suspend fun getChapterPages(chapterId: String): List<String> {
        chapterCache.get(chapterId)?.let { return it }
        val content = getChapterContent(chapterId)
        val urls = getImageUrls(content)
        chapterCache.put(chapterId, urls)
        return urls
    }
}
