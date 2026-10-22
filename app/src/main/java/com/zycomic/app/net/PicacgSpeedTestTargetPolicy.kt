package com.zycomic.app.net

import java.net.URI

/** Pure target expansion shared by the speed tester and offline identity/coverage regressions. */
internal object PicacgSpeedTestTargetPolicy {
    data class ApiTarget(val url: String, val endpoint: DomainIp)

    /** `content == false` is the imgproxy cover service; `true` is the storage-b reader-page service. */
    data class ImageTarget(val url: String, val endpoint: DomainIp, val content: Boolean)

    data class ImageOutcome(val endpoint: DomainIp, val content: Boolean, val success: Boolean, val elapsedMs: Long)

    fun apiTargets(apiUrls: List<String>, resolve: (String) -> List<String>): List<ApiTarget> =
        apiUrls.flatMap { baseUrl ->
            val host = runCatching { URI(baseUrl).host }.getOrNull()
            if (host == null) emptyList() else resolve(host).distinct().map { ip -> ApiTarget(baseUrl, DomainIp(host, ip)) }
        }

    fun imageTargets(contentHosts: List<String>, resolve: (String) -> List<String>): List<ImageTarget> =
        contentHosts.flatMap { contentHost ->
            val coverHost = PicacgBuiltinNetwork.coverHostFor(contentHost) ?: return@flatMap emptyList()
            val coverUrl = PicacgBuiltinNetwork.speedTileUrl(coverHost)
            val contentUrl = PicacgBuiltinNetwork.speedTileContentUrl(contentHost)
            resolve(contentHost).distinct().flatMap { ip ->
                val endpoint = DomainIp(contentHost, ip)
                listOf(
                    ImageTarget(coverUrl, endpoint, content = false),
                    ImageTarget(contentUrl, endpoint, content = true),
                )
            }
        }

    /** Rank the best successful IP per root independently for the cover and body services. */
    fun ranking(outcomes: List<ImageOutcome>, content: Boolean): List<DomainIp> =
        outcomes.asSequence()
            .filter { it.success && it.content == content }
            .groupBy { it.endpoint.domain.lowercase() }
            .map { (_, probes) -> probes.minBy { it.elapsedMs } }
            .sortedBy { it.elapsedMs }
            .map { it.endpoint }
}
