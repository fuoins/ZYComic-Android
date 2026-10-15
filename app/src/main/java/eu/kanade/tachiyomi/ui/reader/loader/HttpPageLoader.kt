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
    private val readerThreads = readerPreferences.readerThreads().get().coerceAtLeast(1)
    private val nearForegroundSize = min(3, preloadSize)
    private val dataSaver = DataSaver(source, sourcePreferences)
    private val activeScopes = ConcurrentHashMap<ReaderPage, PageAttemptScope>()
    private val pendingDisposition = ConcurrentHashMap<ReaderPage, ImageCancelReason>()
    private val terminalErrors = ConcurrentHashMap<ReaderPage, Throwable>()
    private val decodeFailures = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile private var visiblePages: List<ReaderPage> = emptyList()
    @Volatile private var successors: List<ReaderPage> = emptyList()
    @Volatile private var currentWindow: Set<ReaderPage> = emptySet()
    @Volatile private var readingRanks: Map<ReaderPage, Int> = emptyMap()
    @Volatile private var readingOrderInitialized = false

    init {
        repeat(readerThreads) {
            workerScope.launchIO {
                while (true) {
                    val entry = runInterruptible { queue.take() }
                    val page = entry.page
                    if (entry.generation != page.generation.get() || page.status != Page.State.Queue) continue
                    synchronized(page) {
                        if (page.queuedToken.get() == entry.generation) page.queuedToken.set(-1)
                    }
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
            page.status = Page.State.Queue
        }
        if (page.status is Page.State.Error) page.status = Page.State.Queue
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
            .take(3)
            .toList()
        val normalizedSuccessors = successors.asSequence()
            .filterNot { it is InsertPage }
            .distinctBy { it.index }
            .take(preloadSize)
            .toList()
        val oldVisible = visiblePages.toSet()
        val newVisible = normalizedVisible.toSet()
        (oldVisible - newVisible).forEach { it.readerVisible = false }
        (newVisible - oldVisible).forEach { it.readerVisible = true }
        visiblePages = normalizedVisible
        this.successors = normalizedSuccessors
        readingOrderInitialized = true
        activeScopes.values.forEach { it.updateVisibility(it.page in normalizedVisible) }
        readingRanks = normalizedSuccessors.withIndex().associate { it.value to it.index }
        currentWindow = (normalizedVisible + normalizedSuccessors).toSet()

        // Upgrade any still-queued successor that just entered the viewport, preserving viewer order.
        val newlyVisibleQueueEntries = queue.toList().filter { it.page in newVisible }
        newlyVisibleQueueEntries.forEach { entry ->
            queue.remove(entry)
            synchronized(entry.page) {
                if (entry.page.queuedToken.get() == entry.generation) entry.page.queuedToken.set(-1)
            }
        }

        // Leaving the complete preload window is the only visibility-based abandon.
        activeScopes.keys.filter { it !in currentWindow }.forEach { cancelPage(it, ImageCancelReason.PAGE_HIDDEN, requeue = false) }
        queue.toList().filter { it.page !in currentWindow }.forEach { entry ->
            queue.remove(entry)
            synchronized(entry.page) {
                if (entry.page.queuedToken.get() == entry.generation) entry.page.queuedToken.set(-1)
            }
            entry.page.slow = false
        }

        normalizedVisible.forEach { page ->
            if (page.status == Page.State.Queue) {
                maybePreemptForVisiblePage()
                enqueue(page, priorityFor(page))
            }
        }
        normalizedSuccessors.forEach { page ->
            if (page.status == Page.State.Queue) enqueue(page, priorityFor(page))
        }
    }

    private fun priorityFor(page: ReaderPage): Int = if (page in visiblePages) 1 else 0

    private fun enqueue(page: ReaderPage, priority: Int) {
        if (page.status != Page.State.Queue) return
        synchronized(page) {
            val generation = page.generation.get()
            if (page.queuedToken.get() == generation) return
            page.queuedToken.set(generation)
            queue.offer(PriorityPage(page, priority, generation))
        }
    }

    /** Cancel exactly one active far-foreground attempt; queued items are not worker capacity. */
    private fun maybePreemptForVisiblePage() {
        if (activeScopes.size < readerThreads) return
        val farPages = successors.drop(nearForegroundSize).toSet()
        val target = activeScopes.values.asSequence()
            .map { it.page }
            .filter { it in farPages && it.activeAttempt.get()?.let { attempt -> attempt.isCurrent && attempt.hasOuterCall } == true }
            .maxByOrNull { readingRanks[it] ?: Int.MIN_VALUE }
            ?: return // E4: no far attempt to take; wait for existing workers.
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

    override fun retryPage(page: ReaderPage) {
        val sourceKey = RouteManager.imgRewriteTarget ?: RouteManager.imgHost
        if (page.retryBudgetSource != sourceKey) {
            page.retryBudgetSource = sourceKey
            page.manualRetryCount.set(0)
            page.imageUrl?.let(decodeFailures::remove)
        }
        if (page.manualRetryCount.get() >= 3) {
            page.slow = false
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
        page.progress = 0
        if (wasActive) return
        synchronized(page) { page.generation.incrementAndGet() }
        if (source.isEhBasedSource()) page.imageUrl = null
        page.status = Page.State.Queue
        enqueue(page, priorityFor(page))
    }

    override fun invalidatePage(page: ReaderPage): Boolean {
        val imageUrl = page.imageUrl ?: return false
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
                page.status = Page.State.Error(terminalErrors.remove(page) ?: ImageDecryptFailedException())
            }
            return false
        }
        if (!wasActive) {
            page.status = Page.State.Queue
            page.slow = false
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
        if (visiblePages.firstOrNull()?.let { it !== page } == true) {
            val homePage = visiblePages.first()
            val homeScope = homePage.activeAttempt.get()
            val homeStarted = homeScope?.hasOuterCall == true || homePage.status == Page.State.Ready || homePage.status is Page.State.Error
            if (!homeStarted || coordinator.availableAttemptCount <= 0) {
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
        val myGeneration = synchronized(page) { page.generation.incrementAndGet() }
        var attempt: PageAttemptScope? = null
        var slowWatch: java.util.concurrent.ScheduledFuture<*>? = null
        var deferRetry = false
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                if (!owns(page, myGeneration)) return
                page.status = Page.State.LoadPage
                page.imageUrl = source.getImageUrl(page)
            }
            val imageUrl = page.imageUrl ?: throw IOException("图片 URL 为空")
            if (!chapterCache.isImageInCache(imageUrl)) {
                if (!owns(page, myGeneration)) return
                page.status = Page.State.DownloadImage
                page.progress = 0
                val currentAttempt = PageAttemptScope(
                    page = page,
                    generation = myGeneration,
                    coordinator = coordinator,
                    canRace = page in visiblePages,
                    isSecondaryVisible = page in visiblePages && visiblePages.firstOrNull() !== page,
                )
                attempt = currentAttempt
                page.activeAttempt.set(currentAttempt)
                activeScopes[page] = currentAttempt
                currentAttempt.updateVisibility(page in visiblePages)
                slowWatch = coordinator.watchdog.scheduleAtFixedRate({
                    val isNear = page in visiblePages || successors.take(nearForegroundSize).any { it === page }
                    val quietFor = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - currentAttempt.lastProgressAtNanos.get())
                    if (currentAttempt.isCurrent && isNear && quietFor >= 1_500L) page.slow = true
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
            page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
            page.slow = false
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
                deferRetry = true
            } else {
                page.slow = false
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
        }
    }

    private fun owns(page: ReaderPage, generation: Int): Boolean =
        !isRecycled && page.generation.get() == generation

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
