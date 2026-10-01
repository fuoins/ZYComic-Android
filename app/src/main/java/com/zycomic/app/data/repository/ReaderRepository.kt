package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.ChapterContent
import com.zycomic.app.net.NetworkModule
import java.io.IOException

/**
 * 章节内容 LRU 缓存：最近 5 章的页面 URL 列表。
 *
 * - 用 LinkedHashMap access-order 实现 LRU。
 * - 命中时移到链表末尾（最近使用），不删除。
 * - 超过 5 章时自动淘汰最久未使用的头部条目。
 */
class ChapterCache(private val maxSize: Int = 5) {

    private val map = object : LinkedHashMap<Int, List<String>>(
        maxSize, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<String>>?): Boolean {
            return size > maxSize
        }
    }

    /**
     * 获取缓存的页面 URL 列表。命中时移到 MRU 位置（不删除）。
     * @return 缓存的页面 URL 列表，未命中返回 null
     */
    @Synchronized
    fun get(chapterId: Int): List<String>? = map[chapterId]

    /**
     * 写入缓存。已存在则覆盖并移到 MRU 位置；超过容量自动淘汰 LRU 条目。
     */
    @Synchronized
    fun put(chapterId: Int, pages: List<String>) {
        map[chapterId] = pages
    }

    /** 是否包含某章缓存 */
    @Synchronized
    fun contains(chapterId: Int): Boolean = map.containsKey(chapterId)

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
    suspend fun getChapterContent(chapterId: Int): ChapterContent {
        val resp = api.chapters(chapterId)
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取章节内容失败" })
        return resp.data ?: throw IOException("章节内容为空")
    }

    /**
     * 拼接完整图片 URL：img_domains[0] + piclist[i]。
     * @return 完整图片 URL 列表
     */
    fun getImageUrls(chapterContent: ChapterContent): List<String> {
        val domain = chapterContent.imgDomains.firstOrNull() ?: return emptyList()
        return chapterContent.piclist.map { path ->
            // 兼容路径是否已带斜杠
            val sep = if (domain.endsWith("/") || path.startsWith("/")) "" else "/"
            "https://$domain$sep$path"
        }
    }

    // ==================== 缓存 ====================

    /** 从缓存获取某章已拼接好的页面 URL 列表。 */
    fun getCachedPages(chapterId: Int): List<String>? = chapterCache.get(chapterId)

    /** 写入某章页面 URL 列表到缓存。 */
    fun putCachedPages(chapterId: Int, pages: List<String>) {
        chapterCache.put(chapterId, pages)
    }

    /**
     * 获取章节页面 URL：先查缓存，未命中则拉取章节内容并拼接 URL，写入缓存。
     */
    suspend fun getChapterPages(chapterId: Int): List<String> {
        chapterCache.get(chapterId)?.let { return it }
        val content = getChapterContent(chapterId)
        val urls = getImageUrls(content)
        chapterCache.put(chapterId, urls)
        return urls
    }
}
