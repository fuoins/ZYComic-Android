package com.zycomic.app.platform

import com.zycomic.app.platform.StartupPlatformPolicy.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupPlatformPolicyTest {
    @Test
    fun `disabled platform guidance offers startup or settings then restart`() {
        assertEquals(
            "请在开屏页开启漫蛙，或在设置页开启后重启软件",
            StartupPlatformPolicy.disabledSelectionMessage(ContentPlatform.MANWA),
        )
        assertEquals(
            "请在开屏页开启哔咔，或在设置页开启后重启软件",
            StartupPlatformPolicy.disabledSelectionMessage(ContentPlatform.PICACG),
        )
        assertEquals(
            "请在开屏页开启禁漫天堂，或在设置页开启后重启软件",
            StartupPlatformPolicy.disabledSelectionMessage(ContentPlatform.JM),
        )
    }

    @Test
    fun `defaults enable all platforms and reuse the last line and image results`() {
        val state = State()

        assertFalse(state.disableManwa)
        assertFalse(state.disablePicacg)
        assertFalse(state.disableJm)
        assertTrue(state.useLastResult)
        assertTrue(state.useLastLineResult)
        assertEquals(0L, state.revision)
        assertTrue(StartupPlatformPolicy.isEnabled(state, ContentPlatform.MANWA))
        assertTrue(StartupPlatformPolicy.isEnabled(state, ContentPlatform.PICACG))
        assertTrue(StartupPlatformPolicy.isEnabled(state, ContentPlatform.JM))
    }

    @Test
    fun `resolve defaults to JM and substitutes a disabled platform`() {
        val defaults = State()
        val onlyJm = State(disableManwa = true, disablePicacg = true)
        val bothAvailable = StartupPlatformPolicy.transition(onlyJm, disableManwa = false)
        val onlyManwa = StartupPlatformPolicy.transition(bothAvailable, disableJm = true)

        assertEquals(ContentPlatform.JM, StartupPlatformPolicy.resolve(defaults, null))
        assertEquals(ContentPlatform.MANWA, StartupPlatformPolicy.resolve(defaults, ContentPlatform.MANWA))
        assertEquals(ContentPlatform.MANWA, StartupPlatformPolicy.resolve(onlyManwa, null))
        assertEquals(ContentPlatform.MANWA, StartupPlatformPolicy.resolve(onlyManwa, ContentPlatform.JM))
        assertEquals(ContentPlatform.MANWA, StartupPlatformPolicy.resolve(bothAvailable, ContentPlatform.MANWA))
    }

    @Test
    fun `platform ids and selector order preserve existing identities and defaults`() {
        assertEquals(listOf(ContentPlatform.MANWA, ContentPlatform.PICACG, ContentPlatform.JM), ContentPlatform.entries)
        assertEquals("manwa", ContentPlatform.MANWA.id)
        assertEquals("jm", ContentPlatform.JM.id)
        assertEquals(ContentPlatform.JM, ContentPlatform.fromId(null))
        assertEquals(ContentPlatform.PICACG, ContentPlatform.fromId("picacg"))
    }

    @Test
    fun `Picacg can be the only enabled platform and disabled choices resolve without cross-routing`() {
        val picacgOnly = StartupPlatformPolicy.transition(
            State(disableManwa = true, disablePicacg = true),
            disablePicacg = false,
            disableJm = true,
        )

        assertTrue(StartupPlatformPolicy.isEnabled(picacgOnly, ContentPlatform.PICACG))
        assertFalse(StartupPlatformPolicy.isEnabled(picacgOnly, ContentPlatform.MANWA))
        assertFalse(StartupPlatformPolicy.isEnabled(picacgOnly, ContentPlatform.JM))
        assertEquals(ContentPlatform.PICACG, StartupPlatformPolicy.resolve(picacgOnly, ContentPlatform.JM))
        assertEquals(picacgOnly, StartupPlatformPolicy.transition(picacgOnly, disablePicacg = true))
    }

    @Test
    fun `a second disable that would leave no source is rejected without changing either switch`() {
        val onlyJmStart = State(disableManwa = true, disablePicacg = true)
        val rejected = StartupPlatformPolicy.transition(onlyJmStart, disableJm = true)

        assertFalse(StartupPlatformPolicy.canTransition(onlyJmStart, disableJm = true))
        assertEquals(onlyJmStart, rejected)

        val bothEnabled = StartupPlatformPolicy.transition(onlyJmStart, disableManwa = false)
        val onlyManwa = StartupPlatformPolicy.transition(bothEnabled, disableJm = true)
        val onlyJm = StartupPlatformPolicy.transition(bothEnabled, disableManwa = true)

        assertTrue(StartupPlatformPolicy.canTransition(bothEnabled, disableJm = true))
        assertEquals(State(disableManwa = false, disablePicacg = true, disableJm = true, revision = 2), onlyManwa)
        assertEquals(State(disableManwa = true, disablePicacg = true, disableJm = false, revision = 2), onlyJm)
        assertTrue(StartupPlatformPolicy.isEnabled(onlyManwa, ContentPlatform.MANWA))
        assertFalse(StartupPlatformPolicy.isEnabled(onlyManwa, ContentPlatform.JM))
        assertFalse(StartupPlatformPolicy.isEnabled(onlyJm, ContentPlatform.MANWA))
        assertTrue(StartupPlatformPolicy.isEnabled(onlyJm, ContentPlatform.JM))
    }

    @Test
    fun `default state keeps multiple sources available`() {
        val onlyJm = State(disableManwa = true, disablePicacg = true)
        val bothEnabled = StartupPlatformPolicy.transition(onlyJm, disableManwa = false)

        assertFalse(bothEnabled.disableManwa)
        assertTrue(bothEnabled.disablePicacg)
        assertFalse(bothEnabled.disableJm)
        assertTrue(StartupPlatformPolicy.isEnabled(bothEnabled, ContentPlatform.MANWA))
        assertTrue(StartupPlatformPolicy.isEnabled(bothEnabled, ContentPlatform.JM))
    }

    @Test
    fun `disable changes preserve the other switch unless a source would be removed`() {
        val onlyJm = State(disableManwa = true, disablePicacg = true)
        val bothEnabled = StartupPlatformPolicy.transition(onlyJm, disableManwa = false)
        val jmDisabled = StartupPlatformPolicy.transition(bothEnabled, disableJm = true)
        val rejectedManwaDisable = StartupPlatformPolicy.transition(jmDisabled, disableManwa = true)
        val noMemory = StartupPlatformPolicy.transition(bothEnabled, useLastResult = false)
        val unchanged = StartupPlatformPolicy.transition(noMemory, useLastResult = false)

        assertEquals(State(disableManwa = false, disablePicacg = true, disableJm = false, revision = 1), bothEnabled)
        assertEquals(State(disableManwa = false, disablePicacg = true, disableJm = true, revision = 2), jmDisabled)
        assertEquals(jmDisabled, rejectedManwaDisable)
        assertEquals(State(disableManwa = false, disablePicacg = true, disableJm = false, useLastResult = false, revision = 2), noMemory)
        assertEquals(noMemory, unchanged)
    }

    @Test
    fun `speed test reuse preference never changes allowed page memory`() {
        val state = State(disableManwa = false, useLastResult = false)
        assertEquals(ContentPlatform.MANWA, StartupPlatformPolicy.resolve(state, ContentPlatform.MANWA))
        assertEquals(ContentPlatform.JM, StartupPlatformPolicy.resolve(state, ContentPlatform.JM))
    }

    @Test
    fun `line configuration and image source reuse preferences are independent`() {
        val defaults = State()
        val lineReuseOff = StartupPlatformPolicy.transition(defaults, useLastLineResult = false)
        val imageReuseOff = StartupPlatformPolicy.transition(defaults, useLastResult = false)
        val bothReuseOff = StartupPlatformPolicy.transition(lineReuseOff, useLastResult = false)

        assertFalse(lineReuseOff.useLastLineResult)
        assertTrue(lineReuseOff.useLastResult)
        assertEquals(1L, lineReuseOff.revision)
        assertTrue(imageReuseOff.useLastLineResult)
        assertFalse(imageReuseOff.useLastResult)
        assertEquals(1L, imageReuseOff.revision)
        assertFalse(bothReuseOff.useLastLineResult)
        assertFalse(bothReuseOff.useLastResult)
        assertEquals(2L, bothReuseOff.revision)
    }

    @Test
    fun `legacy negative preferences migrate by inversion and retain the selected source`() {
        assertEquals(
            true to false,
            StartupPlatformPolicy.migratedEnabledValues(
                manwaEnabled = null,
                jmEnabled = null,
                legacyDisableManwa = false,
                legacyDisableJm = true,
            ),
        )
        assertEquals(
            false to true,
            StartupPlatformPolicy.migratedEnabledValues(
                manwaEnabled = null,
                jmEnabled = null,
                legacyDisableManwa = true,
                legacyDisableJm = false,
            ),
        )
    }

    @Test
    fun `all absent preferences enable every platform`() {
        assertEquals(
            Triple(true, true, true),
            StartupPlatformPolicy.migratedEnabledState(
                manwaEnabled = null,
                picacgEnabled = null,
                jmEnabled = null,
                legacyDisableManwa = null,
                legacyDisablePicacg = null,
                legacyDisableJm = null,
            ),
        )
    }

    @Test
    fun `every legacy negative preference combination is retained`() {
        for (mask in 0 until 8) {
            val expected = Triple(mask and 1 == 0, mask and 2 == 0, mask and 4 == 0)
                .let { if (!it.first && !it.second && !it.third) Triple(false, false, true) else it }
            assertEquals(
                expected,
                StartupPlatformPolicy.migratedEnabledState(
                    manwaEnabled = null,
                    picacgEnabled = null,
                    jmEnabled = null,
                    legacyDisableManwa = mask and 1 != 0,
                    legacyDisablePicacg = mask and 2 != 0,
                    legacyDisableJm = mask and 4 != 0,
                ),
                "legacy preference mask $mask",
            )
        }
    }

    @Test
    fun `every positive preference combination is retained with legacy values taking lower priority`() {
        for (mask in 0 until 8) {
            val manwa = mask and 1 != 0
            val picacg = mask and 2 != 0
            val jm = mask and 4 != 0
            val expected = if (!manwa && !picacg && !jm) Triple(false, false, true) else Triple(manwa, picacg, jm)
            assertEquals(
                expected,
                StartupPlatformPolicy.migratedEnabledState(
                    manwaEnabled = manwa,
                    picacgEnabled = picacg,
                    jmEnabled = jm,
                    legacyDisableManwa = manwa,
                    legacyDisablePicacg = picacg,
                    legacyDisableJm = jm,
                ),
                "positive preference mask $mask",
            )
        }
    }

    @Test
    fun `positive preferences override legacy values and missing values use explicit legacy or new defaults`() {
        assertEquals(
            Triple(true, false, true),
            StartupPlatformPolicy.migratedEnabledState(
                manwaEnabled = true,
                picacgEnabled = false,
                jmEnabled = true,
                legacyDisableManwa = true,
                legacyDisablePicacg = false,
                legacyDisableJm = true,
            ),
        )
        assertEquals(
            Triple(false, false, true),
            StartupPlatformPolicy.migratedEnabledState(
                manwaEnabled = false,
                picacgEnabled = null,
                jmEnabled = null,
                legacyDisableManwa = false,
                legacyDisablePicacg = true,
                legacyDisableJm = null,
            ),
        )
        assertEquals(
            Triple(true, true, false),
            StartupPlatformPolicy.migratedEnabledState(
                manwaEnabled = null,
                picacgEnabled = null,
                jmEnabled = false,
                legacyDisableManwa = null,
                legacyDisablePicacg = false,
                legacyDisableJm = false,
            ),
        )
    }

    @Test
    fun `two platform migration retains positive priority and at least one enabled source`() {
        assertEquals(
            true to false,
            StartupPlatformPolicy.migratedEnabledValues(
                manwaEnabled = true,
                jmEnabled = false,
                legacyDisableManwa = true,
                legacyDisableJm = false,
            ),
        )
        assertEquals(
            false to true,
            StartupPlatformPolicy.migratedEnabledValues(
                manwaEnabled = false,
                jmEnabled = false,
                legacyDisableManwa = false,
                legacyDisableJm = true,
            ),
        )
    }
}
