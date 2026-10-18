package com.zycomic.app.ui.login

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmAccountCapabilityPolicyTest {
    @Test
    fun `verified account flows are enabled without email login`() {
        assertTrue(JmAccountCapabilityPolicy.supportsUsernamePasswordLogin())
        assertFalse(JmAccountCapabilityPolicy.supportsEmailLogin())
        assertTrue(JmAccountCapabilityPolicy.supportsRegistration())
        assertTrue(JmAccountCapabilityPolicy.supportsActivation())
        assertTrue(JmAccountCapabilityPolicy.supportsForgotPassword())
        assertTrue(JmAccountCapabilityPolicy.supportsPasswordChange())
    }
}
