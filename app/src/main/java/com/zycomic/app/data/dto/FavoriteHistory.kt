package com.zycomic.app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 收藏列表项。
 *
 * 注意：
 * - book_id / book_name / book_img 均为字符串字段（snake_case）。
 * - end 为连载状态文本（可能为空），isNew 标记是否有更新。
 * - readLast 为“读到”章节名，chapterName 为“最新”章节名。
 */
@Serializable
data class FavoriteItem(
    @Serializable(with = StringOrIntSerializer::class)
    val id: String = "",
    @SerialName("book_id")
    @Serializable(with = StringOrIntSerializer::class)
    val bookId: String = "",
    @SerialName("book_name") val bookName: String = "",
    @SerialName("book_img") val bookImg: String = "",
    val end: String = "",
    @SerialName("chapter_name") val chapterName: String = "",
    @SerialName("read_last") val readLast: String = "",
    @SerialName("last_time") val lastTime: String = "",
    @SerialName("is_new")
    @Serializable(with = BooleanOrIntSerializer::class)
    val isNew: Boolean = false,
)

/**
 * 阅读历史项。
 * 字段：id（历史记录 id）/ book_id / book_name / book_img / end / chapter_name / last_time。
 */
@Serializable
data class HistoryItem(
    // 服务端历史记录"行主键"，仅用于列表展示/去重，删除接口不用它。
    @Serializable(with = StringOrIntSerializer::class)
    val id: String = "",
    // 漫画 id（封面路径 /book/id/<bookId>/ 与之相等）；删除 ids=、进详情/阅读、收藏都用它。
    @SerialName("book_id")
    @Serializable(with = StringOrIntSerializer::class)
    val bookId: String = "",
    @SerialName("book_name") val bookName: String = "",
    @SerialName("book_img") val bookImg: String = "",
    val end: String = "",
    @SerialName("chapter_name") val chapterName: String = "",
    @SerialName("last_time") val lastTime: String = "",
)

/** 收藏分类文件夹 */
@Serializable
data class Folder(
    val id: Int = 0,
    @SerialName("title") val name: String = "",
    val count: Int = 0,
)

/** 收藏分类列表响应 */
@Serializable
data class FolderListResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: List<Folder> = emptyList(),
)
