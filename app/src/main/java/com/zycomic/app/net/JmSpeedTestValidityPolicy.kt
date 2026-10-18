package com.zycomic.app.net

/** Pure equality gate shared by runtime reuse and JVM tests. */
internal object JmSpeedTestValidityPolicy {
    fun canReuseImages(
        snapshotFingerprint: String?,
        currentFingerprint: String,
        snapshotNetworkPolicy: String?,
        currentNetworkPolicy: String,
        imageDelays: Map<String, Long>,
        currentImages: Set<String>,
        startedAtEpochMs: Long?,
        completedAtEpochMs: Long?,
    ): Boolean = snapshotFingerprint == currentFingerprint &&
        snapshotNetworkPolicy == currentNetworkPolicy &&
        currentImages.isNotEmpty() && imageDelays.keys == currentImages &&
        imageDelays.values.all { it >= 0L } && startedAtEpochMs != null &&
        completedAtEpochMs != null && completedAtEpochMs >= startedAtEpochMs

    fun isValid(
        snapshotVersion: Int?,
        currentVersion: Int?,
        snapshotFingerprint: String?,
        currentFingerprint: String,
        snapshotLineCandidates: Set<String>,
        currentLineCandidates: Set<String>,
        snapshotImageCandidates: Set<String>,
        currentImageCandidates: Set<String>,
        lineDelays: Map<String, Long>,
        imageDelays: Map<String, Long>,
        startedAtEpochMs: Long?,
        completedAtEpochMs: Long?,
    ): Boolean = snapshotFingerprint != null &&
        snapshotFingerprint == currentFingerprint &&
        snapshotVersion == currentVersion &&
        startedAtEpochMs != null && completedAtEpochMs != null &&
        completedAtEpochMs >= startedAtEpochMs &&
        snapshotLineCandidates == currentLineCandidates &&
        snapshotImageCandidates == currentImageCandidates &&
        lineDelays.keys == currentLineCandidates &&
        imageDelays.keys == currentImageCandidates &&
        lineDelays.values.all { it >= 0L } && imageDelays.values.all { it >= 0L }
}
