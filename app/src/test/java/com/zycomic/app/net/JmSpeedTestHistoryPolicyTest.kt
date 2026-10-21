package com.zycomic.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JmSpeedTestHistoryPolicyTest {
    private fun identity(
        domain: String,
        ip: String,
        request: String = "https://$domain/api",
        route: String = "configured-rules",
        probe: String = "valid-api-json",
        phase: String = JmSpeedTestHistoryPolicy.API_PHASE,
    ) = JmSpeedTestHistoryPolicy.Identity(phase, request, domain, ip, route, probe)

    private fun measurement(
        identity: JmSpeedTestHistoryPolicy.Identity,
        delay: Long,
        measuredAt: Long,
        verdict: JmSpeedTester.ImgVerdict? = null,
    ) = JmSpeedTestHistoryPolicy.Measurement(identity, delay, verdict, measuredAt)

    @Test
    fun reconciliationRequiresAnExplicitStrictlyNewerVersion() {
        assertFalse(JmSpeedTestHistoryPolicy.shouldReconcile(false, 4, 5))
        assertFalse(JmSpeedTestHistoryPolicy.shouldReconcile(true, null, 5))
        assertFalse(JmSpeedTestHistoryPolicy.shouldReconcile(true, 5, null))
        assertFalse(JmSpeedTestHistoryPolicy.shouldReconcile(true, 5, 5))
        assertFalse(JmSpeedTestHistoryPolicy.shouldReconcile(true, 6, 5))
        assertTrue(JmSpeedTestHistoryPolicy.shouldReconcile(true, 4, 5))
    }

    @Test
    fun reconciliationRetainsExactMeasurementsAndTimestampsProbesAdditionsAndPrunesRemovals() {
        val retained = identity("api.example", "192.0.2.1")
        val removed = identity("old.example", "192.0.2.2")
        val added = identity("api.example", "192.0.2.3")
        val oldRetained = measurement(retained, 41, 1_000)
        val oldRemoved = measurement(removed, 17, 900)
        val newlyMeasured = measurement(added, 12, 2_000)
        val result = JmSpeedTestHistoryPolicy.reconcile(
            previous = JmSpeedTestHistoryPolicy.PhaseHistory(4, listOf(oldRetained, oldRemoved)),
            activeVersion = 5,
            activeIdentities = setOf(retained, added),
            fresh = listOf(newlyMeasured),
        )

        assertEquals(5, result.remoteVersion)
        assertEquals(listOf(newlyMeasured, oldRetained), result.measurements)
        assertEquals(1_000L, result.measurements.single { it.identity == retained }.measuredAtEpochMs)
        assertEquals(setOf(retained, added), result.measurements.map { it.identity }.toSet())
    }

    @Test
    fun eachProbeConditionIsPartOfEndpointIdentityButOtherIpsDoNotChangeIdentity() {
        val original = identity("api.example", "192.0.2.1")
        val otherIp = identity("api.example", "192.0.2.2")
        val changedRoute = identity("api.example", "192.0.2.1", route = "probe-dns")
        val changedRequest = identity("api.example", "192.0.2.1", request = "http://api.example/api")
        val changedProbe = identity("api.example", "192.0.2.1", probe = "different-probe")
        assertEquals(original, identity("api.example", "192.0.2.1"))
        assertFalse(original == otherIp)
        assertFalse(original == changedRoute)
        assertFalse(original == changedRequest)
        assertFalse(original == changedProbe)
    }

    @Test
    fun sameVersionQuickMergeReplacesFreshRowsAndKeepsUntestedHistoryWithoutCompatibilityFiltering() {
        val unchanged = identity("old-pool.example", "192.0.2.1")
        val refreshed = identity("new-pool.example", "192.0.2.2")
        val oldUnchanged = measurement(unchanged, 30, 1_000)
        val oldRefreshed = measurement(refreshed, 50, 1_100)
        val fresh = measurement(refreshed, 8, 2_000)
        val result = JmSpeedTestHistoryPolicy.mergeQuick(
            JmSpeedTestHistoryPolicy.PhaseHistory(5, listOf(oldUnchanged, oldRefreshed)),
            activeVersion = 5,
            fresh = listOf(fresh),
        )
        assertEquals(listOf(fresh, oldUnchanged), result.measurements)
        assertEquals(1_000L, result.measurements.single { it.identity == unchanged }.measuredAtEpochMs)
    }

    @Test
    fun fullNonReuseReplacementDoesNotRetainOldRows() {
        val old = measurement(identity("old.example", "192.0.2.1"), 1, 1_000)
        val fresh = measurement(identity("new.example", "192.0.2.2"), 20, 2_000)
        assertEquals(
            listOf(fresh),
            JmSpeedTestHistoryPolicy.freshOnly(6, listOf(fresh)).measurements,
        )
        assertEquals(5, JmSpeedTestHistoryPolicy.RETEST_LIMIT)
        assertEquals(old.identity.domain, "old.example")
    }
}
