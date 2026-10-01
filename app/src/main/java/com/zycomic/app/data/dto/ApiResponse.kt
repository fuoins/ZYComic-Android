package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/**
 * 通用响应包装。
 * 注意：newest 响应的 nums 在顶层（不在 data 内），故单独提取。
 */
@Serializable
data class ApiResponse<T>(
    val code: Int = 0,
    val msg: String = "",
    val data: T? = null,
    val nums: Int? = null,
)
