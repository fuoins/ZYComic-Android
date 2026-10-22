package com.zycomic.app.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPlatformRetryPolicyTest {
    @Test
    fun `Picacg and Manwa budgets use distinct platform keys and candidate pools`() {
        val manwaKey = ReaderPlatformRetryPolicy.budgetKey(ReaderRetryPlatform.MANWA, "img-manwa.example")
        val picacgKey = ReaderPlatformRetryPolicy.budgetKey(ReaderRetryPlatform.PICACG, "picacg-config-4")

        assertNotEquals(manwaKey, picacgKey)
        assertTrue(ReaderPlatformRetryPolicy.supportsEndpointFallback(ReaderRetryPlatform.MANWA))
        assertFalse(ReaderPlatformRetryPolicy.supportsEndpointFallback(ReaderRetryPlatform.PICACG))
        assertFalse(ReaderPlatformRetryPolicy.manualRetryLimitReached(3, manwaKey, picacgKey))
        assertTrue(ReaderPlatformRetryPolicy.manualRetryLimitReached(3, picacgKey, picacgKey))
        assertFalse(ReaderPlatformRetryPolicy.shouldAutomaticallyRetryHttpFailure(429))
        assertTrue(ReaderPlatformRetryPolicy.shouldAutomaticallyRetryHttpFailure(503))
    }

    @Test
    fun `JM retry availability follows the same source key as its loader budget`() {
        val jmKey = ReaderPlatformRetryPolicy.budgetKey(ReaderRetryPlatform.JM, "jm-image.example")
        val anotherJmKey = ReaderPlatformRetryPolicy.budgetKey(ReaderRetryPlatform.JM, "jm-image-2.example")

        assertTrue(ReaderPlatformRetryPolicy.manualRetryLimitReached(3, jmKey, jmKey))
        assertFalse(ReaderPlatformRetryPolicy.manualRetryLimitReached(3, jmKey, anotherJmKey))
    }

    @Test
    fun `runtime recovery accepts only current visible failed pages in matching platform config`() {
        assertTrue(
            ReaderPlatformRetryPolicy.mayRestoreRuntimeFailure(
                platform = ReaderRetryPlatform.JM,
                currentBudgetKey = "jm:route-a",
                expectedBudgetKey = "jm:route-a",
                currentGeneration = 12,
                expectedGeneration = 12,
                isVisible = true,
                isFailed = true,
                isRecycled = false,
                schedulingEnabled = true,
            ),
        )
        assertFalse(mayRestore(generation = 13, expectedGeneration = 12))
        assertFalse(mayRestore(visible = false))
        assertFalse(mayRestore(currentKey = "manwa:route-b", expectedKey = "manwa:route-a"))
        assertTrue(mayRestore(platform = ReaderRetryPlatform.PICACG, currentKey = "picacg:revision-4"))
        assertFalse(
            mayRestore(
                platform = ReaderRetryPlatform.PICACG,
                currentKey = "picacg:revision-5",
                expectedKey = "picacg:revision-4",
            ),
        )
    }

    private fun mayRestore(
        platform: ReaderRetryPlatform = ReaderRetryPlatform.MANWA,
        currentKey: String = "manwa:route-a",
        expectedKey: String = currentKey,
        generation: Int = 12,
        expectedGeneration: Int = generation,
        visible: Boolean = true,
    ) = ReaderPlatformRetryPolicy.mayRestoreRuntimeFailure(
        platform = platform,
        currentBudgetKey = currentKey,
        expectedBudgetKey = expectedKey,
        currentGeneration = generation,
        expectedGeneration = expectedGeneration,
        isVisible = visible,
        isFailed = true,
        isRecycled = false,
        schedulingEnabled = true,
    )
}
