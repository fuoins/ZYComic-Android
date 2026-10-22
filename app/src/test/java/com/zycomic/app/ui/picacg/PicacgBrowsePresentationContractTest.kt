package com.zycomic.app.ui.picacg

import org.junit.jupiter.api.Assertions.assertEquals
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
        assertEquals(1, Regex("LazyRow\\(").findAll(source).count())
        val rankingRow = source.substringAfter("internal fun PicacgRankingChips(").substringBefore("fun PicacgBrowseScreen(")
        assertTrue(rankingRow.contains("PicacgFeedMode.LEADERBOARD_24H"))
        assertTrue(rankingRow.contains("PicacgFeedMode.LEADERBOARD_7D"))
        assertTrue(rankingRow.contains("PicacgFeedMode.LEADERBOARD_30D"))
        assertTrue(rankingRow.contains("PicacgFeedMode.RANDOM"))
        assertTrue(rankingRow.contains("PicacgFeedMode.LATEST"))
        assertTrue(rankingRow.contains("\"日榜\""))
        assertTrue(rankingRow.contains("\"周榜\""))
        assertTrue(rankingRow.contains("\"月榜\""))
        val rankingRowPosition = source.indexOf("if (pageMode == PicacgBrowsePageMode.RANKING)")
        val loginPromptPosition = source.indexOf("if (!loggedIn) {\n            Text(stringResource(KMR.strings.picacg_session_required))")
        assertTrue(rankingRowPosition < loginPromptPosition)
        assertTrue(rankingRowPosition < source.indexOf("if (loading && mangas.isEmpty() && !hasSnapshot)"))
        assertTrue(source.contains("val displayOnly = pageMode != PicacgBrowsePageMode.CATEGORY"))
    }

    @Test
    fun `cached snapshot is restored synchronously and even an empty snapshot suppresses skeleton`() {
        val snapshot = source.indexOf("val initialSnapshot = remember(queryIdentity)")
        val effect = source.indexOf("LaunchedEffect(queryIdentity, page, refresh, browseCacheRevision)")
        assertTrue(snapshot >= 0 && snapshot < effect)
        assertTrue(source.contains("var pageSnapshots by remember(queryIdentity) { mutableStateOf(mapOf(1 to initialSnapshot?.items.orEmpty())) }"))
        assertTrue(source.contains("var hasSnapshot by remember(queryIdentity) { mutableStateOf(initialSnapshot != null) }"))
        assertTrue(source.contains("if (loading && mangas.isEmpty() && !hasSnapshot)"))
        assertTrue(source.contains("if ((!loading || hasSnapshot) && error == null && mangas.isEmpty())"))
    }

    @Test
    fun `query identity fences every cache dimension and random remains uncached`() {
        val identity = source.substringAfter("val queryIdentity = ").substringBefore("\n")
        for (dimension in listOf("session", "loggedIn", "tabId", "initialFilter", "pageMode", "mode", "currentFilter", "condition", "routeState.revision", "keywordRevision", "blockKeywords")) {
            assertTrue(identity.contains(dimension), "query identity is missing $dimension")
        }
        assertTrue(source.contains("PicacgFeedMode.RANDOM -> null"))
        assertTrue(source.contains("pageItems = if (page == 1) PicacgRepository.random() else emptyList()"))
    }

    @Test
    fun `failed refresh retains page snapshots and late results cannot overwrite current query`() {
        assertTrue(source.contains("val currentRequest by rememberUpdatedState(queryIdentity to page)"))
        assertTrue(source.contains("currentRequest == requestIdentity && page == requestedPage && refresh == requestedRefresh"))
        assertTrue(source.contains("PicacgRepository.browseConditionIsCurrent(condition)"))
        assertTrue(source.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(source.contains("if (requestIsCurrent()) {\n                pageSnapshots = if (requestedPage == 1)"))
        val failure = source.substringAfter("} catch (failure: Exception) {").substringBefore("} finally {")
        assertTrue(failure.contains("if (requestIsCurrent()) error = failure.message ?: loadFailed"))
        assertFalse(failure.contains("pageSnapshots ="))
        assertFalse(failure.contains("mangas ="))
        assertTrue(source.contains("if (requestIsCurrent()) loading = false"))
    }

    @Test
    fun `page reload replaces that page snapshot while later pages remain distinct`() {
        assertTrue(source.contains("pageSnapshots = if (requestedPage == 1) {\n                    mapOf(1 to pageItems)"))
        assertTrue(source.contains("pageSnapshots + (requestedPage to pageItems)"))
        assertTrue(source.contains("pageSnapshots.filterKeys { it <= page }.toSortedMap()"))
    }

    @Test
    fun `pager preview renders only static ranking controls before tab selection settles`() {
        val browse = File(root, "app/src/main/java/com/zycomic/app/ui/browse/BrowseScreen.kt").readText()
        val picacgPage = browse.substringAfter("if (tabPlatform == ContentPlatform.PICACG)").substringBefore("if (item?.kind == TabKind.SPECIAL")
        val preview = picacgPage.substringAfter("} else if (pageMode == com.zycomic.app.ui.picacg.PicacgBrowsePageMode.RANKING)")
        assertTrue(picacgPage.contains("if (page == vm.mainTab.value) {\n            com.zycomic.app.ui.picacg.PicacgBrowseScreen("))
        assertTrue(preview.contains("com.zycomic.app.ui.picacg.PicacgRankingChips()"))
        assertFalse(preview.contains("PicacgBrowseScreen("))
        assertFalse(preview.contains("LaunchedEffect"))
        assertFalse(preview.contains("Repository"))
        assertTrue(browse.contains("snapshotFlow { pagerState.settledPage }"))
        assertTrue(browse.contains("pagerState.animateScrollToPage(index)\n                                            vm.selectMainTab(index)"))
        assertTrue(picacgPage.contains("BrowsePageType.PICACG_CATEGORY -> com.zycomic.app.ui.picacg.PicacgBrowsePageMode.CATEGORY"))
        assertTrue(picacgPage.contains("item?.kind == TabKind.FILTER && item.id != BrowseTabStore.ID_PICACG_HOME"))
    }

    @Test
    fun `shared ranking controls have no data effects and preserve order and default`() {
        val rankingRow = source.substringAfter("internal fun PicacgRankingChips(").substringBefore("fun PicacgBrowseScreen(")
        val modes = rankingRow.substringAfter("items = listOf(").substringBefore("),")
        assertEquals(
            listOf("LEADERBOARD_24H", "LEADERBOARD_7D", "LEADERBOARD_30D", "RANDOM", "LATEST"),
            Regex("PicacgFeedMode\\.(\\w+)").findAll(modes).map { it.groupValues[1] }.toList(),
        )
        assertTrue(rankingRow.contains("selectedMode: PicacgFeedMode = PicacgFeedMode.LEADERBOARD_24H"))
        assertTrue(rankingRow.contains("onSelectMode: (PicacgFeedMode) -> Unit = {}"))
        assertTrue(rankingRow.contains("CompactBrowseFilterChip("))
        assertFalse(rankingRow.contains("LaunchedEffect"))
        assertFalse(rankingRow.contains("Repository"))
        assertTrue(source.contains("LEADERBOARD_24H(\"H24\")"))
        assertTrue(source.contains("LEADERBOARD_7D(\"D7\")"))
        assertTrue(source.contains("LEADERBOARD_30D(\"D30\")"))
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
