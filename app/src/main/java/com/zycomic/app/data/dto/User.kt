package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/** 用户信息 */
@Serializable
data class User(
    @Serializable(with = StringOrIntSerializer::class)
    val uid: String = "",
    val point: Int = 0,
    val level: Int = 0,
    @kotlinx.serialization.SerialName("favorite_count")
    val favoriteCount: Int = 0,
    val nickname: String = "",
    val username: String = "",
)

/**
 * 已屏蔽标签响应。
 * 服务端格式：{"code":1,"data":{"data":{"blacklisted_tags":["标签1","标签2"]}}}
 * 双层 data 嵌套。
 */
@Serializable
data class BlacklistResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: BlacklistOuterData? = null,
)

@Serializable
data class BlacklistOuterData(
    val data: BlacklistInnerData? = null,
)

@Serializable
data class BlacklistInnerData(
    @kotlinx.serialization.SerialName("blacklisted_tags")
    val blacklistedTags: List<String> = emptyList(),
    /**
     * 已屏蔽漫画列表（抓包实测字段）：
     * `"blacklisted_books":[{"id":123836,"book_name":"被恶棍们抚养中","cover_url":"https://.../cover_1.jpg"}]`
     */
    @kotlinx.serialization.SerialName("blacklisted_books")
    val blacklistedBooks: List<BlacklistedBook> = emptyList(),
)

/** 已屏蔽漫画条目（来自 getBlacklist）。id 为服务端真实 bookId，可用于取消屏蔽。 */
@Serializable
data class BlacklistedBook(
    @Serializable(with = StringOrIntSerializer::class)
    val id: String = "",
    @kotlinx.serialization.SerialName("book_name")
    val bookName: String = "",
    @kotlinx.serialization.SerialName("cover_url")
    val coverUrl: String = "",
)

/**
 * 漫画屏蔽 toggle 的响应体（单层 data）：
 * `{"code":1,"data":{"success":true,"message":"已加入屏蔽","is_blacklisted":true},"msg":"已加入屏蔽"}`
 */
@Serializable
data class BookBlacklistData(
    val success: Boolean = false,
    val message: String = "",
    @kotlinx.serialization.SerialName("is_blacklisted")
    val isBlacklisted: Boolean = false,
)
