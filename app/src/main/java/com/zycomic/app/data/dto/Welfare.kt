package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/** 签到福利响应 */
@Serializable
data class WelfareResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: WelfareData? = null,
)

@Serializable
data class WelfareData(
    val point: Int = 0,
    val continuous: Int = 0,       // 连续签到天数
    val signed: Int = 0,          // 今日是否已签 0/1
)

/** 积分明细项 */
@Serializable
data class PointLog(
    val id: Int = 0,
    val point: Int = 0,
    val remark: String = "",
    val addtime: String = "",
)

/** 积分明细列表响应 */
@Serializable
data class PointLogResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: List<PointLog> = emptyList(),
)
