package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.HistoryItem
import com.zycomic.app.net.NetworkModule
import java.io.IOException

/**
 * 阅读历史仓库。
 *
 * - 删除历史：ids 在 URL query 参数里（不是 body），逗号由 Retrofit 自动 URL 编码为 %2C，body 为空。
 * - 判断没有更多页：某页返回 list 为空数组。
 */
object HistoryRepository {

    private val api get() = NetworkModule.api

    /**
     * 阅读历史分页列表。
     * @return Pair(历史列表, 是否还有更多)。list 为空数组表示没有更多页。
     */
    suspend fun getHistory(page: Int): Pair<List<HistoryItem>, Boolean> {
        val resp = api.history(page)
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取历史失败" })
        val list = resp.data?.list ?: emptyList()
        // 某页返回空数组 = 没有更多页
        val hasMore = list.isNotEmpty()
        return Pair(list, hasMore)
    }

    /**
     * 删除阅读历史（单个或多个）。
     * @param ids 逗号分隔的历史 ID 字符串，如 "1,2,3"。
     *        Retrofit @Query 会自动将逗号 URL 编码为 %2C。
     *        body 为空（无 @Body 参数）。
     */
    suspend fun deleteHistory(ids: String) {
        val resp = api.deleteHistory(ids = ids, action = "del")
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "删除历史失败" })
    }
}
