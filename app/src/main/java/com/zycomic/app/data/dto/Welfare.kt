package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/** 签到福利请求：date 为当前月份1号，如 2026-10-01 */
@Serializable
data class WelfareRequest(
    val date: String = "",
)

/** 签到福利响应 */
@Serializable
data class WelfareResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: WelfareData? = null,
)

@Serializable
data class WelfareData(
    val sign_list: List<SignDay> = emptyList(),
    val consecutive_sign: Int = 0,
    val check_ad_bonus_today: Boolean = false,
    val ad_clicks: Int = 0,
    val prev_date: String = "",
    val next_date: String = "",
    val current_month: String = "",
)

@Serializable
data class SignDay(
    val status: String = "",
    val index: Int = 0,
    val date: String = "",
)

/** 积分明细项 */
@Serializable
data class PointLog(
    val id: Int = 0,
    val user_id: Int = 0,
    val title: String = "",
    val point: Int = 0,
    val create_time: String = "",
    val consecutive_sign_days: Int = 0,
)

/** 积分明细列表响应 */
@Serializable
data class PointLogResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: PointLogData? = null,
)

@Serializable
data class PointLogData(
    val list: List<PointLog> = emptyList(),
    val uid: Int = 0,
    val consecutive_sign: Int = 0,
)
