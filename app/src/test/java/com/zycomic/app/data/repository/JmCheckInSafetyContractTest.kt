package com.zycomic.app.data.repository

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JmCheckInSafetyContractTest {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("app/src/main/java/com/zycomic/app/data/repository/JmCheckInRepository.kt")) }
    private val repository = Files.readString(root.resolve("app/src/main/java/com/zycomic/app/data/repository/JmCheckInRepository.kt"))
    private val viewModel = Files.readString(root.resolve("app/src/main/java/com/zycomic/app/ui/profile/ProfileViewModel.kt"))
    private val profile = Files.readString(root.resolve("app/src/main/java/com/zycomic/app/ui/profile/ProfileScreen.kt"))
    private val screens = Files.readString(root.resolve("app/src/main/java/com/zycomic/app/ui/profile/JmCheckInScreens.kt"))

    @Test
    fun `makeup batch revalidates account calendar session dates and current month`() {
        assertTrue(repository.contains("makeupMutex.withLock"))
        assertTrue(repository.contains("preview.accountHash != accountHash"))
        assertTrue(repository.contains("preview.month != month"))
        assertTrue(repository.contains("fresh.dailyId != preview.dailyId"))
        assertTrue(repository.contains("requested.any { it !in eligible }"))
        assertTrue(repository.contains("requested.filter { it in locallyCompleted || it in signedOnServer }"))
        assertTrue(repository.contains("YearMonth.from(LocalDate.now()) != month"))
        assertTrue(repository.contains("JmAuthStore.sessionRevision != sessionRevision"))
    }

    @Test
    fun `batch pacing cancellation and non-retryable writes are explicit`() {
        assertTrue(repository.contains("MIN_MAKEUP_INTERVAL_MS = 1_500L"))
        assertTrue(repository.contains("if (remaining > 0) delay(remaining)"))
        assertTrue(viewModel.contains("makeupJob?.cancel()"))
        assertTrue(repository.contains("throw error"))
        val writeBlock = repository.substringAfter("suspend fun submitMakeup(").substringBefore("private suspend fun <T> executeRead")
        assertTrue(writeBlock.contains("A write is never retried automatically"))
        assertTrue(writeBlock.contains("execute(request)"))
    }

    @Test
    fun `closing account-bound screens disposes scope and cancels the active batch`() {
        assertTrue(profile.contains("remember(jmSessionRevision) { JmCheckInViewModel() }"))
        assertTrue(profile.contains("onDispose { jmCheckInViewModel.close() }"))
        assertTrue(profile.contains("showJmCalendar = false"))
        assertTrue(profile.contains("jmSubscreenSessionRevision == jmSessionRevision"))
        assertTrue(viewModel.contains("fun close()"))
        assertTrue(viewModel.contains("scope.cancel()"))
        assertTrue(screens.contains("onDismissRequest = { if (state.makeupLoading) viewModel.cancelMakeup() else closeScreen() }"))
        assertTrue(screens.contains("ScreenHeader(title = \"JM 签到日历\", onBack = ::closeScreen"))
    }

    @Test
    fun `annual artwork fetch submits a year only and does not claim a month`() {
        assertTrue(repository.contains(".post(multipart(\"data\" to year))"))
        assertTrue(repository.contains("fun parseMonthlyAwards(raw: String, requestedYear: String)"))
        assertTrue(repository.contains("internal fun isValidAwardYear"))
        assertTrue(screens.contains("年度奖励图片为只读浏览；此页面不会调用按月份领取奖励的接口。"))
    }
}
