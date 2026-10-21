package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform

/** Six fixed startup checks, kept separate from the per-platform live progress below each switch. */
enum class StartupCheckPhase(val label: String) {
    MANWA_LINE("漫蛙线路"),
    MANWA_IMAGE("漫蛙图域名"),
    PICACG_LINE("哔咔线路"),
    PICACG_IMAGE("哔咔图域名"),
    JM_LINE("禁漫线路"),
    JM_IMAGE("禁漫图域名"),
}

enum class StartupCheckResult(val label: String) {
    NOT_TESTED("未检测"),
    RUNNING("检测中"),
    SUCCESS("通过"),
    FAILED("失败"),
    DISABLED("已禁用"),
    BLOCKED("受阻"),
    NO_SAMPLE("缺样本"),
    NOT_RUN("未检测"),
    INTERRUPTED("中断"),
}

data class StartupCheckStatus(
    val phase: StartupCheckPhase,
    val result: StartupCheckResult = StartupCheckResult.NOT_TESTED,
    val detail: String? = null,
)

fun initialStartupCheckStatuses(): List<StartupCheckStatus> = StartupCheckPhase.entries.map { StartupCheckStatus(it) }

fun updateStartupCheckStatus(
    current: List<StartupCheckStatus>,
    phase: StartupCheckPhase,
    result: StartupCheckResult,
    detail: String? = null,
): List<StartupCheckStatus> = current.map {
    if (it.phase == phase) it.copy(result = result, detail = detail) else it
}

fun startupChecksPassed(statuses: List<StartupCheckStatus>): Int =
    statuses.distinctBy { it.phase }.count { it.result == StartupCheckResult.SUCCESS }

/** Fixed six-stage denominator; failures, skipped work and history never advance it. */
fun startupChecksProgress(statuses: List<StartupCheckStatus>): Float =
    startupChecksPassed(statuses).toFloat() / StartupCheckPhase.entries.size

/** Progress shown beneath one platform's startup switch; never shared across concurrent tests. */
data class PlatformStartupStatus(
    val message: String,
    val fraction: Float? = null,
    val running: Boolean = false,
)

internal fun platformStartupProgressFraction(completed: Int, total: Int): Float? =
    if (total <= 0) null else (completed.toFloat() / total).coerceIn(0f, 1f)

internal fun updatePlatformStartupStatus(
    current: Map<ContentPlatform, PlatformStartupStatus>,
    platform: ContentPlatform,
    status: PlatformStartupStatus,
): Map<ContentPlatform, PlatformStartupStatus> = current + (platform to status)
