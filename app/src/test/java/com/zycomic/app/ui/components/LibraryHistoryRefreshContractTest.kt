package com.zycomic.app.ui.components

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Source contracts complement pure policy tests; they do not replace Compose/device tests. */
class LibraryHistoryRefreshContractTest {
    private fun source(name: String): String {
        val path = "src/main/java/com/zycomic/app/ui/components/$name.kt"
        return sequenceOf(File("app/$path"), File(path)).first { it.isFile }.readText()
    }

    @Test
    fun `Picacg favorites keeps refresh action without a separate spinner and disables it while busy`() {
        val screen = source("PicacgLibraryScreen")
        assertTrue(screen.contains("refreshSupported = !history,"))
        assertTrue(screen.contains("refreshBusy = loading || removingSelected,"))
        assertTrue(screen.contains("refreshEnabled = loggedIn,"))
        assertFalse(screen.contains("refreshIndicatorSeparate = true,"))
        assertFalse(screen.contains("refreshEnabledWhileBusy = loading && !removingSelected,"))
        assertTrue(screen.contains("onRefresh = {"))
        assertTrue(screen.contains("loading && comics.isEmpty() -> LoadingScreen("))
        assertFalse(screen.contains("refreshSupported = !history && loggedIn && !loading"))
        val toolbar = source("LibraryHistoryToolbar")
        assertTrue(toolbar.contains("refreshEnabledWhileBusy: Boolean = false,"))
        assertTrue(toolbar.contains("enabled = refreshEnabled && (!refreshBusy || refreshEnabledWhileBusy)"))
    }

    @Test
    fun `shared toolbar can keep refresh clickable while showing a busy indicator`() {
        val toolbar = source("LibraryHistoryToolbar")
        assertTrue(toolbar.contains("refreshEnabled: Boolean = true,"))
        assertTrue(toolbar.contains("refreshIndicatorSeparate: Boolean = false,"))
        assertTrue(toolbar.contains("refreshEnabledWhileBusy: Boolean = false,"))
        assertTrue(toolbar.contains("enabled = refreshEnabled && (!refreshBusy || refreshEnabledWhileBusy)"))
        assertTrue(toolbar.contains("busy = refreshBusy"))
        assertTrue(toolbar.contains("busyIndicatorSeparate = refreshIndicatorSeparate"))
        assertTrue(toolbar.contains("IconButton(onClick = onClick, enabled = enabled"))
        assertTrue(toolbar.contains("if (busy && !busyIndicatorSeparate)"))
        assertTrue(toolbar.contains("if (busy)"))
        assertTrue(toolbar.contains("CircularProgressIndicator("))
    }

    @Test
    fun `Picacg history selection action is enabled before rows arrive and mode survives loading`() {
        val screen = source("PicacgLocalHistoryScreen")
        assertTrue(screen.contains("selectionSupported = true,"))
        assertTrue(screen.contains("selectionEnabled = true,"))
        assertTrue(screen.contains("onEnterSelection = { selectionMode = true },"))
        assertTrue(screen.contains("val allRows by historyFlow.collectAsState(initial = null)"))
        val rowLoadingLifecycle = screen.substringAfter("val allRows by historyFlow.collectAsState(initial = null)")
            .substringBefore("fun exitSelection()")
        assertFalse(rowLoadingLifecycle.contains("selectionMode = false"))
        val toolbar = source("LibraryHistoryToolbar")
        assertTrue(toolbar.contains("selectionEnabled: Boolean = true,"))
        assertTrue(toolbar.contains("enabled = selectionEnabled"))
    }

    @Test
    fun `snapshot is displayed before page one revalidation and retains its paging cursor`() {
        val screen = source("PicacgLibraryScreen")
        val snapshotRead = screen.indexOf("val snapshot = PicacgLibraryCache.get(requestKey)")
        val request = screen.indexOf("val result = PicacgRepository.favourites(")
        assertTrue(snapshotRead >= 0 && snapshotRead < request)
        assertTrue(screen.contains("loadedPage = snapshot.page"))
        assertTrue(screen.contains("page = loadedPage + 1"))
        val failure = screen.substringAfter("catch (failure: Exception)").substringBefore("finally")
        assertFalse(failure.contains("comics = emptyList()"))
        val revalidation = screen.substringAfter("if (requestedPage == 1)").substringBefore("val requestGeneration")
        assertFalse(revalidation.contains("comics = emptyList()"))
    }

    @Test
    fun `Picacg favorites grid and list do not use the Manwa cover URL helper`() {
        val screen = source("PicacgLibraryScreen")
        val card = source("PicacgMangaCard")
        assertTrue(screen.contains("PicacgMangaCard("))
        assertTrue(screen.contains("imageLoader = PicacgImageLoader.get(LocalContext.current)"))
        assertTrue(card.contains("imageLoader = PicacgImageLoader.get(context)"))
        assertFalse(screen.contains("coverUrl("))
        assertFalse(card.contains("model = coverUrl("))
    }
}
