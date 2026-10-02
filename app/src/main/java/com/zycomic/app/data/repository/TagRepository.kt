package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.AddBlacklistRequest
import com.zycomic.app.data.dto.RemoveBlacklistRequest
import com.zycomic.app.net.NetworkModule
import java.io.IOException

/**
 * 屏蔽标签仓库。
 *
 * - 获取 / 添加 / 删除已屏蔽标签。
 * - 过滤开关在内存中维护（默认开启）。
 *
 * API 路径：
 * - 获取已屏蔽：GET /api/users/getBlacklist，响应双层 data {"data":{"data":{"blacklisted_tags":[...]}}}
 * - 添加屏蔽：POST /api/classes/addBlacklist，body = {"selectedTags": ["tag1", "tag2"]}
 * - 删除屏蔽：POST /api/classes/removeBlacklist，body = {"removeTags": ["tag1", "tag2"]}
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

    /** 获取已屏蔽标签列表。解析双层 data：data.data.blacklisted_tags。 */
    suspend fun getBlackTags(): List<String> {
        val resp = api.getBlacklist()
        return resp.data?.data?.blacklistedTags ?: emptyList()
    }

    /**
     * 添加屏蔽标签。
     * body = {"selectedTags": ["tag1", "tag2", ...]}
     */
    suspend fun addBlackTags(tags: List<String>) {
        if (tags.isEmpty()) return
        val resp = api.addBlacklist(AddBlacklistRequest(selectedTags = tags))
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "添加屏蔽标签失败" })
    }

    /**
     * 删除屏蔽标签。
     * body = {"removeTags": ["tag1", "tag2", ...]}
     */
    suspend fun removeBlackTags(tags: List<String>) {
        if (tags.isEmpty()) return
        val resp = api.removeBlacklist(RemoveBlacklistRequest(removeTags = tags))
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "删除屏蔽标签失败" })
    }

    // ==================== 过滤开关 ====================

    /** 读取过滤屏蔽标签开关（默认开启）。 */
    fun isFilterEnabled(): Boolean = filterEnabled

    /** 设置过滤屏蔽标签开关。 */
    fun setFilterEnabled(enabled: Boolean) {
        filterEnabled = enabled
    }
}
