package com.zycomic.app.net

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.zycomic.app.platform.StartupPlatformPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class JmSpeedTestHistoryStorageIntegrationTest {
    private val context = HistoryTestContext()

    @BeforeEach
    fun setUp() {
        DevConfig.appContext = context
        context.commitsSucceed = true
        context.commitThrows = false
        StartupPlatformPolicy.load(context)
        StartupPlatformPolicy.setJmEnabled(true)
        JmSpeedTestHistoryStorage.saveLines(JmSpeedTestHistoryPolicy.PhaseHistory(null))
        JmSpeedTestHistoryStorage.saveImages(JmSpeedTestHistoryPolicy.PhaseHistory(null))
    }

    @Test
    fun `persisted line and image histories round trip with original measurement timestamps`() {
        val line = measurement(
            phase = JmSpeedTestHistoryPolicy.API_PHASE,
            request = "https://api.example/custom/api",
            domain = "api.example",
            ip = "192.0.2.10",
            delay = 47L,
            measuredAt = 1_700_000_000_123L,
        )
        val image = measurement(
            phase = JmSpeedTestHistoryPolicy.IMAGE_PHASE,
            request = "https://img.example/cover.jpg",
            domain = "img.example",
            ip = "198.51.100.20",
            delay = 83L,
            verdict = JmSpeedTester.ImgVerdict.OK,
            measuredAt = 1_699_999_999_456L,
        )

        assertTrue(persist(listOf(line), listOf(image)))

        assertEquals(JmSpeedTestHistoryPolicy.PhaseHistory(7, listOf(line)), JmSpeedTestHistoryStorage.loadLines())
        assertEquals(JmSpeedTestHistoryPolicy.PhaseHistory(7, listOf(image)), JmSpeedTestHistoryStorage.loadImages())
        assertEquals(1_700_000_000_123L, JmSpeedTestHistoryStorage.loadLines().measurements.single().measuredAtEpochMs)
        assertEquals(1_699_999_999_456L, JmSpeedTestHistoryStorage.loadImages().measurements.single().measuredAtEpochMs)
    }

    @Test
    fun `stale config and new generation guards do not persist histories`() {
        val priorLine = measurement(
            phase = JmSpeedTestHistoryPolicy.API_PHASE,
            request = "https://old.example/api",
            domain = "old.example",
            ip = "192.0.2.1",
            delay = 120L,
            measuredAt = 1_600_000_000_001L,
        )
        val priorImage = measurement(
            phase = JmSpeedTestHistoryPolicy.IMAGE_PHASE,
            request = "https://old-image.example/image",
            domain = "old-image.example",
            ip = "198.51.100.1",
            delay = 200L,
            verdict = JmSpeedTester.ImgVerdict.NO_IMAGE,
            measuredAt = 1_600_000_000_002L,
        )
        assertTrue(persist(listOf(priorLine), listOf(priorImage), version = 4))
        val capturedConfigRevision = JmRouteManager.currentSpeedTestConfigRevision()
        val capturedFingerprint = JmRouteManager.currentSpeedTestConfigFingerprint()
        val capturedPlatformRevision = StartupPlatformPolicy.state.value.revision

        JmRouteManager.applyRemoteConfig(
            lines = listOf("https://new.example"),
            sources = listOf("new-image.example"),
            rules = emptyMap(),
            version = 5,
        )
        val newLine = priorLine.copy(identity = priorLine.identity.copy(domain = "new.example"), measuredAtEpochMs = 1_700_000_000_001L)
        val newImage = priorImage.copy(identity = priorImage.identity.copy(domain = "new-image.example"), measuredAtEpochMs = 1_700_000_000_002L)

        assertFalse(
            JmRouteManager.persistSpeedTestHistories(
                JmSpeedTestHistoryPolicy.PhaseHistory(5, listOf(newLine)),
                JmSpeedTestHistoryPolicy.PhaseHistory(5, listOf(newImage)),
                expectedPlatformRevision = capturedPlatformRevision,
                expectedConfigRevision = capturedConfigRevision,
                expectedConfigFingerprint = capturedFingerprint,
                isCurrent = { true },
            ),
            "the prior config generation must not overwrite history after configuration changes",
        )
        assertFalse(
            JmRouteManager.persistSpeedTestHistories(
                JmSpeedTestHistoryPolicy.PhaseHistory(5, listOf(newLine)),
                JmSpeedTestHistoryPolicy.PhaseHistory(5, listOf(newImage)),
                expectedPlatformRevision = StartupPlatformPolicy.state.value.revision,
                expectedConfigRevision = JmRouteManager.currentSpeedTestConfigRevision(),
                expectedConfigFingerprint = JmRouteManager.currentSpeedTestConfigFingerprint(),
                isCurrent = { false },
            ),
            "a canceled or superseded generation must not write histories",
        )

        assertEquals(JmSpeedTestHistoryPolicy.PhaseHistory(4, listOf(priorLine)), JmSpeedTestHistoryStorage.loadLines())
        assertEquals(JmSpeedTestHistoryPolicy.PhaseHistory(4, listOf(priorImage)), JmSpeedTestHistoryStorage.loadImages())
    }

    @Test
    fun `history commit rejection or exception is returned to the tester`() {
        context.commitsSucceed = false
        assertFalse(persist(emptyList(), emptyList()))
        context.commitsSucceed = true
        context.commitThrows = true
        assertFalse(persist(emptyList(), emptyList()))
    }

    @Test
    fun `history cannot be reported saved without an application context`() {
        DevConfig.appContext = null
        try {
            assertFalse(JmSpeedTestHistoryStorage.saveLines(JmSpeedTestHistoryPolicy.PhaseHistory(7)))
            assertFalse(JmSpeedTestHistoryStorage.saveImages(JmSpeedTestHistoryPolicy.PhaseHistory(7)))
        } finally {
            DevConfig.appContext = context
        }
    }

    /** Compile-only analogue of the actual coordinator call, including its named arguments and trailing lambda. */
    @Suppress("unused")
    private suspend fun coordinatorCallShape() {
        JmSpeedTester.run(
            reuseLines = true,
            reuseImages = false,
            reconcileConfig = true,
            isCurrent = { true },
            beforeApply = { true },
        ) { progress ->
            check(progress.phase != JmSpeedTestProgress.Phase.FAILED)
        }
    }

    private fun persist(
        lines: List<JmSpeedTestHistoryPolicy.Measurement>,
        images: List<JmSpeedTestHistoryPolicy.Measurement>,
        version: Int = 7,
    ): Boolean = JmRouteManager.persistSpeedTestHistories(
        lineHistory = JmSpeedTestHistoryPolicy.PhaseHistory(version, lines),
        imageHistory = JmSpeedTestHistoryPolicy.PhaseHistory(version, images),
        expectedPlatformRevision = StartupPlatformPolicy.state.value.revision,
        expectedConfigRevision = JmRouteManager.currentSpeedTestConfigRevision(),
        expectedConfigFingerprint = JmRouteManager.currentSpeedTestConfigFingerprint(),
        isCurrent = { true },
    )

    private fun measurement(
        phase: String,
        request: String,
        domain: String,
        ip: String,
        delay: Long,
        measuredAt: Long,
        verdict: JmSpeedTester.ImgVerdict? = null,
    ) = JmSpeedTestHistoryPolicy.Measurement(
        identity = JmSpeedTestHistoryPolicy.Identity(
            phase = phase,
            request = request,
            domain = domain,
            ip = ip,
            routeCondition = "configured-rules",
            probeCondition = "success-contract-v1",
        ),
        delayMs = delay,
        verdict = verdict,
        measuredAtEpochMs = measuredAt,
    )

    private class HistoryTestContext : ContextWrapper(null) {
        var commitsSucceed = true
        var commitThrows = false
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
                    "getAll" -> HashMap(values)
                    "edit" -> editor(values)
                    "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                    "toString" -> "JM history SharedPreferences"
                    "hashCode" -> System.identityHashCode(sharedPrefs)
                    "equals" -> sharedPrefs === args?.firstOrNull()
                    else -> null
                }
            } as SharedPreferences
        }

        private fun editor(values: MutableMap<String, Any?>): SharedPreferences.Editor {
            val updates = LinkedHashMap<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putString", "putInt", "putLong", "putBoolean" -> {
                        updates[args!![0] as String] = args[1]
                        editor
                    }
                    "remove" -> {
                        updates[args!![0] as String] = null
                        editor
                    }
                    "clear" -> editor
                    "apply", "commit" -> {
                        if (method.name == "commit" && commitThrows) error("simulated storage failure")
                        synchronized(values) { updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value } }
                        if (method.name == "commit") commitsSucceed else null
                    }
                    else -> null
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
