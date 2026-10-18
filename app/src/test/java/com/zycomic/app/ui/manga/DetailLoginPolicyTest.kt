package com.zycomic.app.ui.manga

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailLoginPolicyTest {
    @Test
    fun `JM login does not unlock Manwa actions`() {
        assertFalse(DetailLoginPolicy.isAuthenticated(isJm = false, jmAuthenticated = true, manwaAuthenticated = false))
        assertTrue(DetailLoginPolicy.isAuthenticated(isJm = true, jmAuthenticated = true, manwaAuthenticated = false))
    }

    @Test
    fun `Manwa login does not unlock JM actions`() {
        assertFalse(DetailLoginPolicy.isAuthenticated(isJm = true, jmAuthenticated = false, manwaAuthenticated = true))
        assertTrue(DetailLoginPolicy.isAuthenticated(isJm = false, jmAuthenticated = false, manwaAuthenticated = true))
    }

    @Test
    fun `signed out accounts require login on both sources`() {
        listOf(true, false).forEach { isJm ->
            assertFalse(DetailLoginPolicy.isAuthenticated(isJm, jmAuthenticated = false, manwaAuthenticated = false))
            assertTrue(DetailLoginPolicy.isAuthenticated(isJm, jmAuthenticated = true, manwaAuthenticated = true))
        }
    }
}
