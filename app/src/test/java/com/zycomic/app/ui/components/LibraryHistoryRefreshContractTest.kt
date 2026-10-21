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
    fun `Picacg refresh support stays visible while busy is passed separately`() {
        val screen = source("PicacgLibraryScreen")
        assertTrue(screen.contains("refreshSupported = !history,"))
        assertTrue(screen.contains("refreshBusy = loading || removingSelected,"))
        assertTrue(screen.contains("refreshEnabled = loggedIn,"))
        assertTrue(screen.contains("refreshIndicatorSeparate = true,"))
        assertFalse(screen.contains("refreshSupported = !history && loggedIn && !loading"))
    }

    @Test
    fun `shared toolbar renders the busy indicator inside a disabled refresh action`() {
        val toolbar = source("LibraryHistoryToolbar")
        assertTrue(toolbar.contains("refreshEnabled: Boolean = true,"))
        assertTrue(toolbar.contains("refreshIndicatorSeparate: Boolean = false,"))
        assertTrue(toolbar.contains("enabled = refreshEnabled && !refreshBusy"))
        assertTrue(toolbar.contains("busy = refreshBusy"))
        assertTrue(toolbar.contains("busyIndicatorSeparate = refreshIndicatorSeparate"))
        assertTrue(toolbar.contains("IconButton(onClick = onClick, enabled = enabled"))
        assertTrue(toolbar.contains("if (busy && !busyIndicatorSeparate)"))
        assertTrue(toolbar.contains("if (busy)"))
        assertTrue(toolbar.contains("CircularProgressIndicator("))
    }

    @Test
    fun `Picacg history selection action stays present but cannot enter selection without rows`() {
        val screen = source("PicacgLocalHistoryScreen")
        assertTrue(screen.contains("selectionSupported = true,"))
        assertTrue(screen.contains("selectionEnabled = allRows != null && rows.isNotEmpty(),"))
        assertTrue(screen.contains("onEnterSelection = { if (allRows != null && rows.isNotEmpty()) selectionMode = true },"))
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
