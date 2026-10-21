package com.zycomic.app.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Runs against the production coordinator with the existing offline tester fakes. */
class StartupCompletionToastRuntimeTest {
    @Test
    fun `startup success stays silent after completion while settings success and startup failure are published`() = runBlocking {
        PlatformSpeedTestCoordinator.finishStartupConfigCheck(false)
        PicacgRouteManager.verifiedImageSampleUrl = "https://img.picacg.test/chapter/cover.jpg"
        val notices = mutableListOf<PlatformSpeedTestReport>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            PlatformSpeedTestCoordinator.completions.collect { notices += it }
        }
        try {
            val startup = PlatformSpeedTestCoordinator.retestAllEnabled()
            assertTrue(startup.all { it.status == "tested" })
            delay(50)
            assertTrue(notices.isEmpty())

            PlatformSpeedTestCoordinator.run(forceFresh = true)
            withTimeout(2_000) { while (notices.size < 3) yield() }
            assertTrue(notices.all { it.status == "tested" })
            notices.clear()

            PicacgSpeedTester.failNextRun = true
            PlatformSpeedTestCoordinator.run(forceFresh = true, isStartupRun = true)
            withTimeout(2_000) { while (notices.isEmpty()) yield() }
            delay(50)
            assertEquals(listOf("incomplete"), notices.map { it.status })
            assertEquals(listOf("哔咔"), notices.map { it.platform })
        } finally {
            collector.cancel()
        }
    }
}
