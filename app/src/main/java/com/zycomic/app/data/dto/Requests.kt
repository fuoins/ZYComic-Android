package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val captcha: String = "",
)

/**
 * 单本收藏操作请求体（POST api/detail/favorite）。
 * 注意：addFavorite 传 val=0，removeFavorite 传 val=1（不要写反）。
 * book_id 和 folder_id 均为数字。
 */
@Serializable
data class FavoriteRequest(
    /** 0=收藏(add), 1=取消收藏(remove) */
    val `val`: Int,
    @SerialName("book_id")
    val bookId: Int,
    @SerialName("folder_id")
    val folderId: Int = 0,
)

/**
 * 批量取消收藏请求体（POST api/users/favorite）。
 * body = {"ids":"id1,id2","action":"del"}
 */
@Serializable
data class BatchFavoriteRequest(
    /** 逗号分隔的 book_id 字符串，如 "1,2,3" */
    val ids: String,
    val action: String = "del",
)

/**
 * 收藏夹操作请求体（POST api/users/favorite_folder）。
 *
 * 各 action 对应的字段：
 * - 创建收藏夹：action=moveToFolder，只传 folder_name
 * - 重命名：action=renameFolder，folder_id + folder_name
 * - 删除：action=delFolder，folder_id
 *
 * 注意：移动漫画到收藏夹不用此接口，用 POST api/detail/favorite（val=0, book_id, folder_id）。
 *
 * folder_id 为字符串。
 */
@Serializable
data class FavoriteFolderRequest(
    /** moveToFolder / renameFolder / delFolder */
    val action: String,
    @SerialName("folder_id")
    val folderId: String = "0",
    @SerialName("folder_name")
    val folderName: String = "",
)

/** 添加屏蔽标签请求体：{"selectedTags": ["tag1", "tag2"]} */
@Serializable
data class AddBlacklistRequest(
    val selectedTags: List<String> = emptyList(),
)

/** 删除屏蔽标签请求体：{"removeTags": ["tag1", "tag2"]} */
@Serializable
data class RemoveBlacklistRequest(
    val removeTags: List<String> = emptyList(),
)
