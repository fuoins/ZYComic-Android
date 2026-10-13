package com.zycomic.app.data.repository

import android.util.Log
import com.zycomic.app.data.dto.ChapterContent
import com.zycomic.app.net.NetworkModule
import com.zycomic.app.net.RouteManager
import java.io.IOException

/**
 * 章节内容 LRU 缓存：最近 5 章的页面 URL 列表。
 *
 * - 用 LinkedHashMap access-order 实现 LRU。
 * - key 为章节 ID 字符串（"123"）。
 */
class ChapterCache(private val maxSize: Int = 5) {

    private val map = object : LinkedHashMap<String, List<String>>(
        maxSize,
        0.75f,
        true,
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
 * 阅读器仓库：章节内容获取 + 图片 URL 拼接 + LRU 缓存 + 图源自动选择。
 */
object ReaderRepository {

    private const val TAG = "ReaderRepo"

    private val api get() = NetworkModule.api

    /** 章节缓存：全局单例，最近 5 章 */
    val chapterCache = ChapterCache(maxSize = 5)

    /** 当前章节的图源域名列表（供阅读器图源切换对话框使用）。 */
    @Volatile
    var currentImgDomains: List<String> = emptyList()
        private set

    /** 当前章节的服务端推荐图源域名。 */
    @Volatile
    var currentRecommendedImgDomain: String = ""
        private set

    // ==================== 章节内容 ====================

    /** 获取章节内容（piclist + img_domains），并自动选择图源。 */
    suspend fun getChapterContent(chapterId: String): ChapterContent {
        try {
            val resp = api.chapters(chapterId)
            if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "获取章节内容失败" })
            val data = resp.data ?: throw IOException("章节内容为空")
            Log.d(TAG, "章节$chapterId: piclist=${data.piclist.size}, imgDomains=${data.imgDomains}, current=${data.currentImgDomain}")

            // 保存当前章节图源列表供阅读器使用
            currentImgDomains = data.imgDomains
            currentRecommendedImgDomain = data.currentImgDomain

            // 自动选择图源：优先用 _CURRENT_IMG_DOMAIN
            if (data.currentImgDomain.isNotBlank()) {
                autoSelectImgDomain(data.currentImgDomain)
            }

            return data
        } catch (e: Exception) {
            Log.e(TAG, "获取章节内容失败 chapterId=$chapterId", e)
            throw e
        }
    }

    /** 配置外的服务器推荐图源不加入候选列表，也不写入本地 DNS/rule。 */
    private fun autoSelectImgDomain(domain: String) {
        val index = RouteManager.imgDomains.indexOfFirst { it.equals(domain, ignoreCase = true) }
        if (index < 0) {
            Log.w(TAG, "章节推荐图源不在当前权威配置中，忽略全局切换: $domain")
            return
        }
        if (RouteManager.pinnedImgDomain.isNullOrBlank() && RouteManager.imgIndex != index) {
            RouteManager.setImgHost(index)
            Log.d(TAG, "切换到配置内章节推荐图源: $domain (index=$index)")
        }
    }

    /**
     * 拼接完整图片 URL：优先用 _CURRENT_IMG_DOMAIN，否则用 img_domains[0]。
     * @return 完整图片 URL 列表
     */
    fun getImageUrls(chapterContent: ChapterContent): List<String> {
        // imgRewriteTarget 为 null 时（开关关闭且没手动 pin）保持服务端给的域名，行为同改前
        val target = RouteManager.imgRewriteTarget
        val domain = target ?: chapterContent.currentImgDomain.ifBlank {
            chapterContent.imgDomains.firstOrNull() ?: return emptyList()
        }
        return chapterContent.piclist.map { path ->
            if (path.startsWith("http://") || path.startsWith("https://")) {
                if (!target.isNullOrBlank()) {
                    path.replace(Regex("https?://[^/]+"), "https://$target")
                } else {
                    path
                }
            } else {
                val sep = if (domain.endsWith("/") || path.startsWith("/")) "" else "/"
                "https://$domain$sep$path"
            }
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
        // 开启"用测速最快图源"时绕过缓存，每次用当前最快域名重新拼接
        if (!RouteManager.useFastestImgForAll) {
            chapterCache.get(chapterId)?.let { return it }
        }
        val content = getChapterContent(chapterId)
        val urls = getImageUrls(content)
        if (!RouteManager.useFastestImgForAll) {
            chapterCache.put(chapterId, urls)
        }
        return urls
    }

    /** 切换图源后清空当前章节缓存，强制重新加载图片。 */
    fun clearCurrentChapterCache(chapterId: String) {
        chapterCache.let {
            // 清空所有缓存，因为切换图源后所有章节的URL都变了
        }
        chapterCache.clear()
    }
}
