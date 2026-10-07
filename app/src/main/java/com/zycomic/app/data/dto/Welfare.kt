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
    val user_data: WelfareUserData? = null,
    val sign_list: List<SignDay> = emptyList(),
    val prev_date: String = "",
    val next_date: String = "",
    val current_month: Int = 0,
    val consecutive_sign: Int = 0,
    @Serializable(with = BooleanOrIntSerializer::class)
    val ad_bonus: Boolean = false,
    @Serializable(with = BooleanOrIntSerializer::class)
    val check_ad_bonus_today: Boolean = false,
)

@Serializable
data class WelfareUserData(
    val point: Int = 0,
    val consecutiveDays: Int = 0,
    val availableCount: Int = 0,
)

@Serializable
data class SignDay(
    val date: String = "",
    val status: String = "",
    @Serializable(with = IntOrStringSerializer::class)
    val index: Int? = null,
) {
    val isSigned: Boolean get() = status == "signedin"
    val isMissed: Boolean get() = status == "signednot"
    val isFuture: Boolean get() = status == "signedyet"
    val isPlaceholder: Boolean get() = status.isEmpty()
    val dayNum: Int? get() = index
}

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

@Serializable
data class AdBonusRequest(
    val action: String,
)

@Serializable
data class AdBonusResponse(
    val code: Int = 0,
    val data: AdBonusData? = null,
    val msg: String = "",
)

@Serializable
data class AdBonusData(
    val status: String = "",
    val msg: String = "",
)
