package com.zycomic.app.ui.picacg

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Source contracts complement policy tests when an Android renderer is unavailable. */
class PicacgBrowsePresentationContractTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/zycomic/app/ui/picacg/PicacgBrowseScreen.kt").isFile }
    private val source = File(root, "app/src/main/java/com/zycomic/app/ui/picacg/PicacgBrowseScreen.kt").readText()

    @Test
    fun `ranking starts at 24 hours and does not render internal tab rows`() {
        val initialMode = source.substringAfter("var mode by remember").substringBefore("var filter by remember")
        assertTrue(initialMode.contains("PicacgFeedMode.LEADERBOARD_24H"))
        assertFalse(initialMode.contains("PicacgFeedMode.LATEST"))
        assertFalse(source.contains("LazyRow("))
        assertTrue(source.contains("val displayOnly = pageMode != PicacgBrowsePageMode.CATEGORY"))
    }

    @Test
    fun `category filters reuse Manwa capsules and section typography`() {
        assertTrue(source.contains("import com.zycomic.app.ui.components.FilterChip"))
        assertTrue(source.contains("import com.zycomic.app.ui.components.FilterSection"))
        assertFalse(source.contains("import androidx.compose.material3.FilterChip"))
        assertTrue(source.contains("FilterSection(\"主题\")"))
        assertTrue(source.contains("FilterSection(stringResource(KMR.strings.picacg_sort))"))
    }

    @Test
    fun `display content is width bounded scrollable and still uses shared settings`() {
        val settings = source.substringAfter("DisplaySettingsSection(").substringBefore("modes =")
        assertTrue(settings.contains("modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())"))
        assertTrue(settings.contains("onSetDisplayMode = onSetDisplayMode"))
        assertTrue(settings.contains("onSetGridColumns = onSetGridColumns"))
        val browse = File(root, "app/src/main/java/com/zycomic/app/ui/browse/BrowseScreen.kt").readText()
        assertTrue(browse.contains("onSetDisplayMode = vm::setDisplayMode"))
        assertTrue(browse.contains("onSetGridColumns = { vm.setGridColumns(it, picacgOrientation) }"))
    }
}
