package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class FavoriteRequest(
    /** 1=收藏, 0=取消收藏 */
    val `val`: Int,
    @kotlinx.serialization.SerialName("book_id")
    val bookId: Int,
    @kotlinx.serialization.SerialName("folder_id")
    val folderId: Int = 0,
)

@Serializable
data class BatchFavoriteRequest(
    /** 逗号分隔的 id 字符串，如 "1,2,3" */
    val ids: String,
    val action: String = "del",
)

@Serializable
data class FavoriteFolderRequest(
    /** moveToFolder / renameFolder / delFolder / moveOutFolder */
    val action: String,
    @kotlinx.serialization.SerialName("folder_id")
    val folderId: Int = 0,
    @kotlinx.serialization.SerialName("book_id")
    val bookId: Int = 0,
    @kotlinx.serialization.SerialName("new_name")
    val newName: String = "",
)

@Serializable
data class BlackTagRequest(
    val selectedTags: List<String>? = null,
    val removeTags: List<String>? = null,
)
