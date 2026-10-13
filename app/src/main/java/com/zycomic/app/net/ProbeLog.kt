package com.zycomic.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 图源探活结论。
 *
 * 提到包级而不是留在 [SpeedTester] 内部：日志格式化（[ProbeLog]）要能脱离 Android/网络单测，
 * 而这个三态正是"连不上"（网络问题，换 VPN/网络才有解）与"无图"（该域没镜像对象或防盗链，
 * 该改配置）的分界 —— 两者在 UI 上一律显示"超时"会掩盖完全不同的问题（1.0.2 修过）。
 */
enum class ImgVerdict { OK, UNREACHABLE, NO_IMAGE }

/**
 * 测活 / 测速日志行的字段拼装（纯函数）。
 *
 * 这份日志是"照着它手工更新 ruledns 配置"的唯一输入，所以每行都必须能直接映射到
 * doh.json 的字段：域名、有没有 IP 组、实际拨到哪个 IP、结论、状态码、延迟。
 * 结论口径沿用 [ImgVerdict] 三态：**连不上**（该换网络/VPN）与
 * **无图**（该删这个域或补路径）处理方式完全不同，不能都写成"超时"。
 */
object ProbeLog {

    fun line(
        host: String,
        inRule: Boolean,
        dialedIp: String?,
        verdict: ImgVerdict,
        httpCode: Int,
        delayMs: Long,
    ): String = buildString {
        append("probe host=").append(host)
        append(" inRule=").append(if (inRule) 1 else 0)
        append(" ip=").append(dialedIp ?: "-")
        append(" verdict=").append(verdict.name)
        append(" code=").append(if (httpCode > 0) httpCode.toString() else "-")
        append(" delay=").append(
            if (delayMs == Long.MAX_VALUE) ">timeout" else "${delayMs}ms",
        )
    }
}

/**
 * 阿里云 DoH 应答解析（纯函数，便于脱离 Android 单测）。
 *
 * 用途：章节接口 / Trello 运行时学到的图源不在 ruledns 的 IP 组里，会退回 `Dns.SYSTEM`，
 * 因此拿不到 IP 分数、也不参与按机器测速。测活时把这类域名实际解析到的 v4 地址记进日志，
 * 才能一步补进配置的 `rule`。
 */
object DohResolver {

    private const val TYPE_A = 1

    private val json = Json { ignoreUnknownKeys = true }

    /** 只取 A 记录的 data，按应答顺序返回；非法/非 v4 一律跳过，解析失败返回空表。 */
    fun parseARecords(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val answers = root["Answer"]?.jsonArray ?: return emptyList()
            answers.mapNotNull { item ->
                val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
                val type = obj["type"]?.jsonPrimitive?.intOrNull
                if (type != TYPE_A) return@mapNotNull null
                val data = obj["data"]?.jsonPrimitive?.content?.trim().orEmpty()
                data.takeIf { isIpv4(it) }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    // IPv4 判定复用 IpProbePlanner，避免两处各写一套宽松度不同的解析
    private fun isIpv4(value: String): Boolean = IpProbePlanner.isIpv4(value)
}
