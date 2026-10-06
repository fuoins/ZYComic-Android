package com.zycomic.app.data.repository

import android.util.Log
import com.zycomic.app.data.dto.ChapterContent
import com.zycomic.app.net.DevConfig
import com.zycomic.app.net.NetworkModule
import com.zycomic.app.net.RouteManager
import com.zycomic.app.net.SpeedTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

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
                autoSelectImgDomain(data.currentImgDomain, data.imgDomains)
            }

            return data
        } catch (e: Exception) {
            Log.e(TAG, "获取章节内容失败 chapterId=$chapterId", e)
            throw e
        }
    }

    /**
     * 自动选择图源：
     * - 已有图源：切换到该图源，用测速结果选最快IP
     * 新图源：DoH查询IP + 添加配置 + 动态更新代理 + 切换
     */
    private suspend fun autoSelectImgDomain(domain: String, allDomains: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                // 1. 检查是否是已有图源
                val existingIndex = RouteManager.imgDomains.indexOf(domain)
                if (existingIndex >= 0) {
                    // 已有图源：切换
                    if (RouteManager.imgIndex != existingIndex) {
                        RouteManager.setImgHost(existingIndex)
                        Log.d(TAG, "切换到已有图源: $domain (index=$existingIndex)")
                    }
                    return@withContext
                }

                // 2. 新图源：DoH 查询 IP
                Log.d(TAG, "发现新图源: $domain，开始 DoH 查询 IP")
                val ips = queryDohAli(domain)
                if (ips.isEmpty()) {
                    Log.w(TAG, "DoH 查询失败，新图源 $domain 无 IP，跳过配置更新")
                    // 即使没有IP也切换图源，用系统DNS尝试
                    addNewImgDomainToRouteManager(domain)
                    return@withContext
                }

                // 3. 添加到 DevConfig rule + sni
                val currentConfig = DevConfig.getConfigJson()
                val root = JSONObject(currentConfig)
                val ruleObj = root.optJSONObject("rule") ?: JSONObject()
                ruleObj.put(domain, org.json.JSONArray(ips))
                root.put("rule", ruleObj)

                val sniArr = root.optJSONArray("sni") ?: org.json.JSONArray()
                val sniSet = mutableSetOf<String>()
                for (i in 0 until sniArr.length()) {
                    sniSet.add(sniArr.getString(i))
                }
                sniSet.add(domain)
                root.put("sni", org.json.JSONArray(sniSet.toList()))

                val newConfig = root.toString()
                DevConfig.applyConfig(newConfig)
                Log.d(TAG, "新图源 $domain 已添加到配置（${ips.size}个IP）")

                // 4. 更新 RouteManager 图源列表并切换
                addNewImgDomainToRouteManager(domain)

                // 5. 后台测速新图源，更快则切过去（fire-and-forget）
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val newDelay = SpeedTester.testSingleImgHost(domain)
                        val curDelay = SpeedTester.testSingleImgHost(RouteManager.imgHost)
                        if (newDelay < curDelay) {
                            val idx = RouteManager.imgDomains.indexOf(domain)
                            if (idx >= 0 && RouteManager.imgIndex != idx) RouteManager.setImgHost(idx)
                        }
                    } catch (_: Exception) {}
                }

            } catch (e: Exception) {
                Log.e(TAG, "自动选择图源失败: $domain", e)
            }
        }
    }

    /** 添加新图源到 RouteManager 并切换。 */
    private fun addNewImgDomainToRouteManager(domain: String) {
        val currentList = RouteManager.imgDomains.toMutableList()
        if (!currentList.contains(domain)) {
            currentList.add(domain)
            RouteManager.updateImgDomains(currentList)
        }
        val index = RouteManager.imgDomains.indexOf(domain)
        if (index >= 0 && RouteManager.imgIndex != index) {
            RouteManager.setImgHost(index)
            Log.d(TAG, "切换到新图源: $domain (index=$index)")
        }
    }

    /**
     * 阿里云 DoH 查询 A 记录。
     * @return IP 列表，失败返回空列表
     */
    private suspend fun queryDohAli(domain: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            val url = "https://dns.alidns.com/resolve?name=$domain&type=A"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val answer = json.optJSONArray("Answer") ?: return@withContext emptyList()
                val ips = mutableListOf<String>()
                for (i in 0 until answer.length()) {
                    val item = answer.getJSONObject(i)
                    if (item.optInt("type") == 1) {
                        ips.add(item.getString("data"))
                    }
                }
                Log.d(TAG, "DoH查询 $domain -> $ips")
                ips
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoH查询失败: $domain", e)
            emptyList()
        }
    }

    /**
     * 拼接完整图片 URL：优先用 _CURRENT_IMG_DOMAIN，否则用 img_domains[0]。
     * @return 完整图片 URL 列表
     */
    fun getImageUrls(chapterContent: ChapterContent): List<String> {
        val domain = if (RouteManager.useFastestImgForAll) {
            RouteManager.fastestImgDomain ?: chapterContent.currentImgDomain.ifBlank {
                chapterContent.imgDomains.firstOrNull() ?: return emptyList()
            }
        } else {
            chapterContent.currentImgDomain.ifBlank {
                chapterContent.imgDomains.firstOrNull() ?: return emptyList()
            }
        }
        return chapterContent.piclist.map { path ->
            // piclist 可能已经是完整 URL（https://domain/...），直接使用；否则拼接域名
            if (path.startsWith("http://") || path.startsWith("https://")) {
                path
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
        chapterCache.get(chapterId)?.let { return it }
        val content = getChapterContent(chapterId)
        val urls = getImageUrls(content)
        chapterCache.put(chapterId, urls)
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
