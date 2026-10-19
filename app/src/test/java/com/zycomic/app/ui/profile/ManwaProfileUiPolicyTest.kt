package com.zycomic.app.ui.profile

import com.zycomic.app.data.repository.ManwaSessionStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManwaProfileUiPolicyTest {
    @Test
    fun `unknown cached session does not show warning during initial or retry refresh`() {
        assertFalse(ManwaProfileUiPolicy.showUnverifiedWarning(ManwaSessionStatus.UNKNOWN, refreshing = true))
        assertFalse(ManwaProfileUiPolicy.showUnverifiedWarning(ManwaSessionStatus.UNKNOWN, refreshing = true))
    }

    @Test
    fun `unknown session shows warning after refresh finishes without verification`() {
        assertTrue(ManwaProfileUiPolicy.showUnverifiedWarning(ManwaSessionStatus.UNKNOWN, refreshing = false))
    }

    @Test
    fun `verified signed out and expired sessions do not show cached unverified warning`() {
        assertFalse(ManwaProfileUiPolicy.showUnverifiedWarning(ManwaSessionStatus.VERIFIED, refreshing = false))
        assertFalse(ManwaProfileUiPolicy.showUnverifiedWarning(ManwaSessionStatus.SIGNED_OUT, refreshing = false))
        assertFalse(ManwaProfileUiPolicy.showUnverifiedWarning(ManwaSessionStatus.EXPIRED, refreshing = false))
    }
}
