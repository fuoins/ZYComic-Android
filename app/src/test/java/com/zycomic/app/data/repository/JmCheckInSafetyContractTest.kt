package com.zycomic.app.data.repository

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JmCheckInSafetyContractTest {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("app/src/main/java/com/zycomic/app/data/repository/JmCheckInRepository.kt")) }
    private val repository = root.resolve("app/src/main/java/com/zycomic/app/data/repository/JmCheckInRepository.kt").toFile().readText()
    private val viewModel = root.resolve("app/src/main/java/com/zycomic/app/ui/profile/ProfileViewModel.kt").toFile().readText()
    private val profile = root.resolve("app/src/main/java/com/zycomic/app/ui/profile/ProfileScreen.kt").toFile().readText()
    private val screens = root.resolve("app/src/main/java/com/zycomic/app/ui/profile/JmCheckInScreens.kt").toFile().readText()

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
    fun `check-in read retry never retries rate limited HTTP 429`() {
        val readRetryPolicy = repository.substringAfter("private suspend fun <T> executeRead")
            .substringBefore("private fun verifySession")
        assertTrue(readRetryPolicy.contains("""Regex("HTTP 5\\d{2}").containsMatchIn(message)"""))
        assertTrue(!readRetryPolicy.contains("HTTP (429|5"))
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
        assertTrue(screens.contains("ScreenHeader(title = stringResource(KMR.strings.jm_makeup_title), onBack = ::closeScreen"))
        assertTrue(profile.contains("showJmMakeup = false"))
        assertTrue(profile.contains("JmMakeupScreen(jmCheckInViewModel, context, ::closeMakeup)"))
    }

    @Test
    fun `annual artwork fetch submits a year only and does not claim a month`() {
        assertTrue(repository.contains(".post(multipart(\"data\" to year))"))
        assertTrue(repository.contains("fun parseMonthlyAwards(raw: String, requestedYear: String)"))
        assertTrue(repository.contains("internal fun isValidAwardYear"))
        assertTrue(screens.contains("年度奖励图片为只读浏览；此页面不会调用按月份领取奖励的接口。"))
    }

    @Test
    fun `calendar is read only and all past missing dates are frozen before confirm start`() {
        val calendarScreen = screens.substringAfter("internal fun JmCalendarDetailScreen").substringBefore("private data class MakeupConfirmation")
        assertTrue(!calendarScreen.contains("confirmMakeup"))
        assertTrue(!calendarScreen.contains("clickable"))
        assertTrue(calendarScreen.contains("KMR.strings.jm_calendar_legend"))
        assertTrue(calendarScreen.contains("KMR.strings.jm_calendar_activity_marker"))
        val makeupScreen = screens.substringAfter("internal fun JmMakeupScreen").substringBefore("internal fun JmAnnualAwardsScreen")
        assertTrue(makeupScreen.contains("calendar.makeupCandidates(today).map { it.date }.toSet()"))
        assertTrue(makeupScreen.contains("confirmation = MakeupConfirmation(calendar, candidates)"))
        assertTrue(makeupScreen.contains("viewModel.confirmMakeup(context, preview.calendar, preview.dates)"))
        assertTrue(makeupScreen.contains("KMR.strings.jm_makeup_description"))
        assertTrue(makeupScreen.contains("KMR.strings.jm_makeup_start"))
        val load = viewModel.substringAfter("fun loadCalendar()").substringBefore("fun confirmMakeup")
        assertTrue(!load.contains("Repository.makeup"))
    }

    @Test
    fun `award artwork uses JM loader and missing data differs from a failed image`() {
        assertTrue(screens.contains("JmCoverImageLoader.get(context)"))
        assertTrue(screens.contains("imageLoader = imageLoader"))
        assertTrue(screens.contains("onError = { imageFailed = true }"))
        assertTrue(screens.contains("award?.imagePath != null -> stringResource(KMR.strings.jm_award_image_url_failed)"))
        assertTrue(screens.contains("award == null -> stringResource(KMR.strings.jm_award_no_data)"))
        assertTrue(screens.contains("KMR.strings.jm_award_image_load_failed"))
    }
}
