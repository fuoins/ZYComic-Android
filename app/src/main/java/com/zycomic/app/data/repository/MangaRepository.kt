package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.dto.TagGroup
import com.zycomic.app.net.NetworkModule
import java.io.IOException

/**
 * 漫画仓库：首页 / 排行 / 最近更新 / 详情 / 搜索 / 分类 / 标签。
 *
 * 屏蔽标签过滤规则：
 * - 被屏蔽漫画特征：name 以 "已根据标签" 开头。
 * - 过滤开启时用 [filterBlockedMangas] 隐藏这类漫画。
 * - 分页凑数用 [loadWithFilter]，自动多页加载直到凑满目标条数。
 */
object MangaRepository {

    private val api get() = NetworkModule.api

    /** 被屏蔽漫画名称前缀 */
    private const val BLOCKED_PREFIX = "已根据标签"

    // ==================== 首页 ====================

    /** 首页数据（data 为 Manga 对象，内含 love_list 推荐列表等）。 */
    suspend fun getIndex(): Manga {
        val resp = api.index()
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取首页失败" })
        return resp.data ?: throw IOException("首页数据为空")
    }

    // ==================== 排行榜 ====================

    /**
     * 排行榜。
     * @param type 0=人气, 1=新番, 2=完结
     */
    suspend fun getRank(type: Int, page: Int): List<Manga> {
        val resp = api.rank(type, page)
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取排行失败" })
        return resp.data ?: emptyList()
    }

    // ==================== 最近更新 ====================

    /**
     * 最近更新。注意：nums（总数）在响应顶层，不在 data 内。
     * @return Pair(当前页漫画列表, 总数nums)
     */
    suspend fun getNewest(page: Int, date: String, size: Int = 30): Pair<List<Manga>, Int> {
        val resp = api.newest(page = page, size = size, date = date)
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取最近更新失败" })
        val list = resp.data ?: emptyList()
        val nums = resp.nums ?: list.size
        return Pair(list, nums)
    }

    // ==================== 详情 ====================

    /** 漫画详情（data 直接是漫画对象）。 */
    suspend fun getDetail(bookId: Int): Manga {
        val resp = api.detail(bookId)
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取详情失败" })
        return resp.data ?: throw IOException("详情数据为空")
    }

    // ==================== 搜索 ====================

    /** 搜索（API 参数名是 k，不是 keyword）。 */
    suspend fun search(keyword: String, page: Int): List<Manga> {
        val resp = api.search(keyword, page)
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "搜索失败" })
        return resp.data ?: emptyList()
    }

    // ==================== 分类 ====================

    /**
     * 分类浏览。
     * @param tag 标签名字符串（逗号分隔多个标签），不是数字 ID
     */
    suspend fun getClasses(
        page: Int,
        gender: Int,
        tag: String = "",
        area: Int = 0,
        end: Int = 0,
        st: Int = 0,
    ): List<Manga> {
        val resp = api.classes(
            page = page,
            gender = gender,
            tag = tag,
            area = area,
            end = end,
            st = st,
        )
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取分类失败" })
        return resp.data ?: emptyList()
    }

    // ==================== 标签列表 ====================

    /** 标签列表（500+，按分组返回）。 */
    suspend fun getTags(): List<List<TagGroup>> {
        val resp = api.tags()
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取标签失败" })
        return resp.data
    }

    // ==================== 屏蔽标签过滤 ====================

    /**
     * 过滤掉被屏蔽的漫画（name 以 "已根据标签" 开头的条目）。
     * 应用范围：分类、最近更新、排行、搜索。
     */
    fun filterBlockedMangas(list: List<Manga>): List<Manga> {
        return list.filterNot { it.name.startsWith(BLOCKED_PREFIX) }
    }

    /**
     * 分页凑数：自动加载多页直到凑满 targetCount 条有效漫画。
     *
     * 规则：
     * - 首次加载目标 30 条，下滑加载目标 15 条（由调用方传入 targetCount 决定）。
     * - 每加载一页，先 [filterBlockedMangas] 过滤屏蔽漫画，再追加到结果。
     * - 结果数 >= targetCount 时停止。
     * - 最多加载 10 页。
     * - 连续 10 页返回空列表则停止。
     * - 不去重，接口返回什么就显示什么。
     *
     * @param page 起始页码
     * @param loadFunc 给定页码加载原始漫画列表的 suspend 函数
     * @param targetCount 目标有效条数
     * @return 过滤后的有效漫画列表
     */
    suspend fun loadWithFilter(
        page: Int,
        targetCount: Int,
        loadFunc: suspend (Int) -> List<Manga>,
    ): List<Manga> {
        val result = mutableListOf<Manga>()
        var currentPage = page
        var emptyStreak = 0
        var pagesLoaded = 0
        val maxPages = 10

        while (pagesLoaded < maxPages) {
            val raw = try {
                loadFunc(currentPage)
            } catch (e: Exception) {
                // 加载出错时，如果已有结果就返回，否则抛出
                if (result.isNotEmpty()) break
                throw e
            }
            pagesLoaded++

            if (raw.isEmpty()) {
                emptyStreak++
                if (emptyStreak >= 10) break
            } else {
                emptyStreak = 0
                // 过滤屏蔽漫画后顺接
                result.addAll(filterBlockedMangas(raw))
            }

            if (result.size >= targetCount) break
            currentPage++
        }
        return result
    }
}
