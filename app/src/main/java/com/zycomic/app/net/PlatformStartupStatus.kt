package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform

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
