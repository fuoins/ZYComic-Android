package eu.kanade.tachiyomi.ui.reader.loader

import com.zycomic.app.net.ImageAttemptCoordinator
import com.zycomic.app.net.ImageCacheManager
import com.zycomic.app.net.ImageCancelReason
import com.zycomic.app.net.ImageDecryptFailedException
import com.zycomic.app.net.ImageAttemptDeferredException
import com.zycomic.app.net.PageAttemptScope
import com.zycomic.app.net.RouteManager
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPageLoadStatus
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.source.isEhBasedSource
import exh.util.DataSaver
import exh.util.DataSaver.Companion.getImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Loads HTTP chapters with viewer-owned ordering, bounded attempts and generation-safe cancellation. */
internal class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = Injekt.get(),
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val coordinator: ImageAttemptCoordinator,
) : PageLoader() {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = java.util.concurrent.PriorityBlockingQueue<PriorityPage>()
    private val preloadSize = readerPreferences.preloadSize().get().coerceAtLeast(0)
    private val readerThreads = readerPreferences.readerThreads().get().coerceIn(1, 4)
    private val nearForegroundSize = min(3, preloadSize)
    private val preloadBatchSize = min(3, preloadSize).coerceAtLeast(1)
    private val dataSaver = DataSaver(source, sourcePreferences)
    private val activeScopes = ConcurrentHashMap<ReaderPage, PageAttemptScope>()
    private val pendingDisposition = ConcurrentHashMap<ReaderPage, ImageCancelReason>()
    private val terminalErrors = ConcurrentHashMap<ReaderPage, Throwable>()
    private val decodeFailures = ConcurrentHashMap<String, AtomicInteger>()
    private val successfullyCachedPages = java.util.Collections.newSetFromMap(ConcurrentHashMap<ReaderPage, Boolean>())
    private val queueLock = Any()
    private val preloadLock = Any()

    @Volatile private var visiblePages: List<ReaderPage> = emptyList()
    @Volatile private var successors: List<ReaderPage> = emptyList()
    @Volatile private var currentWindow: Set<ReaderPage> = emptySet()
    @Volatile private var readingRanks: Map<ReaderPage, Int> = emptyMap()
    @Volatile private var readingOrderInitialized = false
    @Volatile private var chapterPageOrder: List<ReaderPage> = emptyList()
    @Volatile private var preloadStartIndex = 0
    @Volatile private var preloadCursor = 0
    @Volatile private var preloadBatch: List<ReaderPage> = emptyList()

    init {
        repeat(readerThreads) {
            workerScope.launchIO {
                while (true) {
                    val entry = runInterruptible { queue.take() }
                    val page = entry.page
                    val shouldLoad = synchronized(queueLock) {
                        synchronized(page) {
                            val ownsEntry = page.queuedToken.get() == entry.generation
                            if (ownsEntry) page.queuedToken.compareAndSet(entry.generation, -1)
                            ownsEntry && entry.generation == page.generation.get() &&
                                page.status == Page.State.Queue && readingOrderInitialized && page in currentWindow
                        }
                    }
                    if (!shouldLoad) continue
                    internalLoadPage(page)
                }
            }
        }
    }

    override var isLocal: Boolean = false

    override suspend fun getPages(): List<ReaderPage> {
        val pages = try {
            if (RouteManager.useFastestImgForAll) {
                source.getPageList(chapter.chapter)
            } else {
                chapterCache.getPageListFromCache(chapter.chapter.toDomainChapter()!!)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            source.getPageList(chapter.chapter)
        }
        return pages.mapIndexed { index, page -> ReaderPage(index, page.url, page.imageUrl) }
    }

    override suspend fun loadPage(page: ReaderPage) = withIOContext {
        if (!readingOrderInitialized) return@withIOContext
        if (page.status == Page.State.Ready && page.imageUrl != null && !chapterCache.isImageInCache(page.imageUrl!!)) {
            successfullyCachedPages.remove(page)
            page.status = Page.State.Queue
        }
        if (page.status == Page.State.Queue && (currentWindow.isEmpty() || page in currentWindow)) {
            if (page in visiblePages) maybePreemptForVisiblePage()
            enqueue(page, priorityFor(page))
        }
    }

    /** The viewer is the authority for visible pages and real reading order. */
    override fun updateReadingOrder(visible: List<ReaderPage>, successors: List<ReaderPage>) {
        val normalizedVisible = visible.asSequence()
            .filterNot { it is InsertPage }
            .distinctBy { it.index }
            .toList()
        val normalizedSuccessors = successors.asSequence()
            .filterNot { it is InsertPage }
            .distinctBy { it.index }
            .toList()
        val oldVisible = visiblePages.toSet()
        val newVisible = normalizedVisible.toSet()
        (oldVisible - newVisible).forEach { it.readerVisible = false }
        (newVisible - oldVisible).forEach { it.readerVisible = true }
        visiblePages = normalizedVisible
        this.successors = normalizedSuccessors
        activeScopes.values.forEach { it.updateVisibility(it.page in normalizedVisible) }
        readingRanks = normalizedSuccessors.withIndex().associate { it.value to it.index }

        val batch = synchronized(preloadLock) {
            if (!readingOrderInitialized) {
                val allChapterPages = chapter.pages.orEmpty()
                    .filterNot { it is InsertPage }
                    .distinctBy { it.index }
                chapterPageOrder = allChapterPages.ifEmpty {
                    (normalizedVisible + normalizedSuccessors).distinctBy { it.index }
                }
                val visiblePositions = normalizedVisible.mapNotNull(::chapterPagePosition)
                val firstVisiblePosition = visiblePositions.minOrNull()
                val lastVisiblePosition = visiblePositions.maxOrNull()
                val firstSuccessorPosition = normalizedSuccessors.firstOrNull()?.let(::chapterPagePosition)
                preloadStartIndex = (firstVisiblePosition ?: firstSuccessorPosition ?: 0)
                    .coerceIn(0, chapterPageOrder.size)
                preloadCursor = (lastVisiblePosition?.plus(1) ?: preloadStartIndex)
                    .coerceIn(preloadStartIndex, chapterPageOrder.size)
                readingOrderInitialized = true
            }
            if (preloadBatch.isEmpty()) preloadBatch = takeNextPreloadBatchLocked()
            preloadBatch
        }
        val newWindow = (normalizedVisible + batch).toSet()

        // Upgrade any still-queued successor that just entered the viewport, preserving viewer order.
        synchronized(queueLock) {
            currentWindow = newWindow
            val newlyVisibleQueueEntries = queueSnapshot().filter { it.page in newVisible }
            newlyVisibleQueueEntries.forEach { entry ->
                if (queue.remove(entry)) {
                    synchronized(entry.page) {
                        if (entry.page.queuedToken.get() == entry.generation) entry.page.queuedToken.set(-1)
                    }
                }
            }
            queueSnapshot().filter { it.page !in newWindow }.forEach { entry ->
                if (queue.remove(entry)) {
                    synchronized(entry.page) {
                        if (entry.page.queuedToken.get() == entry.generation) entry.page.queuedToken.set(-1)
                    }
                    entry.page.slow = false
                }
            }
        }

        // Leaving the visible page plus the current three-page batch is the only visibility-based abandon.
        activeScopes.keys.filter { it !in newWindow }.forEach { cancelPage(it, ImageCancelReason.PAGE_HIDDEN, requeue = false) }
        (normalizedVisible + batch).distinctBy { it.index }.forEach(::enqueueIfLoadable)
        advancePreloadBatchIfReady()
    }

    private fun priorityFor(page: ReaderPage): Int = if (page in visiblePages) 1 else 0

    private fun queueSnapshot(): List<PriorityPage> =
        queue.toArray(emptyArray<PriorityPage>()).asList()

    private fun enqueue(page: ReaderPage, priority: Int) {
        synchronized(queueLock) {
            if (!readingOrderInitialized || page !in currentWindow || page.status != Page.State.Queue) return
            synchronized(page) {
                val generation = page.generation.get()
                if (page.queuedToken.get() == generation) return
                page.queuedToken.set(generation)
                queue.offer(PriorityPage(page, priority, generation))
            }
        }
    }

    private fun chapterPagePosition(page: ReaderPage): Int =
        chapterPageOrder.indexOfFirst { it === page || it.index == page.index }
            .takeIf { it >= 0 } ?: -1

    private fun takeNextPreloadBatchLocked(): List<ReaderPage> {
        if (preloadCursor >= chapterPageOrder.size) return emptyList()
        val end = (preloadCursor + preloadBatchSize).coerceAtMost(chapterPageOrder.size)
        return chapterPageOrder.subList(preloadCursor, end).also { preloadCursor = end }.toList()
    }

    private fun isSuccessfullyCached(page: ReaderPage): Boolean {
        if (page in successfullyCachedPages) return true
        val imageUrl = page.imageUrl ?: return false
        return page.status == Page.State.Ready && chapterCache.isImageInCache(imageUrl)
    }

    private fun hasFailedPredecessor(page: ReaderPage): Boolean {
        val pagePosition = chapterPagePosition(page)
        if (pagePosition < 0 || pagePosition <= preloadStartIndex) return false
        return chapterPageOrder
            .subList(preloadStartIndex, pagePosition)
            .any { it.status is Page.State.Error }
    }

    private fun enqueueIfLoadable(page: ReaderPage) {
        if (page.status == Page.State.Ready && page.imageUrl != null && !chapterCache.isImageInCache(page.imageUrl!!)) {
            successfullyCachedPages.remove(page)
            page.status = Page.State.Queue
        }
        if (page !in visiblePages && hasFailedPredecessor(page)) return
        if (page.status == Page.State.Queue) {
            if (page in visiblePages) maybePreemptForVisiblePage()
            enqueue(page, priorityFor(page))
        }
    }

    /** Start another bounded batch only when every page in this batch completed and was cached. */
    private fun advancePreloadBatchIfReady() {
        while (true) {
            val nextBatch = synchronized(preloadLock) {
                val completedBatch = preloadBatch
                if (completedBatch.isEmpty() || completedBatch.any { !isSuccessfullyCached(it) }) return
                val completedBatchStart = preloadCursor - completedBatch.size
                if (chapterPageOrder.subList(preloadStartIndex, completedBatchStart.coerceAtLeast(preloadStartIndex))
                        .any { it.status is Page.State.Error }
                ) return
                preloadBatch = takeNextPreloadBatchLocked()
                preloadBatch
            }
            if (nextBatch.isEmpty()) return
            val newWindow = (visiblePages + nextBatch).toSet()
            synchronized(queueLock) { currentWindow = newWindow }
            nextBatch.forEach(::enqueueIfLoadable)
            // If already-loaded visible pages satisfy the next batch, advance without recursion.
            if (nextBatch.any { !isSuccessfullyCached(it) }) return
        }
    }

    private fun onPageLoadFinished(page: ReaderPage) {
        if (page.status == Page.State.Ready) {
            // A foreground failure may have held queued successors; a successful manual retry resumes them.
            (visiblePages + preloadBatch).distinctBy { it.index }.forEach(::enqueueIfLoadable)
        }
        advancePreloadBatchIfReady()
    }

    /** Cancel exactly one active far-foreground attempt; queued items are not worker capacity. */
    private fun maybePreemptForVisiblePage() {
        if (activeScopes.size < readerThreads) return
        val visible = visiblePages.toSet()
        val nearForeground = successors.take(nearForegroundSize).toSet()
        val anchorPosition = visiblePages.lastOrNull()
            ?.let(::chapterPagePosition)
            ?.takeIf { it >= 0 }
        val target = activeScopes.values.asSequence()
            .map { it.page }
            .filter { it !in visible && it !in nearForeground && it in currentWindow }
            .filter { it.activeAttempt.get()?.let { attempt -> attempt.isCurrent && attempt.hasOuterCall } == true }
            .mapNotNull { page ->
                val position = chapterPagePosition(page)
                val distance = if (position >= 0 && anchorPosition != null) {
                    kotlin.math.abs(position - anchorPosition)
                } else {
                    readingRanks[page]?.plus(1)
                }
                distance?.let { page to it }
            }
            .maxByOrNull { it.second }
            ?.first
            ?: return // No active invisible request can be classified as far; leave all current work untouched.
        // cancelPage records ABANDON_FAR; completion re-enqueues this invisible page at priority 0.
        cancelPage(target, ImageCancelReason.ABANDON_FAR, requeue = true)
    }

    private fun cancelPage(page: ReaderPage, reason: ImageCancelReason, requeue: Boolean): Boolean {
        val attempt = synchronized(page) {
            val current = page.activeAttempt.get() ?: return false
            if (page.generation.get() != current.generation) return false
            page.generation.incrementAndGet() // invalidate stale writes before cancelling Calls
            page.activeAttempt.set(null)
            if (requeue) pendingDisposition[page] = reason else pendingDisposition[page] = ImageCancelReason.PAGE_HIDDEN
            current
        }
        attempt.cancel(reason)
        page.raceActive = false
        page.progress = 0
        logcat { "Image attempt cancelled: page=${page.index}, reason=$reason" }
        return true
    }

    /** Preserve the EHentai reader's immediate-load action using the current viewer-owned queue. */
    fun boostPage(page: ReaderPage) {
        if (page.status == Page.State.Queue) {
            workerScope.launchIO { loadPage(page) }
        }
    }

    override fun retryPage(page: ReaderPage) {
        successfullyCachedPages.remove(page)
        val sourceKey = RouteManager.imgRewriteTarget ?: RouteManager.imgHost
        if (page.retryBudgetSource != sourceKey) {
            page.retryBudgetSource = sourceKey
            page.manualRetryCount.set(0)
            page.imageUrl?.let(decodeFailures::remove)
        }
        if (page.manualRetryCount.get() >= 3) {
            page.slow = false
            page.loadStatus = ReaderPageLoadStatus.Idle
            page.status = Page.State.Error(IOException("已重试 3 次，请重新测速或切换图源"))
            return
        }
        page.manualRetryCount.incrementAndGet()
        val wasActive = cancelPage(page, ImageCancelReason.USER_RETRY, requeue = true)
        val imageUrl = page.imageUrl
        if (!imageUrl.isNullOrBlank()) {
            chapterCache.remove(imageUrl)
            ImageCacheManager.remove(imageUrl)
        }
        page.stream = null
        page.slow = false
        page.loadStatus = ReaderPageLoadStatus.Queued
        page.progress = 0
        if (wasActive) return
        synchronized(page) { page.generation.incrementAndGet() }
        if (source.isEhBasedSource()) page.imageUrl = null
        page.status = Page.State.Queue
        enqueue(page, priorityFor(page))
    }

    override fun invalidatePage(page: ReaderPage): Boolean {
        val imageUrl = page.imageUrl ?: return false
        successfullyCachedPages.remove(page)
        if (page.status != Page.State.Ready) {
            return page.status == Page.State.Queue && decodeFailures[imageUrl]?.get() == 1
        }
        val count = decodeFailures.getOrPut(imageUrl) { AtomicInteger() }.incrementAndGet()
        if (count >= 2) terminalErrors[page] = ImageDecryptFailedException()
        val wasActive = cancelPage(page, ImageCancelReason.USER_RETRY, requeue = count < 2)
        if (!wasActive) synchronized(page) { page.generation.incrementAndGet() }
        chapterCache.remove(imageUrl)
        ImageCacheManager.remove(imageUrl)
        page.stream = null
        if (count >= 2) {
            if (!wasActive) {
                page.slow = false
                page.loadStatus = ReaderPageLoadStatus.Idle
                page.status = Page.State.Error(terminalErrors.remove(page) ?: ImageDecryptFailedException())
            }
            return false
        }
        if (!wasActive) {
            page.status = Page.State.Queue
            page.slow = false
            page.loadStatus = ReaderPageLoadStatus.Queued
            page.progress = 0
            enqueue(page, priorityFor(page))
        }
        return true
    }

    override fun markImageDecodeSuccess(page: ReaderPage) {
        page.imageUrl?.let(decodeFailures::remove)
        page.manualRetryCount.set(0)
        page.retryBudgetSource = null
    }

    override fun recycle() {
        super.recycle()
        visiblePages.forEach { it.readerVisible = false }
        activeScopes.keys.toList().forEach { cancelPage(it, ImageCancelReason.PAGE_HIDDEN, requeue = false) }
        workerScope.cancel()
        queue.clear()
        chapter.pages?.let { pages ->
            launchIO {
                try {
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(chapter.chapter.toDomainChapter()!!, pagesToSave)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                }
            }
        }
    }

    private suspend fun internalLoadPage(page: ReaderPage) {
        if (!readingOrderInitialized || page !in currentWindow) return
        if (visiblePages.firstOrNull()?.let { it !== page } == true) {
            val homePage = visiblePages.first()
            val homeScope = homePage.activeAttempt.get()
            val homeStarted = homeScope?.hasOuterCall == true || homePage.status == Page.State.Ready || homePage.status is Page.State.Error
            if (homePage.status is Page.State.Error) {
                page.loadStatus = ReaderPageLoadStatus.Queued
                return
            }
            if (!homeStarted || coordinator.availablePrimaryAttemptCount <= 0) {
                page.loadStatus = ReaderPageLoadStatus.WaitingForRequest
                runCatching {
                    coordinator.watchdog.schedule({
                        if (!isRecycled && page.status == Page.State.Queue && page in currentWindow) {
                            enqueue(page, priorityFor(page))
                        }
                    }, 100, TimeUnit.MILLISECONDS)
                }
                return
            }
        }
        val myGeneration = synchronized(queueLock) {
            if (!readingOrderInitialized || page !in currentWindow) return
            synchronized(page) { page.generation.incrementAndGet() }
        }
        var attempt: PageAttemptScope? = null
        var slowWatch: java.util.concurrent.ScheduledFuture<*>? = null
        var deferRetry = false
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                if (!owns(page, myGeneration)) return
                page.status = Page.State.LoadPage
                page.loadStatus = ReaderPageLoadStatus.Requesting
                page.imageUrl = source.getImageUrl(page)
            }
            val imageUrl = page.imageUrl ?: throw IOException("图片 URL 为空")
            if (!chapterCache.isImageInCache(imageUrl)) {
                if (!owns(page, myGeneration)) return
                val currentAttempt = synchronized(queueLock) {
                    if (!readingOrderInitialized || page !in currentWindow || !owns(page, myGeneration)) {
                        null
                    } else {
                        page.status = Page.State.DownloadImage
                        page.loadStatus = ReaderPageLoadStatus.WaitingForRequest
                        page.progress = 0
                        PageAttemptScope(
                            page = page,
                            generation = myGeneration,
                            coordinator = coordinator,
                            canRace = page in visiblePages,
                            isSecondaryVisible = page in visiblePages && visiblePages.firstOrNull() !== page,
                        ).also { scope ->
                            page.activeAttempt.set(scope)
                            activeScopes[page] = scope
                            scope.updateVisibility(page in visiblePages)
                        }
                    }
                }
                if (currentAttempt == null) {
                    if (owns(page, myGeneration)) {
                        page.status = Page.State.Queue
                        page.loadStatus = ReaderPageLoadStatus.Queued
                    }
                    return
                }
                attempt = currentAttempt
                slowWatch = coordinator.watchdog.scheduleAtFixedRate({
                    val isNear = page in visiblePages || successors.take(nearForegroundSize).any { it === page }
                    val requestStartedAt = currentAttempt.requestStartedAtNanos.get()
                    val quietFor = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - currentAttempt.lastProgressAtNanos.get())
                    if (currentAttempt.isCurrent && currentAttempt.hasActiveEndpointRequest && requestStartedAt > 0L && isNear && quietFor >= 1_500L) {
                        page.slow = true
                    }
                }, 250, 250, TimeUnit.MILLISECONDS)

                val response = source.getImage(page, dataSaver)
                if (!owns(page, myGeneration) || !currentAttempt.isCurrent) {
                    response.close()
                    return
                }
                chapterCache.putImageToCache(imageUrl, response) {
                    owns(page, myGeneration) && page.activeAttempt.get() === currentAttempt
                }
            }
            if (!owns(page, myGeneration)) return
            val cachedImage = chapterCache.getImageFile(imageUrl)
            if (!chapterCache.isImageInCache(imageUrl) || !validateCachedImage(cachedImage)) {
                throw IOException("完整图片未成功写入章节缓存")
            }
            successfullyCachedPages.add(page)
            page.stream = { cachedImage.inputStream() }
            page.slow = false
            page.loadStatus = ReaderPageLoadStatus.Idle
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            if (!owns(page, myGeneration)) return // stale cancellation never publishes Error
            if (e is CancellationException) throw e
            var cause: Throwable? = e
            var deferred = false
            while (cause != null) {
                if (cause is ImageAttemptDeferredException) {
                    deferred = true
                    break
                }
                cause = cause.cause
            }
            if (deferred) {
                page.status = Page.State.Queue
                page.loadStatus = ReaderPageLoadStatus.Queued
                deferRetry = true
            } else {
                page.slow = false
                page.loadStatus = ReaderPageLoadStatus.Idle
                page.status = Page.State.Error(e)
            }
        } finally {
            slowWatch?.cancel(false)
            attempt?.let { current ->
                current.endRace()
                page.activeAttempt.compareAndSet(current, null)
                activeScopes.remove(page, current)
                val disposition = pendingDisposition.remove(page)
                if (disposition != null) {
                    page.stream = null
                    val terminalError = terminalErrors.remove(page)
                    page.status = if (terminalError != null) Page.State.Error(terminalError) else Page.State.Queue
                    page.slow = false
                    page.loadStatus = if (terminalError == null) {
                        ReaderPageLoadStatus.Queued
                    } else {
                        ReaderPageLoadStatus.Idle
                    }
                    if (terminalError == null && disposition != ImageCancelReason.PAGE_HIDDEN && page in currentWindow && !isRecycled) {
                        enqueue(page, priorityFor(page))
                    }
                }
            }
            if (deferRetry && page in currentWindow && !isRecycled) {
                runCatching {
                    coordinator.watchdog.schedule({
                        if (!isRecycled && page.status == Page.State.Queue && page in currentWindow) {
                            enqueue(page, priorityFor(page))
                        }
                    }, 100, TimeUnit.MILLISECONDS)
                }
            }
            onPageLoadFinished(page)
        }
    }

    private fun owns(page: ReaderPage, generation: Int): Boolean =
        !isRecycled && page.generation.get() == generation

    /** Decode a bounded preview so a complete HTTP body with a valid magic header but corrupt pixels cannot advance the batch. */
    private fun validateCachedImage(file: java.io.File): Boolean {
        if (!file.isFile || file.length() <= 0L || ImageUtil.findImageType { file.inputStream() } == null) return false
        return try {
            file.inputStream().use { input ->
                val decoder = ImageDecoder.newInstance(input, false, null) ?: return false
                try {
                    val width = decoder.width
                    val height = decoder.height
                    if (width <= 0 || height <= 0) return false
                    var sampleSize = 1
                    val maxDimension = maxOf(width, height)
                    while (maxDimension / sampleSize > 512) sampleSize *= 2
                    val bitmap = decoder.decode(sampleSize = sampleSize) ?: return false
                    bitmap.recycle()
                    true
                } finally {
                    decoder.recycle()
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private class PriorityPage(
        val page: ReaderPage,
        val priority: Int,
        val generation: Int,
    ) : Comparable<PriorityPage> {
        companion object {
            private val idGenerator = AtomicInteger(0)
        }
        private val identifier = idGenerator.incrementAndGet()
        override fun compareTo(other: PriorityPage): Int {
            val priorityOrder = other.priority.compareTo(priority)
            return if (priorityOrder != 0) priorityOrder else identifier.compareTo(other.identifier)
        }
    }
}
