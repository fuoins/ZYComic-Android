package com.zycomic.app.net

/** A categorized probe failure; raw exception messages are deliberately never retained. */
enum class SpeedTestFailureCategory {
    HTTP_STATUS,
    INVALID_JSON,
    INVALID_IMAGE,
    TIMEOUT,
    DNS,
    TLS,
    CONNECTION,
    IO,
    OTHER,
}

/** Safe metadata for one request attempt. No URL, headers, response body, or exception text is stored. */
data class SpeedTestProbeAttempt(
    val probe: String,
    val success: Boolean,
    val elapsedMs: Long,
    val httpStatus: Int?,
    val contentType: String?,
    val failureCategory: SpeedTestFailureCategory?,
)

/** Safe per-endpoint result retained only for a completed, full speed-test pass. */
data class SpeedTestEndpointDiagnostic(
    val success: Boolean,
    val latencyMs: Long?,
    val elapsedMs: Long,
    val failureCategory: SpeedTestFailureCategory?,
    val imageVerdict: String? = null,
    val fallbackAttempted: Boolean = false,
    val attempts: List<SpeedTestProbeAttempt> = emptyList(),
)

/** The effective transport decision captured when the test clients are created. */
data class SpeedTestNetworkPath(
    val followSystemVpnEnabled: Boolean,
    val systemVpnActive: Boolean,
    val directStrategy: Boolean,
)
