package com.zycomic.app.ui.components

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowseScreenSessionPolicyTest {
    @Test
    fun `accepts only results from the same JM session and tab`() {
        assertTrue(
            BrowseScreenSessionPolicy.acceptsResult(
                startedRevision = 7L,
                currentRevision = 7L,
                startedTabKey = "jm_jm_home",
                currentTabKey = "jm_jm_home",
                startedOnJm = true,
                currentlyOnJm = true,
            ),
        )
    }

    @Test
    fun `rejects a result after the account session changes`() {
        assertFalse(
            BrowseScreenSessionPolicy.acceptsResult(
                startedRevision = 7L,
                currentRevision = 8L,
                startedTabKey = "jm_jm_home",
                currentTabKey = "jm_jm_home",
                startedOnJm = true,
                currentlyOnJm = true,
            ),
        )
    }

    @Test
    fun `rejects a result after switching JM tabs or platforms`() {
        assertFalse(
            BrowseScreenSessionPolicy.acceptsResult(
                startedRevision = 7L,
                currentRevision = 7L,
                startedTabKey = "jm_jm_home",
                currentTabKey = "jm_custom_1",
                startedOnJm = true,
                currentlyOnJm = true,
            ),
        )
        assertFalse(
            BrowseScreenSessionPolicy.acceptsResult(
                startedRevision = 7L,
                currentRevision = 7L,
                startedTabKey = "jm_jm_home",
                currentTabKey = "manwa_home",
                startedOnJm = true,
                currentlyOnJm = false,
            ),
        )
        assertFalse(
            BrowseScreenSessionPolicy.acceptsResult(
                startedRevision = 7L,
                currentRevision = 7L,
                startedTabKey = null,
                currentTabKey = null,
                startedOnJm = true,
                currentlyOnJm = true,
            ),
        )
    }
}
