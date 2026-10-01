package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/** 排行榜响应：data 为漫画列表 */
typealias RankResponse = ApiResponse<List<Manga>>

/**
 * 最近更新响应。
 * nums 在响应顶层（不在 data 内），由 [ApiResponse.nums] 承载。
 * data 为漫画列表。
 */
typealias NewestResponse = ApiResponse<List<Manga>>

/** 搜索响应：参数 k，data 为漫画列表 */
typealias SearchResponse = ApiResponse<List<Manga>>

/** 分类响应：data 为漫画列表，tag 为标签名字符串 */
typealias ClassResponse = ApiResponse<List<Manga>>

/** 标签列表响应 */
@Serializable
data class TagListResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: List<List<TagGroup>> = emptyList(),
)

/** 标签分组（分类页标签按组展示） */
@Serializable
data class TagGroup(
    val name: String = "",
    val list: List<TagItem> = emptyList(),
)
