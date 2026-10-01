package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/**
 * 列表数据包装。
 * 服务端返回格式为 {"code":1,"data":{"list":[...]}}，data 是对象而非直接数组。
 */
@Serializable
data class ListData<T>(
    val list: List<T> = emptyList(),
)

/** 排行榜响应：data={list:[...]} */
typealias RankResponse = ApiResponse<ListData<Manga>>

/**
 * 最近更新响应。
 * nums 在响应顶层（不在 data 内），由 [ApiResponse.nums] 承载。
 * data={list:[...]}。
 */
typealias NewestResponse = ApiResponse<ListData<Manga>>

/** 搜索响应：参数 k，data={list:[...]} */
typealias SearchResponse = ApiResponse<ListData<Manga>>

/** 分类响应：data={list:[...]}，tag 为标签名字符串 */
typealias ClassResponse = ApiResponse<ListData<Manga>>

/** 标签列表 data：data={list:[{id,name},...]}，扁平列表 */
@Serializable
data class TagListData(
    val list: List<TagItem> = emptyList(),
)

/** 标签列表响应 */
@Serializable
data class TagListResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: TagListData? = null,
)
