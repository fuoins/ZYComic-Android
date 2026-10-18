package com.zycomic.app.net

/** The selected candidates are deliberately separate from the fastest measured candidates. */
data class PlatformSpeedTestReport(
    val platform: String,
    val status: String,
    val reason: String,
    val selectedLine: String,
    val selectedImage: String,
    val manualLine: Boolean,
    val manualImage: Boolean,
    val lineCandidates: List<String>,
    val imageCandidates: List<String>,
    val lineDelays: Map<String, Long> = emptyMap(),
    val imageDelays: Map<String, Long> = emptyMap(),
    val remoteVersion: Int? = null,
    val lineStatus: String = status,
    val imageStatus: String = status,
) {
    fun summary(): String = if (status == "skipped") {
        "$platform：跳过（$reason）"
    } else {
        "$platform：${when (status) {
            "reused" -> "复用上次有效结果"
            "tested" -> "本次新测"
            else -> "测速未完成"
        }}${when (imageStatus) {
            "mixed" -> "（图源含匹配的旧结果）"
            "reused" -> "（图源复用上次结果）"
            else -> ""
        }}；线路 ${selectedLine.ifBlank { "暂无" }}（${if (manualLine) "手动" else "自动"}）；图源 ${selectedImage.ifBlank { "暂无" }}（${if (manualImage) "手动" else "自动"}）；$reason"
    }
}
