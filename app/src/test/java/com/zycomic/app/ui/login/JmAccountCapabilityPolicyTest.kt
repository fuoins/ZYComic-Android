package com.zycomic.app.ui.login

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmAccountCapabilityPolicyTest {
    @Test
    fun `only verified username and password login is enabled`() {
        assertTrue(JmAccountCapabilityPolicy.supportsUsernamePasswordLogin())
        assertFalse(JmAccountCapabilityPolicy.supportsEmailLogin())
        assertFalse(JmAccountCapabilityPolicy.supportsRegistration())
        assertFalse(JmAccountCapabilityPolicy.supportsActivation())
        assertFalse(JmAccountCapabilityPolicy.supportsForgotPassword())
        assertFalse(JmAccountCapabilityPolicy.supportsPasswordChange())
    }
}
