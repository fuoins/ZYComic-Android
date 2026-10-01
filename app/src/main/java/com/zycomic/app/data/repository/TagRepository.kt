package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.BlackTagRequest
import com.zycomic.app.net.NetworkModule
import java.io.IOException

/**
 * 屏蔽标签仓库。
 *
 * - 获取 / 添加 / 删除已屏蔽标签。
 * - 过滤开关在内存中维护（默认开启）。
 *
 * 注意：
 * - 添加屏蔽：body = {"selectedTags": ["tag1", "tag2"]}
 * - 删除屏蔽：body = {"removeTags": ["tag1", "tag2"]}
 * - List<String> 由 kotlinx.serialization 自动序列化为 JSONArray。
 */
object TagRepository {

    private val api get() = NetworkModule.api

    /**
     * 过滤屏蔽标签开关（内存中维护，默认开启）。
     * UI 层调用 [isFilterEnabled] 判断是否在分类/搜索/排行中过滤被屏蔽漫画。
     */
    @Volatile
    private var filterEnabled: Boolean = true

    // ==================== 屏蔽标签 CRUD ====================

    /** 获取已屏蔽标签列表。 */
    suspend fun getBlackTags(): List<String> {
        val resp = api.blackTag()
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取屏蔽标签失败" })
        return resp.data ?: emptyList()
    }

    /**
     * 添加屏蔽标签。
     * body = {"selectedTags": ["tag1", "tag2", ...]}
     * List<String> 由 kotlinx.serialization 自动序列化为 JSONArray。
     */
    suspend fun addBlackTags(tags: List<String>) {
        if (tags.isEmpty()) return
        val resp = api.setBlackTag(BlackTagRequest(selectedTags = tags))
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "添加屏蔽标签失败" })
    }

    /**
     * 删除屏蔽标签。
     * body = {"removeTags": ["tag1", "tag2", ...]}
     * List<String> 由 kotlinx.serialization 自动序列化为 JSONArray。
     */
    suspend fun removeBlackTags(tags: List<String>) {
        if (tags.isEmpty()) return
        val resp = api.setBlackTag(BlackTagRequest(removeTags = tags))
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "删除屏蔽标签失败" })
    }

    // ==================== 过滤开关 ====================

    /** 读取过滤屏蔽标签开关（默认开启）。 */
    fun isFilterEnabled(): Boolean = filterEnabled

    /** 设置过滤屏蔽标签开关。 */
    fun setFilterEnabled(enabled: Boolean) {
        filterEnabled = enabled
    }
}
