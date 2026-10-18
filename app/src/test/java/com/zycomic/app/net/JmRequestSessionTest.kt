package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmRequestSessionTest {
    @Test
    fun `401 from an older captured session cannot clear a newer login`() {
        val capturedRevision = 12L
        val newerAccountRevision = 13L

        assertFalse(JmRequestSession.isCurrentRevision(capturedRevision, newerAccountRevision))
    }

    @Test
    fun `401 from the current captured session may clear that session`() {
        val capturedRevision = 13L

        assertTrue(JmRequestSession.isCurrentRevision(capturedRevision, capturedRevision))
    }
}
