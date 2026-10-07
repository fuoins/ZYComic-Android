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
)
