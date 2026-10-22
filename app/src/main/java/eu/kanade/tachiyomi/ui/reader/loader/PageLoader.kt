package eu.kanade.tachiyomi.ui.reader.loader

import androidx.annotation.CallSuper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    @Volatile
    private var chapterPages: List<ReaderPage> = emptyList()

    @Volatile
    private var nextChapterGatePageCount = 0

    private val chapterReadingOrder = MutableStateFlow(ChapterReadingOrder(emptyList(), emptySet(), false, false))

    @Volatile
    private var imageLoadingBarrierChapter: ReaderChapter? = null

    @Volatile
    private var imageLoadingBarrierJob: Job? = null

    @Volatile
    private var imageLoadingBarrierReleased = false

    private val imageLoadingBarrierScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Whether this loader has been already recycled.
     */
    @Volatile
    var isRecycled = false
        private set

    abstract var isLocal: Boolean

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /** Optional reader-only snapshots; ordinary loaders retain their full-list behavior. */
    open suspend fun getPages(onPages: suspend (List<ReaderPage>, Boolean) -> Unit): List<ReaderPage> = getPages()

    private val directoryComplete = MutableStateFlow(false)

    /** Records the page list used to determine the chapter's final preloading window. */
    fun setChapterPages(pages: List<ReaderPage>, complete: Boolean = true) {
        val changed = chapterPages != pages
        chapterPages = pages
        if (changed || chapterReadingOrder.value.pages.isEmpty()) {
            val visible = chapterReadingOrder.value.visiblePageIndices
            chapterReadingOrder.value = ChapterReadingOrder(pages, visible, false, false)
        }
        directoryComplete.value = complete
    }

    fun setNextChapterGatePageCount(count: Int) {
        nextChapterGatePageCount = count.coerceIn(0, 10)
        val order = chapterReadingOrder.value
        chapterReadingOrder.value = order.copy(
            tailHasBeenReached = order.tailHasBeenReached ||
                (order.viewerOrderKnown && hasVisiblePageInTail(order, nextChapterGatePageCount)),
        )
    }

    /** Delays next-chapter image requests until the current chapter's tail window is ready. */
    fun setImageLoadingBarrier(predecessor: ReaderChapter?) {
        if (predecessor == null || imageLoadingBarrierChapter === predecessor || imageLoadingBarrierReleased || isRecycled) return
        synchronized(this) {
            if (imageLoadingBarrierChapter != null || imageLoadingBarrierReleased || isRecycled) return
            val predecessorLoader = predecessor.pageLoader ?: return
            predecessor.ref()
            imageLoadingBarrierChapter = predecessor
            val referenceReleased = AtomicBoolean(false)
            val releaseReference = { if (referenceReleased.compareAndSet(false, true)) predecessor.unref() }
            val barrierJob = imageLoadingBarrierScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    predecessorLoader.awaitNextChapterImagesAllowed()
                } finally {
                    releaseReference()
                }
            }
            imageLoadingBarrierJob = barrierJob
            barrierJob.invokeOnCompletion { releaseReference() }
        }
    }

    /** Releases any predecessor-tail wait when this chapter becomes the active reader chapter. */
    @Synchronized
    fun releaseImageLoadingBarrier() {
        imageLoadingBarrierReleased = true
        imageLoadingBarrierJob?.cancel()
        imageLoadingBarrierJob = null
    }

    /** Waits for the current page to enter the preloaded tail and for that tail to settle. */
    suspend fun awaitNextChapterImagesAllowed() {
        directoryComplete.first { it }
        val preloadCount = nextChapterGatePageCount
        if (preloadCount == 0) {
            val order = chapterReadingOrder.first { it.viewerOrderKnown }
            val lastPage = order.pages.lastOrNull() ?: return
            lastPage.statusFlow.first { it == Page.State.Ready }
            return
        }

        while (true) {
            val order = chapterReadingOrder.first { readingOrder ->
                readingOrder.viewerOrderKnown &&
                    (readingOrder.tailHasBeenReached || hasVisiblePageInTail(readingOrder, preloadCount))
            }
            val tail = order.pages.takeLast(preloadCount)
            tail.forEach { page ->
                if (!page.isImageLoadTerminal()) {
                    page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                }
            }

            val latestOrder = chapterReadingOrder.value
            val latestTail = latestOrder.pages.takeLast(preloadCount)
            if (latestOrder.tailHasBeenReached && latestTail.isNotEmpty() && latestTail.all { it.isImageLoadTerminal() }) return
        }
    }

    open fun updateReadingOrder(
        visible: List<ReaderPage>,
        successors: List<ReaderPage>,
        chapterOrder: List<ReaderPage> = emptyList(),
    ) {
        val normalizedVisible = visible.filterNot { it is InsertPage }.distinctBy { it.index }
        val previousOrder = chapterReadingOrder.value
        val currentOrder = previousOrder.pages
        val normalizedOrder = if (chapterOrder.isNotEmpty()) {
            chapterOrder.filterNot { it is InsertPage }.distinctBy { it.index }
        } else if (normalizedVisible.isEmpty() && successors.isEmpty() && currentOrder.isNotEmpty()) {
            currentOrder
        } else {
            (normalizedVisible + successors + chapterPages).filterNot { it is InsertPage }.distinctBy { it.index }
        }
        val order = ChapterReadingOrder(
            normalizedOrder,
            normalizedVisible.map { it.index }.toSet(),
            previousOrder.tailHasBeenReached,
            previousOrder.viewerOrderKnown || chapterOrder.isNotEmpty(),
        )
        chapterReadingOrder.value = order.copy(
            tailHasBeenReached = order.tailHasBeenReached ||
                (order.viewerOrderKnown && hasVisiblePageInTail(order, nextChapterGatePageCount)),
        )
    }

    private fun hasVisiblePageInTail(order: ChapterReadingOrder, count: Int): Boolean {
        if (count <= 0) return false
        val tail = order.pages.takeLast(count)
        return tail.isNotEmpty() && order.visiblePageIndices.any { visibleIndex -> tail.any { it.index == visibleIndex } }
    }

    private data class ChapterReadingOrder(
        val pages: List<ReaderPage>,
        val visiblePageIndices: Set<Int>,
        val tailHasBeenReached: Boolean,
        val viewerOrderKnown: Boolean,
    )

    /** Called only when a chapter is actually selected, not when its pages are merely preloaded. */
    open fun activateForReading() {
        releaseImageLoadingBarrier()
    }

    /** Stops new work for a chapter while preserving its pages and cache for a later return. */
    open fun deactivateForReading() {}

    /** Common holder entry point; the barrier is awaited before any loader-specific image work. */
    suspend fun loadPageWhenAllowed(page: ReaderPage) {
        awaitImageLoadingBarrier()
        loadPage(page)
    }

    /** Image workers must await this immediately before starting a network image request. */
    protected suspend fun awaitImageLoadingBarrier() {
        imageLoadingBarrierJob?.join()
    }

    /**
     * Loads the page. May also preload other pages.
     * Progress of the page loading should be followed via [page.statusFlow].
     * [loadPage] is not currently guaranteed to complete, so it should be launched asynchronously.
     */
    open suspend fun loadPage(page: ReaderPage) {}

    /** Called when the decoder rejects a downloaded image. */
    open fun invalidatePage(page: ReaderPage): Boolean = false

    /** Called after successful decode so URL-scoped decode retry state can be cleared. */
    open fun markImageDecodeSuccess(page: ReaderPage) {}

    /**
     * Retries the given [page] in case it failed to load. This method only makes sense when an
     * online source is used.
     */
    open fun retryPage(page: ReaderPage) {}

    /** Requeues a still-visible page after a coordinated runtime source switch, without a manual retry. */
    open fun retryAfterRuntimeRecovery(page: ReaderPage, expectedGeneration: Int) {}

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    @Synchronized
    open fun recycle() {
        isRecycled = true
        releaseImageLoadingBarrier()
        imageLoadingBarrierScope.cancel()
    }

    private fun ReaderPage.isImageLoadTerminal(): Boolean =
        status == Page.State.Ready || status is Page.State.Error
}
