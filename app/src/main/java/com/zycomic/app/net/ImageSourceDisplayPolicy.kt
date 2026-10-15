package com.zycomic.app.net

/** Pure display-state calculation for the image source currently used by chapter images. */
object ImageSourceDisplayPolicy {
    enum class Kind(val label: String) {
        AUTOMATIC_FASTEST("自动·测速最快"),
        SERVER_RECOMMENDED("当前·服务端推荐"),
        MANUAL_PIN("手动固定"),
    }

    data class CurrentSource(
        val kind: Kind,
        val domain: String?,
        /** Null means the active source is selected by domain and no exact IP is known. */
        val ip: String?,
        /** Only populated when this source can be matched to a successful measured endpoint. */
        val successfulEndpoint: DomainIp?,
    ) {
        val hasSuccessfulEndpoint: Boolean
            get() = successfulEndpoint != null

        /** Match only an endpoint whose domain and IP are both known to be the current endpoint. */
        fun matchesEndpoint(endpoint: DomainIp): Boolean {
            if (domain.isNullOrBlank() || ip.isNullOrBlank()) return false
            return endpoint.domain.equals(domain, ignoreCase = true) && endpoint.ip == ip
        }

        fun description(): String {
            val source = domain?.let { host -> ip?.let { "$host · $it" } ?: "$host（IP未固定）" }
                ?: "暂未获取到图源域名"
            return "${kind.label}：$source"
        }

        fun notInSuccessfulListDescription(): String =
            "${description()}（当前项不在测速成功列表中，不代表测速成功）"
    }

    /**
     * Mirrors [ImgDomainPolicy] priority without changing request behavior:
     * a valid manual pin wins, then the fastest successful endpoint when enabled,
     * otherwise the latest server-recommended domain (or the chapter's first domain fallback).
     */
    fun currentSource(
        configuredDomains: List<String>,
        successfulEndpoints: List<DomainIp>,
        pinnedDomain: String?,
        pinnedIp: String?,
        useFastestImgForAll: Boolean,
        serverRecommendedDomain: String?,
        serverFallbackDomain: String? = null,
    ): CurrentSource {
        val configuredSuccessfulEndpoints = successfulEndpoints.filter { endpoint ->
            configuredDomains.any { it.equals(endpoint.domain, ignoreCase = true) }
        }
        val fastest = configuredSuccessfulEndpoints.firstOrNull()
        val validPin = pinnedDomain?.trim()?.takeIf { pin ->
            pin.isNotEmpty() && configuredDomains.any { it.equals(pin, ignoreCase = true) }
        }
        val rewriteTarget = ImgDomainPolicy.rewriteTarget(
            pinned = pinnedDomain,
            imgDomains = configuredDomains,
            useFastestImgForAll = useFastestImgForAll,
            derivedFastest = fastest?.domain,
        )

        val kind: Kind
        val domain: String?
        val ip: String?
        when {
            validPin != null && rewriteTarget.equals(validPin, ignoreCase = true) -> {
                kind = Kind.MANUAL_PIN
                domain = configuredDomains.first { it.equals(validPin, ignoreCase = true) }
                ip = pinnedIp?.takeIf { it.isNotBlank() }
            }
            useFastestImgForAll && fastest != null && rewriteTarget.equals(fastest.domain, ignoreCase = true) -> {
                kind = Kind.AUTOMATIC_FASTEST
                domain = fastest.domain
                ip = fastest.ip
            }
            else -> {
                kind = Kind.SERVER_RECOMMENDED
                domain = sequenceOf(serverRecommendedDomain, serverFallbackDomain)
                    .mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() } }
                    .firstOrNull()
                ip = null
            }
        }

        val matchingSuccess = domain?.takeIf { ip != null }?.let { currentDomain ->
            configuredSuccessfulEndpoints.firstOrNull { endpoint ->
                endpoint.domain.equals(currentDomain, ignoreCase = true) && endpoint.ip == ip
            }
        }
        return CurrentSource(kind, domain, ip, matchingSuccess)
    }
}
