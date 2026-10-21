package com.zycomic.app.ui.profile

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Source contracts cover first-frame UI behavior without requiring Compose or an Android runtime. */
class ProfileUiPresentationContractTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/zycomic/app/ui/profile/ProfileScreen.kt").isFile }

    @Test
    fun `Picacg account and today's sign-in row exist before profile loads`() {
        val source = File(root, "app/src/main/java/com/zycomic/app/ui/picacg/PicacgProfileExtras.kt").readText()
        assertTrue(source.contains("if (user == null)"))
        assertTrue(source.contains("picacg_profile_email, \"资料加载中…\""))
        assertTrue(source.contains("when (user?.isPunched)"))
        assertTrue(source.contains("null -> punchUnknown"))
        assertTrue(source.contains("loadedUser != null && loadedUser.isPunched != true && !busy"))
    }

    @Test
    fun `JM weekly selectors render independently of issue loading`() {
        val source = File(root, "app/src/main/java/com/zycomic/app/ui/browse/BrowseScreen.kt").readText()
        val week = source.substringAfter("JmBrowsePolicy.MODE_WEEK -> {")
            .substringBefore("JmBrowsePolicy.MODE_SERIALIZATION -> {")
        val renderedControls = week.substringBefore("if (weekIssuesError != null) {")
        assertTrue(renderedControls.contains("title = \"期数\""))
        assertTrue(renderedControls.contains("title = \"类型\""))
        assertTrue(week.contains("options = JmWeekTypes"))
        assertTrue(week.contains("weekIssuesLoading -> \"加载中…\""))
    }

    @Test
    fun `Yuzong second-row categories expose a movable position hint only when scrollable`() {
        val source = File(root, "app/src/main/java/com/zycomic/app/platform/YuzongRankContent.kt").readText()
        val row = source.substringAfter("private fun SubChipRow(").substringBefore("@Composable\nprivate fun RankGrid(")
        assertTrue(row.contains("rememberLazyListState()"))
        assertTrue(row.contains("listState.canScrollBackward || listState.canScrollForward"))
        assertTrue(row.contains("19.dp * scrollFraction"))
    }
}
