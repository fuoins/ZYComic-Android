package com.zycomic.app.reader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderDirectoryLoaderTest {
    private class TestPageLoader : PageLoader() {
        override var isLocal: Boolean = false
        override suspend fun getPages(): List<ReaderPage> = emptyList()
    }

    @Test
    fun `viewer order cannot release the gate before any manifest has arrived`() = runBlocking {
        val loader = TestPageLoader()
        val page = ReaderPage(0, "page-0", "page-0").apply { status = Page.State.Ready }
        loader.setNextChapterGatePageCount(0)
        loader.updateReadingOrder(visible = listOf(page), successors = emptyList(), chapterOrder = listOf(page))
        val released = CompletableDeferred<Unit>()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            loader.awaitNextChapterImagesAllowed()
            released.complete(Unit)
        }
        assertNull(withTimeoutOrNull(50) { released.await() })
        loader.setChapterPages(listOf(page))
        loader.updateReadingOrder(visible = listOf(page), successors = emptyList(), chapterOrder = listOf(page))
        withTimeout(1_000) { released.await() }
        waiter.cancelAndJoin()
        loader.recycle()
    }

    @Test
    fun `ready temporary tail cannot release until final directory and viewer reaches actual tail`() = runBlocking {
        val loader = TestPageLoader()
        val first = ReaderPage(0, "page-0", "page-0")
        val temporaryTail = ReaderPage(1, "page-1", "page-1").apply { status = Page.State.Ready }
        loader.setChapterPages(listOf(first, temporaryTail), complete = false)
        loader.setNextChapterGatePageCount(1)
        loader.updateReadingOrder(visible = listOf(temporaryTail), successors = emptyList(), chapterOrder = listOf(first, temporaryTail))

        val released = CompletableDeferred<Unit>()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            loader.awaitNextChapterImagesAllowed()
            released.complete(Unit)
        }
        assertNull(withTimeoutOrNull(50) { released.await() })

        val actualTail = ReaderPage(2, "page-2", "page-2").apply { status = Page.State.Ready }
        val finalPages = listOf(first, temporaryTail, actualTail)
        loader.setChapterPages(finalPages, complete = true)
        loader.updateReadingOrder(visible = listOf(temporaryTail), successors = listOf(actualTail), chapterOrder = finalPages)
        assertNull(withTimeoutOrNull(50) { released.await() })

        loader.updateReadingOrder(visible = listOf(actualTail), successors = emptyList(), chapterOrder = finalPages)
        withTimeout(1_000) { released.await() }
        assertTrue(waiter.isCompleted)
        waiter.cancelAndJoin()
    }

    @Test
    fun `zero preload still waits for complete directory and the final page to be ready`() = runBlocking {
        val loader = TestPageLoader()
        val first = ReaderPage(0, "page-0", "page-0").apply { status = Page.State.Ready }
        val last = ReaderPage(1, "page-1", "page-1").apply { status = Page.State.Ready }
        val pages = listOf(first, last)
        loader.setChapterPages(pages, complete = false)
        loader.setNextChapterGatePageCount(0)
        loader.updateReadingOrder(visible = listOf(last), successors = emptyList(), chapterOrder = pages)

        val released = CompletableDeferred<Unit>()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            loader.awaitNextChapterImagesAllowed()
            released.complete(Unit)
        }
        assertNull(withTimeoutOrNull(50) { released.await() })

        loader.setChapterPages(pages, complete = true)
        withTimeout(1_000) { released.await() }
        assertTrue(waiter.isCompleted)
        waiter.cancelAndJoin()
    }

    @Test
    fun `default complete directory preserves non Picacg tail gate behavior`() = runBlocking {
        val loader = TestPageLoader()
        val page = ReaderPage(0, "page-0", "page-0").apply { status = Page.State.Ready }
        loader.setChapterPages(listOf(page))
        loader.setNextChapterGatePageCount(1)
        loader.updateReadingOrder(visible = listOf(page), successors = emptyList(), chapterOrder = listOf(page))

        assertNotNull(withTimeout(1_000) { loader.awaitNextChapterImagesAllowed() })
    }

    @Test
    fun `incomplete ready directory is not silently considered complete`() = runBlocking {
        val loader = TestPageLoader()
        val page = ReaderPage(0, "page-0", "page-0").apply { status = Page.State.Ready }
        loader.setChapterPages(listOf(page), complete = false)
        loader.setNextChapterGatePageCount(1)
        loader.updateReadingOrder(visible = listOf(page), successors = emptyList(), chapterOrder = listOf(page))
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { loader.awaitNextChapterImagesAllowed() }
        delay(50)
        assertFalse(waiter.isCompleted)
        waiter.cancelAndJoin()
    }
}
