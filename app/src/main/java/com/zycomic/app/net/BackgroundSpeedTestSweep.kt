package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy

/**
 * Tracks whether the lightweight startup selection still has unmeasured endpoints and supplies the
 * bounded chunk size used by the low-priority background completion pass.
 *
 * The pass always reuses the per-scope, per-version histories that the platform testers already
 * persist. It never probes an endpoint that already has a measurement for the current generation.
 */
internal object BackgroundSpeedTestSweep {
    /**
     * Endpoints dispatched per platform phase in one background round. Each round commits its own
     * snapshot/history, so killing the process loses at most this many in-flight measurements.
     */
    const val CHUNK_SIZE = 12

    fun pendingPlatforms(): Set<ContentPlatform> = buildSet {
        if (StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) && !RouteManager.hasCompletedSpeedTestSnapshot()) {
            add(ContentPlatform.MANWA)
        }
        if (StartupPlatformPolicy.isEnabled(ContentPlatform.PICACG) && PicacgSpeedTester.hasPendingBackgroundWork()) {
            add(ContentPlatform.PICACG)
        }
        if (StartupPlatformPolicy.isEnabled(ContentPlatform.JM) && jmPending()) {
            add(ContentPlatform.JM)
        }
    }

    /** Monotonic-ish count of persisted endpoint results; used to detect a round that made no progress. */
    fun progressSignature(): String {
        val manwa = if (StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA)) {
            val history = RouteManager.speedTestHistoryForVersion(RouteManager.activeRemoteConfigVersion)
            (history?.lineDelays?.size ?: 0) + (history?.imgDelays?.size ?: 0)
        } else {
            0
        }
        val picacg = if (StartupPlatformPolicy.isEnabled(ContentPlatform.PICACG)) {
            PicacgSpeedTestHistoryStorage.loadApi().measurements.size +
                PicacgSpeedTestHistoryStorage.loadImages().measurements.size
        } else {
            0
        }
        val jm = if (StartupPlatformPolicy.isEnabled(ContentPlatform.JM)) {
            val history = JmRouteManager.speedTestSnapshot.value
            history.lineIpDelaysMs.size + history.imageIpDelaysMs.size +
                history.untestedLineIpDelaysMs.size + history.untestedImageIpDelaysMs.size
        } else {
            0
        }
        return "manwa=$manwa|picacg=$picacg|jm=$jm"
    }

    private fun jmPending(): Boolean {
        val snapshot = JmRouteManager.speedTestSnapshot.value
        return !(snapshot.lineDrainedAll && snapshot.imageDrainedAll && JmRouteManager.isSpeedTestSnapshotValid(snapshot))
    }
}
