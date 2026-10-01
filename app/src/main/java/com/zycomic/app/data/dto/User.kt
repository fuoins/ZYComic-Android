package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/** 用户信息 */
@Serializable
data class User(
    val uid: Int = 0,
    val point: Int = 0,
    val level: Int = 0,
    @kotlinx.serialization.SerialName("favorite_count")
    val favoriteCount: Int = 0,
    val nickname: String = "",
)
