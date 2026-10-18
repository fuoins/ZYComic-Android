package com.zycomic.app.platform

import com.zycomic.app.platform.StartupPlatformPolicy.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupPlatformPolicyTest {
    @Test
    fun `defaults disable Manwa and reuse the last speed test`() {
        val state = State()

        assertTrue(state.disableManwa)
        assertFalse(state.disableJm)
        assertTrue(state.useLastResult)
        assertEquals(0L, state.revision)
        assertFalse(StartupPlatformPolicy.isEnabled(state, ContentPlatform.MANWA))
        assertTrue(StartupPlatformPolicy.isEnabled(state, ContentPlatform.JM))
    }

    @Test
    fun `resolve defaults to JM and substitutes a disabled platform`() {
        val defaults = State()
        val bothAvailable = StartupPlatformPolicy.transition(defaults, disableManwa = false)
        val onlyManwa = StartupPlatformPolicy.transition(bothAvailable, disableJm = true)

        assertEquals(ContentPlatform.JM, StartupPlatformPolicy.resolve(defaults, null))
        assertEquals(ContentPlatform.JM, StartupPlatformPolicy.resolve(defaults, ContentPlatform.MANWA))
        assertEquals(ContentPlatform.MANWA, StartupPlatformPolicy.resolve(onlyManwa, null))
        assertEquals(ContentPlatform.MANWA, StartupPlatformPolicy.resolve(onlyManwa, ContentPlatform.JM))
        assertEquals(ContentPlatform.MANWA, StartupPlatformPolicy.resolve(bothAvailable, ContentPlatform.MANWA))
    }

    @Test
    fun `a second disable that would leave no source is rejected without changing either switch`() {
        val defaults = State()
        val rejected = StartupPlatformPolicy.transition(defaults, disableJm = true)

        assertFalse(StartupPlatformPolicy.canTransition(defaults, disableJm = true))
        assertEquals(defaults, rejected)

        val bothEnabled = StartupPlatformPolicy.transition(defaults, disableManwa = false)
        val onlyManwa = StartupPlatformPolicy.transition(bothEnabled, disableJm = true)
        val onlyJm = StartupPlatformPolicy.transition(bothEnabled, disableManwa = true)

        assertTrue(StartupPlatformPolicy.canTransition(bothEnabled, disableJm = true))
        assertEquals(State(disableManwa = false, disableJm = true, revision = 2), onlyManwa)
        assertEquals(State(disableManwa = true, disableJm = false, revision = 2), onlyJm)
        assertTrue(StartupPlatformPolicy.isEnabled(onlyManwa, ContentPlatform.MANWA))
        assertFalse(StartupPlatformPolicy.isEnabled(onlyManwa, ContentPlatform.JM))
        assertFalse(StartupPlatformPolicy.isEnabled(onlyJm, ContentPlatform.MANWA))
        assertTrue(StartupPlatformPolicy.isEnabled(onlyJm, ContentPlatform.JM))
    }

    @Test
    fun `both disable switches off means both sources are available`() {
        val bothEnabled = StartupPlatformPolicy.transition(State(), disableManwa = false)

        assertFalse(bothEnabled.disableManwa)
        assertFalse(bothEnabled.disableJm)
        assertTrue(StartupPlatformPolicy.isEnabled(bothEnabled, ContentPlatform.MANWA))
        assertTrue(StartupPlatformPolicy.isEnabled(bothEnabled, ContentPlatform.JM))
    }

    @Test
    fun `disable changes preserve the other switch unless a source would be removed`() {
        val bothEnabled = StartupPlatformPolicy.transition(State(), disableManwa = false)
        val jmDisabled = StartupPlatformPolicy.transition(bothEnabled, disableJm = true)
        val rejectedManwaDisable = StartupPlatformPolicy.transition(jmDisabled, disableManwa = true)
        val noMemory = StartupPlatformPolicy.transition(bothEnabled, useLastResult = false)
        val unchanged = StartupPlatformPolicy.transition(noMemory, useLastResult = false)

        assertEquals(State(disableManwa = false, disableJm = false, revision = 1), bothEnabled)
        assertEquals(State(disableManwa = false, disableJm = true, revision = 2), jmDisabled)
        assertEquals(jmDisabled, rejectedManwaDisable)
        assertEquals(State(disableManwa = false, disableJm = false, useLastResult = false, revision = 2), noMemory)
        assertEquals(noMemory, unchanged)
    }

    @Test
    fun `speed test reuse preference never changes allowed page memory`() {
        val state = State(disableManwa = false, useLastResult = false)
        assertEquals(ContentPlatform.MANWA, StartupPlatformPolicy.resolve(state, ContentPlatform.MANWA))
        assertEquals(ContentPlatform.JM, StartupPlatformPolicy.resolve(state, ContentPlatform.JM))
    }
}
