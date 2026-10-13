package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteConfigVersionPolicyTest {
    @Test
    fun `only strictly newer remote versions are applied`() {
        assertTrue(RemoteConfigVersionPolicy.shouldApply(incomingVersion = 8, appliedVersion = 7))
        assertFalse(RemoteConfigVersionPolicy.shouldApply(incomingVersion = 7, appliedVersion = 7))
        assertFalse(RemoteConfigVersionPolicy.shouldApply(incomingVersion = 6, appliedVersion = 7))
    }
}
