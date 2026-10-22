package com.zycomic.app.reader

import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.net.PicacgRouteManager
import com.zycomic.app.net.RouteManager

/** Source/config-specific reader retry state; Picacg never inherits Manwa route state. */
enum class ReaderRetryPlatform {
    MANWA,
    JM,
    PICACG,
    OTHER,
}

internal object ReaderPlatformRetryPolicy {
    fun platform(sourceId: Long): ReaderRetryPlatform = when (sourceId) {
        ManwaSource.id -> ReaderRetryPlatform.MANWA
        JmSource.id -> ReaderRetryPlatform.JM
        PicacgSource.id -> ReaderRetryPlatform.PICACG
        else -> ReaderRetryPlatform.OTHER
    }

    fun currentBudgetKey(sourceId: Long): String {
        val platform = platform(sourceId)
        val configuration = when (platform) {
            ReaderRetryPlatform.JM -> "${JmRouteManager.currentSpeedTestConfigRevision()}:${JmRouteManager.activeImageDomain}:${JmRouteManager.currentImageEndpoint()?.ip.orEmpty()}"
            ReaderRetryPlatform.PICACG -> PicacgRouteManager.state.value.let { state ->
                "${PicacgRouteManager.configRevision}:${state.revision}:${PicacgRouteManager.apiBaseUrl}:${state.imageHost.orEmpty()}"
            }
            ReaderRetryPlatform.MANWA -> "${RouteManager.configRevision}:${RouteManager.imgPinDisplayRevision}:${RouteManager.imgRewriteTarget ?: RouteManager.imgHost}:${RouteManager.selectedImgIp.orEmpty()}"
            ReaderRetryPlatform.OTHER -> sourceId.toString()
        }
        return budgetKey(platform, configuration)
    }

    fun budgetKey(platform: ReaderRetryPlatform, configuration: String): String =
        "${platform.name.lowercase()}:$configuration"

    fun supportsEndpointFallback(platform: ReaderRetryPlatform): Boolean =
        platform == ReaderRetryPlatform.MANWA || platform == ReaderRetryPlatform.JM

    /** Avoid retrying auth/not-found/rate-limit responses; only server failures may use bounded fallback. */
    fun shouldAutomaticallyRetryHttpFailure(statusCode: Int?): Boolean =
        statusCode != null && statusCode in 500..599

    fun manualRetryLimitReached(
        retryCount: Int,
        retryBudgetSource: String?,
        currentBudgetKey: String,
        limit: Int = 3,
    ): Boolean = retryCount >= limit && retryBudgetSource == currentBudgetKey

    /** Runtime recovery is scoped to one still-visible failed page and its exact source/config epoch. */
    fun mayRestoreRuntimeFailure(
        platform: ReaderRetryPlatform,
        currentBudgetKey: String,
        expectedBudgetKey: String,
        currentGeneration: Int,
        expectedGeneration: Int,
        isVisible: Boolean,
        isFailed: Boolean,
        isRecycled: Boolean,
        schedulingEnabled: Boolean,
    ): Boolean = currentBudgetKey.startsWith("${platform.name.lowercase()}:") && currentBudgetKey == expectedBudgetKey &&
        currentGeneration == expectedGeneration &&
        isVisible && isFailed && !isRecycled && schedulingEnabled
}
