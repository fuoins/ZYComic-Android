package eu.kanade.tachiyomi.ui.reader.loader

import com.zycomic.app.net.ImageAttemptCoordinator
import com.zycomic.app.net.ImageAttemptDeferredException
import com.zycomic.app.net.ImageCacheManager
import com.zycomic.app.net.ImageCancelReason
import com.zycomic.app.net.ImageDecryptFailedException
import com.zycomic.app.net.PageAttemptScope
import com.zycomic.app.net.RouteManager
import com.zycomic.app.net.normalizedForComparison
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
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private val retryQueue = java.util.concurrent.PriorityBlockingQueue<PriorityPage>()
    private val workerSignals = LinkedBlockingQueue<Unit>()
    private val retryWorkerActive = AtomicBoolean(false)
    private val preloadSize = readerPreferences.preloadSize().get().coerceAtLeast(0)
    private val readerThreads = readerPreferences.readerThreads().get().coerceIn(1, 4)
    private val rollingWindowSize = if (preloadSize == 0) 1 else min(3, preloadSize + 1)
    private val nearForegroundSize = min(rollingWindowSize - 1, preloadSize)
    private val dataSaver = DataSaver(source, sourcePreferences)
    private val activeScopes = ConcurrentHashMap<ReaderPage, PageAttemptScope>()
    private val activeRetryLoads = ConcurrentHashMap.newKeySet<ReaderPage>()
    private val queuedRetryPages = ConcurrentHashMap.newKeySet<ReaderPage>()
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

    @Volatile private var rollingPages: List<ReaderPage> = emptyList()

    init {
        repeat(readerThreads) {
            workerScope.launchIO {
                while (true) {
                    runInterruptible { workerSignals.take() }
                    val entry = queue.poll() ?: if (!retryWorkerActive.get()) retryQueue.poll() else null
                    if (entry == null) continue
                    val page = entry.page
                    val ownsRetryWorker = entry.isRetry && retryWorkerActive.compareAndSet(false, true)
                    if (entry.isRetry && !ownsRetryWorker) {
                        retryQueue.offer(entry)
                        continue
                    }
                    val shouldLoad = synchronized(queueLock) {
                        synchronized(page) {
                            val ownsEntry = page.queuedToken.get() == entry.generation
                            if (ownsEntry) page.queuedToken.compareAndSet(entry.generation, -1)
                            ownsEntry && entry.generation == page.generation.get() &&
                                page.status == Page.State.Queue && readingOrderInitialized &&
                                (entry.isRetry || page in currentWindow)
                        }
                    }
                    if (entry.isRetry) queuedRetryPages.remove(page)
                    if (!shouldLoad) {
                        if (ownsRetryWorker) {
                            retryWorkerActive.set(false)
                            workerSignals.offer(Unit)
                        }
                        continue
                    }
                    if (entry.isRetry) activeRetryLoads.add(page)
                    try {
                        internalLoadPage(page, entry.isRetry, entry.queuedAtNanos)
                    } finally {
                        if (entry.isRetry) {
                            activeRetryLoads.remove(page)
                            retryWorkerActive.set(false)
                            workerSignals.offer(Unit)
                        }
                    }
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
        val newlyVisible = newVisible - oldVisible
        (oldVisible - newVisible).forEach { it.readerVisible = false }
        (newVisible - oldVisible).forEach { it.readerVisible = true }
        visiblePages = normalizedVisible
        this.successors = normalizedSuccessors
        activeScopes.values.forEach { it.updateVisibility(it.page in normalizedVisible) }
        readingRanks = normalizedSuccessors.withIndex().associate { it.value to it.index }

        val nextRollingPages = synchronized(preloadLock) {
            if (!readingOrderInitialized) {
                chapterPageOrder = chapter.pages.orEmpty()
                    .filterNot { it is InsertPage }
                    .distinctBy { it.index }
                    .ifEmpty { (normalizedVisible + normalizedSuccessors).distinctBy { it.index } }
                readingOrderInitialized = true
            }
            val visiblePositions = normalizedVisible.mapNotNull { page -> chapterPagePosition(page).takeIf { it >= 0 } }
            val firstSuccessorPosition = normalizedSuccessors.firstOrNull()?.let(::chapterPagePosition)?.takeIf { it >= 0 }
            val anchor = (visiblePositions.maxOrNull()?.plus(1) ?: firstSuccessorPosition ?: preloadStartIndex)
                .coerceIn(0, chapterPageOrder.size)
            preloadStartIndex = visiblePositions.minOrNull() ?: anchor
            preloadCursor = anchor
            rollingPages = emptyList()
            fillRollingWindowLocked(normalizedVisible)
        }
        val newWindow = (normalizedVisible + nextRollingPages).toSet()

        val promotedRetries = synchronized(queueLock) {
            currentWindow = newWindow
            val promoteRegular = queueSnapshot().filter { it.page in newVisible }
            promoteRegular.forEach { entry ->
                if (queue.remove(entry)) {
                    synchronized(entry.page) {
                        if (entry.page.queuedToken.get() == entry.generation) entry.page.queuedToken.set(-1)
                    }
                }
            }
            val promoteRetries = retryQueueSnapshot().filter { it.page in newlyVisible }
            promoteRetries.forEach { entry ->
                if (retryQueue.remove(entry)) {
                    queuedRetryPages.remove(entry.page)
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
            (promoteRegular + promoteRetries).map { it.page }.distinctBy { it.index }
        }

        activeScopes.keys.filter { it !in newWindow && it !in activeRetryLoads }
            .forEach { cancelPage(it, ImageCancelReason.PAGE_HIDDEN, requeue = false) }
        (oldVisible - newVisible).filter { it in activeRetryLoads }
            .forEach { cancelPage(it, ImageCancelReason.ABANDON_FAR, requeue = true) }
        (normalizedVisible + nextRollingPages).distinctBy { it.index }.forEach(::enqueueIfLoadable)
        promotedRetries.forEach { page ->
            if (page.status == Page.State.Queue) enqueue(page, priorityFor(page))
        }
    }

    private fun priorityFor(page: ReaderPage): Int = if (page in visiblePages) 2 else 1

    private fun queueSnapshot(): List<PriorityPage> = queue.toArray(emptyArray<PriorityPage>()).asList()

    private fun retryQueueSnapshot(): List<PriorityPage> = retryQueue.toArray(emptyArray<PriorityPage>()).asList()

    private fun enqueue(page: ReaderPage, priority: Int) {
        synchronized(queueLock) {
            if (!readingOrderInitialized || page !in currentWindow || page.status != Page.State.Queue) return
            synchronized(page) {
                val generation = page.generation.get()
                if (page.queuedToken.get() == generation) return
                page.queuedToken.set(generation)
                queue.offer(PriorityPage(page, priority, generation, isRetry = false, queuedAtNanos = System.nanoTime()))
                workerSignals.offer(Unit)
            }
        }
    }

    private fun enqueueRetry(page: ReaderPage) {
        synchronized(queueLock) {
            if (!readingOrderInitialized || page.status != Page.State.Queue) return
            synchronized(page) {
                val generation = page.generation.get()
                if (page.queuedToken.get() == generation) return
                page.queuedToken.set(generation)
                queuedRetryPages.add(page)
                retryQueue.offer(PriorityPage(page, 0, generation, isRetry = true, queuedAtNanos = System.nanoTime()))
                workerSignals.offer(Unit)
            }
        }
    }

    private fun chapterPagePosition(page: ReaderPage): Int =
        chapterPageOrder.indexOfFirst { it === page || it.index == page.index }
            .takeIf { it >= 0 } ?: -1

    private fun isSuccessfullyCached(page: ReaderPage): Boolean {
        if (page in successfullyCachedPages) return true
        val imageUrl = page.imageUrl ?: return false
        return page.status == Page.State.Ready && chapterCache.isImageInCache(imageUrl)
    }

    private fun fillRollingWindowLocked(visible: List<ReaderPage> = visiblePages): List<ReaderPage> {
        val capacity = (rollingWindowSize - visible.size).coerceAtLeast(0)
        val result = rollingPages.filterNot { page ->
            page in visible || isSuccessfullyCached(page) || page.status is Page.State.Error || page in queuedRetryPages
        }.toMutableList()
        while (preloadCursor < chapterPageOrder.size && result.size < capacity) {
            val page = chapterPageOrder[preloadCursor++]
            if (page in visible || isSuccessfullyCached(page) || page.status is Page.State.Error || page in queuedRetryPages) continue
            result += page
        }
        rollingPages = result
        return result
    }

    private fun refillRollingWindow() {
        val next = synchronized(preloadLock) { fillRollingWindowLocked() }
        val window = (visiblePages + next).toSet()
        synchronized(queueLock) { currentWindow = window }
        next.forEach(::enqueueIfLoadable)
    }

    private fun enqueueIfLoadable(page: ReaderPage) {
        if (page.status == Page.State.Ready && page.imageUrl != null && !chapterCache.isImageInCache(page.imageUrl!!)) {
            successfullyCachedPages.remove(page)
            page.status = Page.State.Queue
        }
        if (page.status == Page.State.Queue && page in currentWindow) {
            if (page in visiblePages) maybePreemptForVisiblePage()
            enqueue(page, priorityFor(page))
        }
    }

    private fun onPageLoadFinished(page: ReaderPage) {
        if (page.status == Page.State.Ready) {
            (visiblePages + rollingPages).distinctBy { it.index }.forEach(::enqueueIfLoadable)
        }
        if (page !in visiblePages && (page.status == Page.State.Ready || page.status is Page.State.Error || page in queuedRetryPages)) {
            synchronized(preloadLock) { rollingPages = rollingPages.filterNot { it === page } }
            refillRollingWindow()
        }
    }

    /** Cancel exactly one active far-foreground attempt; queued items are not worker capacity. */
    private fun maybePreemptForVisiblePage() {
        if (activeScopes.size < readerThreads) return
        val visible = visiblePages.toSet()
        val nearForeground = successors.take(nearForegroundSize).toSet()
        val anchorPosition = visiblePages.lastOrNull()
            ?.let(::chapterPagePosition)
            ?.takeIf { it >= 0 }
        val now = System.nanoTime()
        val target = activeScopes.values.asSequence()
            .map { it.page }
            .filter { it !in visible && (it in activeRetryLoads || (it !in nearForeground && it in currentWindow)) }
            .filter { page ->
                val active = page.activeAttempt.get()
                val lastProgress = active?.lastProgressAtNanos?.get() ?: 0L
                active?.let {
                    it.isCurrent && it.hasOuterCall && it.hasActiveEndpointRequest && lastProgress > 0L &&
                        now - lastProgress >= TimeUnit.MILLISECONDS.toNanos(1_500L)
                } == true
            }
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
        // cancelPage records ABANDON_FAR; completion returns a hidden retry to the low-priority queue.
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
            page.automaticRetryRound.set(1)
            page.endpointsAttemptedInRound.set(0)
            page.automaticRetryExhausted = false
            page.imageUrl?.let(decodeFailures::remove)
        }
        if (page.manualRetryCount.get() >= 3) {
            page.slow = false
            page.loadStatus = ReaderPageLoadStatus.Idle
            page.status = Page.State.Error(IOException("已重试 3 次，请重新测速或切换图源"))
            return
        }
        page.manualRetryCount.incrementAndGet()
        if (page.automaticRetryRound.get() >= PageAttemptScope.MAX_AUTOMATIC_RETRY_ROUNDS) {
            page.automaticRetryRound.incrementAndGet()
            page.endpointsAttemptedInRound.set(0)
        }
        page.automaticRetryExhausted = false
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
        if (page in currentWindow) {
            enqueue(page, priorityFor(page))
        } else {
            enqueueRetry(page)
        }
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
        retryQueue.clear()
        workerSignals.clear()
        queuedRetryPages.clear()
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

    private suspend fun internalLoadPage(page: ReaderPage, isRetry: Boolean, queuedAtNanos: Long) {
        if (!readingOrderInitialized || (!isRetry && page !in currentWindow)) return
        val queueWaitNanos = (System.nanoTime() - queuedAtNanos).coerceAtLeast(0L)
        if (visiblePages.firstOrNull()?.let { it !== page } == true) {
            val homePage = visiblePages.first()
            val homeScope = homePage.activeAttempt.get()
            val homeStarted = homeScope?.hasOuterCall == true || homePage.status == Page.State.Ready || homePage.status is Page.State.Error
            if (!homeStarted || coordinator.availablePrimaryAttemptCount <= 0) {
                page.loadStatus = ReaderPageLoadStatus.WaitingForRequest
                runCatching {
                    coordinator.watchdog.schedule({
                        if (!isRecycled && page.status == Page.State.Queue && (isRetry || page in currentWindow)) {
                            if (isRetry && page !in visiblePages) enqueueRetry(page) else enqueue(page, priorityFor(page))
                        }
                    }, 100, TimeUnit.MILLISECONDS)
                }
                return
            }
        }
        val myGeneration = synchronized(queueLock) {
            if (!readingOrderInitialized || (!isRetry && page !in currentWindow)) return
            synchronized(page) { page.generation.incrementAndGet() }
        }
        var attempt: PageAttemptScope? = null
        var slowWatch: java.util.concurrent.ScheduledFuture<*>? = null
        var deferRetry = false
        var urlResolutionNanos = 0L
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                if (!owns(page, myGeneration)) return
                page.status = Page.State.LoadPage
                page.loadStatus = ReaderPageLoadStatus.ResolvingImageUrl
                val resolutionStartedAt = System.nanoTime()
                try {
                    page.imageUrl = source.getImageUrl(page)
                } finally {
                    urlResolutionNanos = System.nanoTime() - resolutionStartedAt
                    logcat {
                        "Reader page ${page.index + 1} image URL resolution: " +
                            "${TimeUnit.NANOSECONDS.toMillis(urlResolutionNanos)}ms"
                    }
                }
            }
            val imageUrl = page.imageUrl ?: throw IOException("图片 URL 为空")
            if (!chapterCache.isImageInCache(imageUrl)) {
                if (!owns(page, myGeneration)) return
                val currentAttempt = synchronized(queueLock) {
                    if (!readingOrderInitialized || (!isRetry && page !in currentWindow) || !owns(page, myGeneration)) {
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
                            queueWaitNanos = queueWaitNanos,
                            urlResolutionTimeNanos = urlResolutionNanos,
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
                val cacheStartedAt = System.nanoTime()
                try {
                    chapterCache.putImageToCache(imageUrl, response) {
                        owns(page, myGeneration) && page.activeAttempt.get() === currentAttempt
                    }
                } finally {
                    currentAttempt.addCacheTime(System.nanoTime() - cacheStartedAt)
                }
            }
            if (!owns(page, myGeneration)) return
            val cachedImage = chapterCache.getImageFile(imageUrl)
            val validationStartedAt = System.nanoTime()
            val validCachedImage = chapterCache.isImageInCache(imageUrl) && validateCachedImage(cachedImage)
            attempt?.addValidationTime(System.nanoTime() - validationStartedAt)
            if (!validCachedImage) {
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
                val remainingEndpoints = remainingUntriedEndpoints(page)
                val attemptsThisRound = page.endpointsAttemptedInRound.get()
                val canContinueRound = attemptsThisRound in 1 until PageAttemptScope.MAX_ENDPOINTS_PER_ROUND &&
                    remainingEndpoints.isNotEmpty()
                val canAdvanceRound = attemptsThisRound >= PageAttemptScope.MAX_ENDPOINTS_PER_ROUND &&
                    page.automaticRetryRound.get() < PageAttemptScope.MAX_AUTOMATIC_RETRY_ROUNDS &&
                    remainingEndpoints.isNotEmpty()
                val advanced = if (canAdvanceRound) {
                    attempt?.beginNextAutomaticRound() ?: run {
                        page.automaticRetryRound.incrementAndGet()
                        page.endpointsAttemptedInRound.set(0)
                        true
                    }
                } else {
                    false
                }
                if (canContinueRound || advanced) {
                    page.automaticRetryExhausted = false
                    page.status = Page.State.Queue
                    page.loadStatus = ReaderPageLoadStatus.Queued
                    enqueueRetry(page)
                } else {
                    page.slow = false
                    page.loadStatus = ReaderPageLoadStatus.Idle
                    page.automaticRetryExhausted = remainingEndpoints.isEmpty() ||
                        page.automaticRetryRound.get() >= PageAttemptScope.MAX_AUTOMATIC_RETRY_ROUNDS
                    page.status = Page.State.Error(e)
                }
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
                    if (terminalError == null && disposition != ImageCancelReason.PAGE_HIDDEN &&
                        (isRetry || page in currentWindow) && !isRecycled
                    ) {
                        if (isRetry && page !in visiblePages) enqueueRetry(page) else enqueue(page, priorityFor(page))
                    }
                }
            }
            if (deferRetry && (isRetry || page in currentWindow) && !isRecycled) {
                runCatching {
                    coordinator.watchdog.schedule({
                        if (!isRecycled && page.status == Page.State.Queue && (isRetry || page in currentWindow)) {
                            if (isRetry && page !in visiblePages) enqueueRetry(page) else enqueue(page, priorityFor(page))
                        }
                    }, 100, TimeUnit.MILLISECONDS)
                }
            }
            attempt?.let { current ->
                logcat {
                    "Reader page ${page.index + 1} timing: ${current.timingSummary()}, " +
                        "triedNodes=${page.attemptedImageEndpoints.size}, round=${page.automaticRetryRound.get()}"
                }
            }
            onPageLoadFinished(page)
        }
    }

    private fun remainingUntriedEndpoints(page: ReaderPage): List<com.zycomic.app.net.DomainIp> {
        val host = runCatching { java.net.URI(page.imageUrl.orEmpty()).host }.getOrNull() ?: return emptyList()
        val tried = page.attemptedImageEndpoints.mapTo(HashSet()) { it.normalizedForComparison() }
        return RouteManager.imageRetryEndpoints(host, tried)
            .mapNotNull { candidate ->
                candidate ?: runCatching {
                    com.zycomic.app.net.NetworkModule.resolveProbeIps(host)
                        .firstOrNull()?.let { com.zycomic.app.net.DomainIp(host, it) }
                }.getOrNull()
            }
            .filter { it.normalizedForComparison() !in tried && RouteManager.isImgEndpointAvailable(it) }
            .distinctBy { it.normalizedForComparison() }
    }

    private fun owns(page: ReaderPage, generation: Int): Boolean =
        !isRecycled && page.generation.get() == generation

    /** Decode a bounded preview so a complete HTTP body with a valid header but corrupt pixels cannot become Ready. */
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
        val isRetry: Boolean,
        val queuedAtNanos: Long,
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
