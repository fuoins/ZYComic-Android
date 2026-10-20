package com.zycomic.app.picacg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgAutoPunchCoordinatorTest {
    @Test
    fun `preference defaults on and choice persists across instances`() {
        val store = MemoryBooleanStore()
        val first = StoredPicacgAutoPunchPreference(store)

        assertTrue(first.enabled)
        first.enabled = false

        val second = StoredPicacgAutoPunchPreference(store)
        assertFalse(second.enabled)
        second.enabled = true
        assertTrue(StoredPicacgAutoPunchPreference(store).enabled)
    }

    @Test
    fun `logged out page performs no requests`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, loggedIn = { false })

        assertNull(coordinator.loadProfileForPage(7L))
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `initial GET precedes one POST and successful refresh GET`() = runBlocking {
        val events = mutableListOf<String>()
        var currentRevision = 3L
        val coordinator = PicacgAutoPunchCoordinator(
            preference = StoredPicacgAutoPunchPreference(MemoryBooleanStore()),
            isLoggedIn = { true },
            currentSessionRevision = { currentRevision },
            loadProfile = { revision ->
                assertEquals(3L, revision)
                events += "GET profile"
                profile(false)
            },
            punchInAndRefresh = { revision ->
                assertEquals(3L, revision)
                events += "POST punch-in"
                events += "GET profile after POST"
                currentRevision = revision
                profile(true)
            },
        )

        assertEquals(profile(true), coordinator.loadProfileForPage(3L))
        assertEquals(listOf("GET profile", "POST punch-in", "GET profile after POST"), events)
    }

    @Test
    fun `already punched and unknown states never POST`() = runBlocking {
        for (state in listOf(true, null)) {
            val events = mutableListOf<String>()
            val coordinator = coordinator(events, initialState = state)

            assertEquals(profile(state), coordinator.loadProfileForPage(7L))
            assertEquals(listOf("GET profile"), events)
        }
    }

    @Test
    fun `disabled preference only reads profile`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, enabled = false, initialState = false)

        assertEquals(profile(false), coordinator.loadProfileForPage(7L))
        assertEquals(listOf("GET profile"), events)
    }

    @Test
    fun `profile GET failure is propagated without retry or POST`() = runBlocking {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("GET failed")
        val coordinator = coordinator(events, load = {
            events += "GET profile"
            throw failure
        })

        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.loadProfileForPage(7L) }
            },
        )
        assertEquals(listOf("GET profile"), events)
    }

    @Test
    fun `POST failure is propagated and not retried`() = runBlocking {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("POST failed")
        val coordinator = coordinator(events, initialState = false, punch = {
            events += "POST punch-in"
            throw failure
        })

        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.loadProfileForPage(7L) }
            },
        )
        assertEquals(listOf("GET profile", "POST punch-in"), events)
    }

    @Test
    fun `post succeeds but refresh failure is propagated without another POST`() = runBlocking {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("refresh failed")
        val coordinator = coordinator(events, initialState = false, punch = {
            events += "POST punch-in"
            events += "GET profile after POST"
            throw failure
        })

        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.loadProfileForPage(7L) }
            },
        )
        assertEquals(listOf("GET profile", "POST punch-in", "GET profile after POST"), events)
    }

    @Test
    fun `session change after initial GET prevents POST`() = runBlocking {
        val events = mutableListOf<String>()
        var currentRevision = 7L
        val coordinator = PicacgAutoPunchCoordinator(
            preference = StoredPicacgAutoPunchPreference(MemoryBooleanStore()),
            isLoggedIn = { true },
            currentSessionRevision = { currentRevision },
            loadProfile = {
                events += "GET profile"
                currentRevision = 8L
                profile(false)
            },
            punchInAndRefresh = {
                events += "POST punch-in"
                profile(true)
            },
        )

        assertNull(coordinator.loadProfileForPage(7L))
        assertEquals(listOf("GET profile"), events)
    }

    @Test
    fun `cancellation propagates without further requests`() = runBlocking {
        val events = mutableListOf<String>()
        val cancellation = CancellationException("cancelled")
        val coordinator = coordinator(events, load = {
            events += "GET profile"
            throw cancellation
        })

        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
                runBlocking { coordinator.loadProfileForPage(7L) }
            },
        )
        assertEquals(listOf("GET profile"), events)
    }

    @Test
    fun `changing preference alone does not issue requests`() = runBlocking {
        val events = mutableListOf<String>()
        val store = MemoryBooleanStore()
        val preference = StoredPicacgAutoPunchPreference(store)
        val coordinator = coordinator(events, preference = preference, initialState = false)

        preference.enabled = false
        assertEquals(emptyList<String>(), events)
        assertEquals(profile(false), coordinator.loadProfileForPage(7L))
        assertEquals(listOf("GET profile"), events)
    }

    private fun coordinator(
        events: MutableList<String>,
        enabled: Boolean = true,
        initialState: Boolean? = false,
        loggedIn: () -> Boolean = { true },
        load: (suspend () -> PicacgProfile)? = null,
        punch: (suspend () -> PicacgProfile)? = null,
        preference: PicacgAutoPunchPreference = StoredPicacgAutoPunchPreference(MemoryBooleanStore(enabled)),
    ) = PicacgAutoPunchCoordinator(
        preference = preference,
        isLoggedIn = loggedIn,
        currentSessionRevision = { 7L },
        loadProfile = {
            load?.invoke() ?: run {
                events += "GET profile"
                profile(initialState)
            }
        },
        punchInAndRefresh = {
            punch?.invoke() ?: run {
                events += "POST punch-in"
                events += "GET profile after POST"
                profile(true)
            }
        },
    )

    private fun profile(isPunched: Boolean?) = PicacgProfile(id = "mock-user", name = "Mock", isPunched = isPunched)

    private class MemoryBooleanStore(initial: Boolean? = null) : PicacgAutoPunchBooleanStore {
        private val values = mutableMapOf<String, Boolean>()

        init {
            if (initial != null) values["picacg_auto_punch_in_enabled"] = initial
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }
    }
}
