package com.zycomic.app.net

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class PicacgSpeedTestHistoryPolicyTest {
    private fun identity(
        phase: String = PicacgSpeedTestHistoryPolicy.API_PHASE,
        host: String = "api.example",
        ip: String = "192.0.2.1",
        url: String = "https://api.example/v1",
    ) = PicacgSpeedTestHistoryPolicy.Identity(
        platform = PicacgSpeedTestHistoryPolicy.PLATFORM,
        phase = phase,
        url = url,
        domain = host,
        ip = ip,
        routeCondition = if (phase == PicacgSpeedTestHistoryPolicy.API_PHASE) {
            "${PicacgSpeedTestHistoryPolicy.API_ROUTE_CONDITION};ip-source=configured-rules"
        } else {
            "${PicacgSpeedTestHistoryPolicy.IMAGE_ROUTE_CONDITION};ip-source=configured-rules"
        },
        probeCondition = if (phase == PicacgSpeedTestHistoryPolicy.API_PHASE) {
            "GET-root;redirects-disabled;accept-non-redirect-http-status"
        } else {
            "GET-imgproxy-tile;redirects-disabled;status-200;image-content-type;image-magic"
        },
    )

    private fun measurement(
        identity: PicacgSpeedTestHistoryPolicy.Identity,
        delay: Long,
        measuredAt: Long = 100L,
        success: Boolean = true,
    ) = PicacgSpeedTestHistoryPolicy.Measurement(identity, success, delay, measuredAt)

    @Test
    fun reconciliationRequiresAnExplicitlyNewerVersion() {
        assertTrue(PicacgSpeedTestHistoryPolicy.shouldReconcile(true, 4, 5))
        assertFalse(PicacgSpeedTestHistoryPolicy.shouldReconcile(false, 4, 5))
        assertFalse(PicacgSpeedTestHistoryPolicy.shouldReconcile(true, 5, 5))
        assertFalse(PicacgSpeedTestHistoryPolicy.shouldReconcile(true, 6, 5))
        assertFalse(PicacgSpeedTestHistoryPolicy.shouldReconcile(true, null, 5))
        assertTrue(PicacgSpeedTestHistoryPolicy.canUseAtVersion(5, 5))
    }

    @Test
    fun identitiesIncludePlatformPhaseExactUrlDomainIpAndRouteProbeConditions() {
        val original = identity()
        assertFalse(original == identity(host = "other.example", url = "https://other.example/v1"))
        assertFalse(original == identity(ip = "192.0.2.2"))
        assertFalse(original == identity(url = "https://api.example/v2"))
        assertFalse(original == identity(phase = PicacgSpeedTestHistoryPolicy.IMAGE_PHASE))
        assertFalse(original == original.copy(routeCondition = original.routeCondition + ";changed"))
        assertFalse(original == original.copy(probeCondition = original.probeCondition + ";changed"))
        assertFalse(original == original.copy(platform = "other"))
        assertEquals(PicacgSpeedTestHistoryPolicy.PLATFORM, original.platform)
    }

    @Test
    fun newerConfigKeepsExactUnchangedEntriesTestsOnlyAdditionsAndDropsRemovedEntries() {
        val retained = identity(host = "keep.example", url = "https://keep.example/v1")
        val removed = identity(host = "removed.example", url = "https://removed.example/v1")
        val added = identity(host = "added.example", url = "https://added.example/v2")
        val prior = PicacgSpeedTestHistoryPolicy.PhaseHistory(8, listOf(measurement(retained, 12L, 80L), measurement(removed, 2L, 90L)))
        val merged = PicacgSpeedTestHistoryPolicy.reconcile(prior, 9, setOf(retained, added), listOf(measurement(added, 7L, 110L)))

        assertEquals(9, merged.remoteVersion)
        assertEquals(listOf(added, retained), merged.measurements.map { it.identity })
        assertEquals(80L, merged.measurements.single { it.identity == retained }.measuredAtEpochMs)
        assertEquals(110L, merged.measurements.single { it.identity == added }.measuredAtEpochMs)
    }

    @Test
    fun sameVersionQuickMergeKeepsUnselectedRowsWithOriginalMeasurementTimesAndSortsAllRows() {
        val entries = (1..7).map { number ->
            identity(host = "api$number.example", ip = "192.0.2.$number", url = "https://api$number.example/v1")
        }
        val prior = PicacgSpeedTestHistoryPolicy.PhaseHistory(3, entries.mapIndexed { i, key -> measurement(key, (i + 1L) * 10L, 1_000L + i) })
        val selected = PicacgSpeedTestHistoryPolicy.selectRankedTop(prior)
        assertEquals(entries.take(5), selected.map { it.identity })

        val fresh = selected.mapIndexed { i, old -> measurement(old.identity, (5 - i).toLong(), 2_000L + i) }
        val merged = PicacgSpeedTestHistoryPolicy.mergeQuick(prior, selected.mapTo(linkedSetOf()) { it.identity }, fresh)

        assertEquals(listOf(entries[4], entries[3], entries[2], entries[1], entries[0]), merged.measurements.take(5).map { it.identity })
        entries.drop(5).forEach { untouched ->
            assertEquals(1_000L + entries.indexOf(untouched), merged.measurements.single { it.identity == untouched }.measuredAtEpochMs)
        }
        assertEquals(2_004L, merged.measurements.first().measuredAtEpochMs)
    }

    @Test
    fun persistedHistoryRoundTripsThroughFakeContextIncludingIpv6() {
        val valuesByFile = mutableMapOf<String, Any?>()
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> valuesByFile[args!![0] as String] as? String ?: args[1] as String?
                "edit" -> Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { editor, editorMethod, editorArgs ->
                    when (editorMethod.name) {
                        "putString" -> {
                            valuesByFile[editorArgs!![0] as String] = editorArgs[1]
                            editor
                        }
                        "apply" -> null
                        "commit" -> true
                        else -> null
                    }
                }
                else -> null
            }
        } as SharedPreferences
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        val devConfigContext = DevConfig::class.java.getDeclaredField("context").apply { isAccessible = true }
        val previousContext = devConfigContext.get(DevConfig)
        try {
            devConfigContext.set(DevConfig, context)
            val apiIdentity = identity(ip = "2001:db8::2")
            val apiHistory = PicacgSpeedTestHistoryPolicy.PhaseHistory(12, listOf(measurement(apiIdentity, 14L, 1_234L)))
            val imageIdentity = identity(
                phase = PicacgSpeedTestHistoryPolicy.IMAGE_PHASE,
                host = "img.example",
                ip = "192.0.2.5",
                url = "https://img.example/sample.jpg",
            )
            val imageHistory = PicacgSpeedTestHistoryPolicy.PhaseHistory(12, listOf(measurement(imageIdentity, 23L, 5_678L)))

            PicacgSpeedTestHistoryStorage.saveApi(apiHistory)
            PicacgSpeedTestHistoryStorage.saveImages(imageHistory)

            assertEquals(apiHistory, PicacgSpeedTestHistoryStorage.loadApi())
            assertEquals(imageHistory, PicacgSpeedTestHistoryStorage.loadImages())
        } finally {
            devConfigContext.set(DevConfig, previousContext)
        }
    }

    @Test
    fun quickSelectionUsesRankedTopFiveIncludingFailuresWithoutComparingCurrentPools() {
        val records = (1..7).map { number ->
            val key = identity(host = "api$number.example", ip = "192.0.2.$number", url = "https://api$number.example/v1")
            measurement(key, number * 10L, success = number in setOf(1, 3, 5))
        }
        val history = PicacgSpeedTestHistoryPolicy.PhaseHistory(1, PicacgSpeedTestHistoryPolicy.sorted(records))

        assertEquals(listOf(1, 3, 5, 2, 4), PicacgSpeedTestHistoryPolicy.selectRankedTop(history).map { it.endpoint.ip.substringAfterLast('.').toInt() })
    }

    @Test
    fun quickSelectionDoesNotExpandPastFivePreviouslyFailedRows() {
        val failures = (1..7).map { number ->
            val key = identity(host = "api$number.example", ip = "192.0.2.$number", url = "https://api$number.example/v1")
            measurement(key, Long.MAX_VALUE, success = false)
        }
        val history = PicacgSpeedTestHistoryPolicy.PhaseHistory(1, failures)

        assertEquals(failures.take(5), PicacgSpeedTestHistoryPolicy.selectRankedTop(history))
        assertEquals(5, PicacgSpeedTestHistoryPolicy.selectRankedTop(history).size)
    }

    @Test
    fun fullReplacementDoesNotAppendOldRows() {
        val old = measurement(identity(), 1L)
        val replacement = measurement(identity(host = "new.example", url = "https://new.example/v2"), 9L)
        val replaced = PicacgSpeedTestHistoryPolicy.freshOnly(4, listOf(replacement))
        assertEquals(4, replaced.remoteVersion)
        assertEquals(listOf(replacement), replaced.measurements)
        assertFalse(old in replaced.measurements)
    }
}
