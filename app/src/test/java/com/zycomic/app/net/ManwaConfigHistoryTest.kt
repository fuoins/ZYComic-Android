package com.zycomic.app.net

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class ManwaConfigHistoryTest {
    private val context = TestContext()
    private val prefs get() = context.getSharedPreferences("zycomic_route", Context.MODE_PRIVATE)

    private val oldLines = listOf("https://api.example")
    private val oldImages = listOf("img.example")
    private val oldRules = mapOf(
        "api.example" to listOf("203.0.113.10", "203.0.113.12"),
        "img.example" to listOf("198.51.100.20", "198.51.100.22"),
    )
    private val retainedLine = DomainIp("api.example", "203.0.113.10")
    private val deletedLine = DomainIp("api.example", "203.0.113.12")
    private val addedLine = DomainIp("api.example", "203.0.113.11")
    private val retainedImage = DomainIp("img.example", "198.51.100.20")
    private val deletedImage = DomainIp("img.example", "198.51.100.22")
    private val addedImage = DomainIp("img.example", "198.51.100.21")

    private val oldLineIdentities = mapOf(retainedLine to "line-stable", deletedLine to "line-deleted")
    private val oldImageIdentities = mapOf(retainedImage to "image-stable", deletedImage to "image-deleted")

    @BeforeEach
    fun resetState() {
        DevConfig.appContext = context
        prefs.edit().clear().commit()
        RouteManager.clearConfiguration()
        RouteManager.setAutoSelectEnabled(true)
        RouteManager.updateUseFastestImgForAll(true)
        RouteManager.clearPinnedImgDomain()
        RouteManager.loadAutoSelectPrefs()
        StartupPlatformPolicy.load(context)
        StartupPlatformPolicy.setManwaEnabled(true)
        assertTrue(StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA))
    }

    @Test
    fun `strictly newer reconciliation is read only until guarded apply and equal version declines`() {
        preparePriorSnapshot()
        applyConfig(version = 8)
        val rawBefore = historyJson()
        val expectedRevision = RouteManager.configRevision
        val platformRevision = StartupPlatformPolicy.state.value.revision

        val prepared = RouteManager.reconcileSpeedTestHistory(
            version = 8,
            lineIdentities = oldLineIdentities,
            imageIdentities = oldImageIdentities,
            expectedConfigRevision = expectedRevision,
            expectedPlatformRevision = platformRevision,
        )

        assertNotNull(prepared)
        assertEquals(7, RouteManager.latestSpeedTestHistory()?.remoteVersion)
        assertEquals(rawBefore, historyJson(), "preparing a newer-version reuse must not write/publish history")
        assertNull(
            RouteManager.reconcileSpeedTestHistory(
                version = 7,
                lineIdentities = oldLineIdentities,
                imageIdentities = oldImageIdentities,
                expectedConfigRevision = expectedRevision,
                expectedPlatformRevision = platformRevision,
            ),
            "same-version config is not a migration/reconciliation",
        )
        assertEquals(rawBefore, historyJson())
    }

    @Test
    fun `stable endpoint identity survives added IP while removed IP history is pruned`() {
        preparePriorSnapshot()
        applyConfig(version = 8)
        val currentLineIdentities = mapOf(
            retainedLine to "line-stable",
            addedLine to "line-new",
        )
        val currentImageIdentities = mapOf(
            retainedImage to "image-stable",
            addedImage to "image-new",
        )

        val reconciled = reconcile(8, currentLineIdentities, currentImageIdentities)

        assertEquals(mapOf(retainedLine to 80L), reconciled.lineDelays)
        assertEquals(mapOf(retainedImage to 50L), reconciled.imgDelays)
        assertEquals(mapOf(retainedLine to 2_000L), reconciled.lineMeasuredAtEpochMs)
        assertEquals(mapOf(retainedImage to 2_000L), reconciled.imageMeasuredAtEpochMs)
        assertFalse(deletedLine in reconciled.lineDelays)
        assertFalse(deletedImage in reconciled.imgDelays)
        assertFalse(addedLine in reconciled.lineDelays, "a newly added IP has no old measurement to reuse")
        assertFalse(addedImage in reconciled.imgDelays)
    }

    @Test
    fun `guarded full apply persists original measuredAt and replaces old version rows`() {
        preparePriorSnapshot()
        applyConfig(version = 8)
        val currentLineIdentities = mapOf(retainedLine to "line-stable", addedLine to "line-new")
        val currentImageIdentities = mapOf(retainedImage to "image-stable", addedImage to "image-new")
        val prepared = reconcile(8, currentLineIdentities, currentImageIdentities)
        val lineResults = linkedMapOf(retainedLine to 80L, addedLine to 18L)
        val imageResults = linkedMapOf(retainedImage to 50L, addedImage to 15L)
        val lineDiagnostics = lineResults.mapValues { (_, delay) -> diagnostic(delay, image = false) }
        val imageDiagnostics = imageResults.mapValues { (_, delay) -> diagnostic(delay, image = true) }

        assertTrue(
            RouteManager.setEndpointDelays(
                line = lineResults,
                img = imageResults,
                imgVerdicts = imageResults.keys.associateWith { SpeedTester.ImgVerdict.OK },
                remoteVersion = 8,
                expectedConfigRevision = RouteManager.configRevision,
                lineDiagnostics = lineDiagnostics,
                imageDiagnostics = imageDiagnostics,
                startedAtEpochMs = 3_000L,
                completedAtEpochMs = 4_000L,
                networkPath = currentPath(),
                completeSnapshot = true,
                lineMeasuredAtEpochMs = prepared.lineMeasuredAtEpochMs + (addedLine to 3_500L),
                imageMeasuredAtEpochMs = prepared.imageMeasuredAtEpochMs + (addedImage to 3_500L),
                lineIdentities = currentLineIdentities,
                imageIdentities = currentImageIdentities,
            ),
        )

        val loaded = RouteManager.speedTestHistoryForVersion(8)!!
        assertEquals(8, loaded.remoteVersion)
        assertEquals(setOf(retainedLine, addedLine), loaded.lineDelays.keys)
        assertEquals(setOf(retainedImage, addedImage), loaded.imgDelays.keys)
        assertEquals(2_000L, loaded.lineMeasuredAtEpochMs[retainedLine], "reused sample keeps its original measurement time")
        assertEquals(2_000L, loaded.imageMeasuredAtEpochMs[retainedImage])
        assertEquals(3_500L, loaded.lineMeasuredAtEpochMs[addedLine])
        assertEquals(3_500L, loaded.imageMeasuredAtEpochMs[addedImage])
        assertEquals(currentLineIdentities, loaded.lineIdentities)
        assertEquals(currentImageIdentities, loaded.imageIdentities)
        assertFalse(deletedLine in loaded.lineDelays, "new-version history is a full replacement, not an append")
        assertFalse(deletedImage in loaded.imgDelays)
        assertEquals(8, JSONObject(historyJson()).getInt("remoteVersion"))
    }

    @Test
    fun `config revision mismatch after preparation does not publish or persist prepared history`() {
        preparePriorSnapshot()
        applyConfig(version = 8)
        val expectedRevision = RouteManager.configRevision
        val prepared = reconcile(8, oldLineIdentities, oldImageIdentities)
        val rawBefore = historyJson()
        val snapshotBefore = RouteManager.speedTestSnapshot.value

        // Replacing the lists advances the generation after preparation but before apply.
        RouteManager.applyBaseLists(oldLines, oldImages, remoteVersion = 8)
        val accepted = RouteManager.setEndpointDelays(
            line = prepared.lineDelays,
            img = prepared.imgDelays,
            imgVerdicts = prepared.imgVerdicts,
            remoteVersion = 8,
            expectedConfigRevision = expectedRevision,
            expectedPlatformRevision = StartupPlatformPolicy.state.value.revision,
            lineUntestedEndpoints = prepared.lineDelays.keys,
            imageUntestedEndpoints = prepared.imgDelays.keys,
            lineMeasuredAtEpochMs = prepared.lineMeasuredAtEpochMs,
            imageMeasuredAtEpochMs = prepared.imageMeasuredAtEpochMs,
            lineIdentities = prepared.lineIdentities,
            imageIdentities = prepared.imageIdentities,
        )

        assertFalse(accepted)
        assertEquals(snapshotBefore, RouteManager.speedTestSnapshot.value)
        assertEquals(rawBefore, historyJson(), "a stale config generation must not publish the prepared version")
        assertEquals(7, RouteManager.latestSpeedTestHistory()?.remoteVersion)
    }

    private fun preparePriorSnapshot() {
        RouteManager.setCustomRule(oldRules)
        RouteManager.applyBaseLists(oldLines, oldImages, remoteVersion = 7)
        val lines = linkedMapOf(retainedLine to 80L, deletedLine to 30L)
        val images = linkedMapOf(retainedImage to 50L, deletedImage to 20L)
        assertTrue(
            RouteManager.setEndpointDelays(
                line = lines,
                img = images,
                imgVerdicts = images.keys.associateWith { SpeedTester.ImgVerdict.OK },
                remoteVersion = 7,
                expectedConfigRevision = RouteManager.configRevision,
                lineDiagnostics = lines.mapValues { (_, delay) -> diagnostic(delay, image = false) },
                imageDiagnostics = images.mapValues { (_, delay) -> diagnostic(delay, image = true) },
                startedAtEpochMs = 1_000L,
                completedAtEpochMs = 2_000L,
                networkPath = currentPath(),
                completeSnapshot = true,
                lineMeasuredAtEpochMs = lines.keys.associateWith { 2_000L },
                imageMeasuredAtEpochMs = images.keys.associateWith { 2_000L },
                lineIdentities = oldLineIdentities,
                imageIdentities = oldImageIdentities,
            ),
        )
        assertNotNull(historyJson())
    }

    private fun applyConfig(version: Int) {
        RouteManager.setCustomRule(oldRules)
        RouteManager.applyBaseLists(oldLines, oldImages, remoteVersion = version)
    }

    private fun reconcile(
        version: Int,
        lineIdentities: Map<DomainIp, String>,
        imageIdentities: Map<DomainIp, String>,
    ): RouteManager.SpeedTestSnapshot = RouteManager.reconcileSpeedTestHistory(
        version = version,
        lineIdentities = lineIdentities,
        imageIdentities = imageIdentities,
        expectedConfigRevision = RouteManager.configRevision,
        expectedPlatformRevision = StartupPlatformPolicy.state.value.revision,
    )!!

    private fun historyJson(): String = prefs.getString("version_speed_test_history_v1", null)!!

    private fun diagnostic(delay: Long, image: Boolean) = SpeedTestEndpointDiagnostic(
        success = true,
        latencyMs = delay,
        elapsedMs = delay,
        failureCategory = null,
        imageVerdict = if (image) "ok" else null,
        attempts = listOf(
            SpeedTestProbeAttempt(
                probe = if (image) "fixed_cover" else "line_api",
                success = true,
                elapsedMs = delay,
                httpStatus = if (image) 206 else 200,
                contentType = if (image) "image/jpeg" else "application/json",
                failureCategory = null,
            ),
        ),
    )

    private fun currentPath() = SpeedTestNetworkPath(
        followSystemVpnEnabled = false,
        systemVpnActive = false,
        directStrategy = true,
        androidApiLevel = android.os.Build.VERSION.SDK_INT,
        androidRelease = android.os.Build.VERSION.RELEASE,
    )

    private class TestContext : ContextWrapper(null) {
        private val stores = ConcurrentHashMap<String, MutableMap<String, Any?>>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val values = stores.computeIfAbsent(name) { ConcurrentHashMap() }
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { sharedPrefs, method, args ->
                when (method.name) {
                    "getString" -> values[args!![0] as String] as? String ?: args[1] as String?
                    "getInt" -> values[args!![0] as String] as? Int ?: args[1] as Int
                    "getLong" -> values[args!![0] as String] as? Long ?: args[1] as Long
                    "getBoolean" -> values[args!![0] as String] as? Boolean ?: args[1] as Boolean
                    "contains" -> values.containsKey(args!![0] as String)
                    "getStringSet" -> (values[args!![0] as String] as? Set<String>)?.toMutableSet() ?: args[1] as MutableSet<String>?
                    "getAll" -> HashMap(values)
                    "edit" -> editor(values)
                    "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                    "toString" -> "TestSharedPreferences"
                    "hashCode" -> System.identityHashCode(sharedPrefs)
                    "equals" -> sharedPrefs === args?.firstOrNull()
                    else -> null
                }
            } as SharedPreferences
        }

        private fun editor(values: MutableMap<String, Any?>): SharedPreferences.Editor {
            val updates = LinkedHashMap<String, Any?>()
            var clear = false
            lateinit var proxy: SharedPreferences.Editor
            proxy = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putString", "putStringSet", "putInt", "putLong", "putFloat", "putBoolean" -> {
                        updates[args!![0] as String] = args[1]
                        proxy
                    }
                    "remove" -> {
                        updates[args!![0] as String] = null
                        proxy
                    }
                    "clear" -> {
                        clear = true
                        proxy
                    }
                    "apply" -> {
                        synchronized(values) {
                            if (clear) values.clear()
                            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        }
                        null
                    }
                    "commit" -> {
                        synchronized(values) {
                            if (clear) values.clear()
                            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        }
                        true
                    }
                    else -> null
                }
            } as SharedPreferences.Editor
            return proxy
        }
    }
}
