package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/** 收藏项（与 Manga 结构基本一致，可能附带收藏元信息） */
@Serializable
data class FavoriteItem(
    val id: Int = 0,
    val name: String = "",
    val picx: String = "",
    val author: List<String> = emptyList(),
    val state: Int = 0,
    val score: String = "",
    val fav: Int = 0,
    val start: Int = 0,           // 继续阅读章节 ID
    @kotlinx.serialization.SerialName("category_name")
    val categoryName: String = "",
)

/** 阅读历史项 */
@Serializable
data class HistoryItem(
    val id: Int = 0,
    val name: String = "",
    val picx: String = "",
    val author: List<String> = emptyList(),
    val chapterId: Int = 0,        // 上次阅读章节 ID
    val chapterName: String = "",
    val addtime: String = "",
)

/** 收藏分类文件夹 */
@Serializable
data class Folder(
    val id: Int = 0,
    val name: String = "",
    val count: Int = 0,
)

/** 收藏分类列表响应 */
@Serializable
data class FolderListResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: List<Folder> = emptyList(),
)
