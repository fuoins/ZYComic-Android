package com.zycomic.app.net

/** User-facing speed-test labels; diagnostic screens retain endpoint domain and IP. */
internal object SpeedTestUiTextPolicy {
    fun endpointLabel(domain: String, ip: String): String = "$domain · $ip"

    fun platformEndpointProgress(
        platform: String,
        endpointKind: String,
        completed: Int,
        total: Int,
        successful: Int,
        failed: Int,
        noImage: Int? = null,
    ): String = buildString {
        append(platform).append(" · 测试").append(endpointKind).append(" IP ")
            .append(completed).append('/').append(total).append("（可用 ").append(successful)
        if (noImage == null) {
            append("，失败 ").append(failed)
        } else {
            append("，连不上 ").append(failed).append("，无图 ").append(noImage)
        }
        append('）')
    }

    fun startupNoAddressToast(hasLineAddress: Boolean): String? =
        if (hasLineAddress) null else "启动自动选线未解析到线路地址，已保留当前线路"

    fun imageSourceDescription(kind: ImageSourceDisplayPolicy.Kind, inSuccessfulList: Boolean): String = buildString {
        append(kind.label).append("：当前选择")
        if (!inSuccessfulList) append("（当前项不在测速成功列表中，不代表测速成功）")
    }
}
