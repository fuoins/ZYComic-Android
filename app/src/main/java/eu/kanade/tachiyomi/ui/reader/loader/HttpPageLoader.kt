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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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
    private val workerSignals = LinkedBlockingQueue<Unit>()
    private val preloadSize = readerPreferences.preloadSize().get().coerceAtLeast(0)
    private val configuredReaderThreads = readerPreferences.readerThreads().get().coerceIn(0, 10)
    private val readerThreads = configuredReaderThreads.coerceAtLeast(1)
    private val preloadTaskLimit = if (configuredReaderThreads == 0) 0 else min(10, preloadSize)
    private val nearForegroundSize = min(9, preloadTaskLimit)
    private val dataSaver = DataSaver(source, sourcePreferences)
    private val activeScopes = ConcurrentHashMap<ReaderPage, PageAttemptScope>()
    private val activeWorkerJobs = ConcurrentHashMap<ReaderPage, Job>()
    private val pendingRuntimeRecoveryRetries = ConcurrentHashMap<ReaderPage, Int>()
    private val pendingDisposition = ConcurrentHashMap<ReaderPage, ImageCancelReason>()
    private val terminalErrors = ConcurrentHashMap<ReaderPage, Throwable>()
    private val decodeFailures = ConcurrentHashMap<String, AtomicInteger>()
    private val successfullyCachedPages = java.util.Collections.newSetFromMap(ConcurrentHashMap<ReaderPage, Boolean>())
    private val queueLock = Any()
    private val preloadLock = Any()
    private val queueTokenGenerator = AtomicInteger()
    private val capacityLimitedPages = ConcurrentHashMap.newKeySet<ReaderPage>()
    private val previouslyVisiblePages = LinkedHashMap<Int, ReaderPage>()

    @Volatile private var visiblePages: List<ReaderPage> = emptyList()

    @Volatile private var successors: List<ReaderPage> = emptyList()

    @Volatile private var currentWindow: Set<ReaderPage> = emptySet()

    @Volatile private var readingRanks: Map<ReaderPage, Int> = emptyMap()

    @Volatile private var readingOrderInitialized = false

    @Volatile private var schedulingEnabled = true

    @Volatile private var chapterPageOrder: List<ReaderPage> = emptyList()

    @Volatile private var rollingPages: List<ReaderPage> = emptyList()

    init {
        repeat(readerThreads) {
            workerScope.launchIO {
                while (true) {
                    awaitImageLoadingBarrier()
                    runInterruptible { workerSignals.take() }
                    awaitImageLoadingBarrier()
                    val entry = queue.poll() ?: continue
                    val page = entry.page
                    val shouldLoad = synchronized(queueLock) {
                        synchronized(page) {
                            val ownsEntry = page.queuedToken.compareAndSet(entry.queueToken, -1)
                            ownsEntry && schedulingEnabled && !activeWorkerJobs.containsKey(page) &&
                                entry.generation == page.generation.get() &&
                                page.status == Page.State.Queue && readingOrderInitialized &&
                                page in currentWindow
                        }
                    }
                    if (!shouldLoad) continue
                    val pageJob = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.LAZY) {
                        internalLoadPage(page, entry.queuedAtNanos)
                    }
                    if (activeWorkerJobs.putIfAbsent(page, pageJob) != null) {
                        pageJob.cancel()
                        continue
                    }
                    try {
                        pageJob.start()
                        pageJob.join()
                    } finally {
                        activeWorkerJobs.remove(page, pageJob)
                        if (schedulingEnabled && !isRecycled && page.status == Page.State.Queue &&
                            page in currentWindow && page !in capacityLimitedPages
                        ) {
                            enqueue(page, priorityFor(page))
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
        if (!schedulingEnabled || isRecycled || !readingOrderInitialized) return@withIOContext
        if (page.status == Page.State.Ready && page.imageUrl != null && !chapterCache.isImageInCache(page.imageUrl!!)) {
            successfullyCachedPages.remove(page)
            page.status = Page.State.Queue
        }
        if (page.status == Page.State.Queue && (currentWindow.isEmpty() || page in currentWindow)) {
            enqueue(page, priorityFor(page))
        }
    }

    /** The viewer is the authority for visible pages and real reading order. */
    override fun updateReadingOrder(
        visible: List<ReaderPage>,
        successors: List<ReaderPage>,
        chapterOrder: List<ReaderPage>,
    ) {
        super.updateReadingOrder(visible, successors, chapterOrder)
        val normalizedVisible = visible.asSequence()
            .filterNot { it is InsertPage }
            .distinctBy { it.index }
            .toList()
        val normalizedSuccessors = successors.asSequence()
            .filterNot { it is InsertPage }
            .distinctBy { it.index }
            .toList()
        // A transition or transient viewer refresh can report no page order. Keep the last
        // window intact; in particular, do not turn an empty order into a fresh rolling preload.
        if (normalizedVisible.isEmpty() && normalizedSuccessors.isEmpty() && chapterOrder.isEmpty()) return
        val oldVisiblePages = visiblePages
        val oldVisible = oldVisiblePages.toSet()
        val newVisible = normalizedVisible.toSet()
        (oldVisible - newVisible).forEach { it.readerVisible = false }
        (newVisible - oldVisible).forEach { it.readerVisible = true }
        visiblePages = normalizedVisible
        this.successors = normalizedSuccessors
        activeScopes.values.forEach { it.updateVisibility(it.page in normalizedVisible) }
        readingRanks = normalizedSuccessors.withIndex().associate { it.value to it.index }

        val nextRollingPages = synchronized(preloadLock) {
            if (preloadTaskLimit == 0) {
                normalizedVisible.filterNot(::isPageSettled)
                    .forEach { previouslyVisiblePages[it.index] = it }
            }
            if (normalizedVisible.isNotEmpty() || chapterOrder.isNotEmpty() || !readingOrderInitialized) {
                val chapterPages = chapter.pages.orEmpty()
                    .filterNot { it is InsertPage }
                    .distinctBy { it.index }
                chapterPageOrder = (normalizedVisible + normalizedSuccessors + chapterOrder + chapterPages)
                    .filterNot { it is InsertPage }
                    .distinctBy { it.index }
            }
            readingOrderInitialized = true
            if (!schedulingEnabled) return@synchronized emptyList()
            fillRollingWindowLocked(normalizedVisible, oldVisiblePages)
        }
        if (!schedulingEnabled) {
            synchronized(queueLock) { currentWindow = emptySet() }
            return
        }
        val newWindow = (normalizedVisible + nextRollingPages).toSet()

        updateCurrentWindow(newWindow)

        (normalizedVisible + nextRollingPages).distinctBy { it.index }.forEach(::enqueueIfLoadable)
        wakeCapacityLimitedPages()
    }

    override fun activateForReading() {
        super.activateForReading()
        if (isRecycled || schedulingEnabled) return
        schedulingEnabled = true
        val next = synchronized(preloadLock) { fillRollingWindowLocked() }
        val window = (visiblePages + next).toSet()
        synchronized(queueLock) { currentWindow = if (schedulingEnabled) window else emptySet() }
        if (!schedulingEnabled) return
        (visiblePages + next).distinctBy { it.index }.forEach(::enqueueIfLoadable)
    }

    override fun deactivateForReading() {
        if (!schedulingEnabled || isRecycled) return
        schedulingEnabled = false
        visiblePages.forEach { it.readerVisible = false }

        synchronized(queueLock) {
            queueSnapshot().forEach { entry ->
                entry.page.queuedToken.compareAndSet(entry.queueToken, -1)
            }
            queue.clear()
            workerSignals.clear()
            currentWindow = emptySet()
        }
        visiblePages = emptyList()
        successors = emptyList()
        readingRanks = emptyMap()
        capacityLimitedPages.clear()

        val scopedPages = activeScopes.keys.toSet()
        scopedPages.forEach { cancelPage(it, ImageCancelReason.PAGE_HIDDEN, requeue = false) }
        activeWorkerJobs.entries.toList().forEach { (page, job) ->
            if (page !in scopedPages) {
                synchronized(page) { page.generation.incrementAndGet() }
                page.stream = null
                page.raceActive = false
                page.progress = 0
                page.slow = false
                page.loadStatus = ReaderPageLoadStatus.Queued
                page.status = Page.State.Queue
            }
            job.cancel(CancellationException("Reader chapter is no longer active"))
        }
    }

    private fun priorityFor(page: ReaderPage): Int {
        if (page in visiblePages) return Int.MAX_VALUE
        val rank = readingRanks[page] ?: return 0
        return if (rank < nearForegroundSize) nearForegroundSize - rank else 0
    }

    private fun queueSnapshot(): List<PriorityPage> = queue.toArray(emptyArray<PriorityPage>()).asList()

    private fun enqueue(page: ReaderPage, priority: Int) {
        synchronized(queueLock) {
            if (!schedulingEnabled || isRecycled || !readingOrderInitialized || page !in currentWindow ||
                page.status != Page.State.Queue || page in capacityLimitedPages
            ) {
                return
            }
            synchronized(page) {
                val generation = page.generation.get()
                val oldQueueToken = page.queuedToken.get()
                if (oldQueueToken != -1) {
                    val staleEntries = queueSnapshot().filter {
                        it.page === page && it.queueToken == oldQueueToken && it.generation != generation
                    }
                    if (staleEntries.isEmpty()) return
                    staleEntries.forEach { entry ->
                        if (queue.remove(entry)) page.queuedToken.compareAndSet(entry.queueToken, -1)
                    }
                    if (page.queuedToken.get() != -1) return
                }
                val queueToken = queueTokenGenerator.incrementAndGet()
                page.queuedToken.set(queueToken)
                queue.offer(PriorityPage(page, priority, generation, queueToken, queuedAtNanos = System.nanoTime()))
                workerSignals.offer(Unit)
            }
        }
    }

    private fun updateCurrentWindow(newWindow: Set<ReaderPage>) {
        val pagesToRequeue = synchronized(queueLock) {
            currentWindow = newWindow
            queueSnapshot().mapNotNull { entry ->
                val page = entry.page
                val inWindow = page in newWindow
                if ((!inWindow || entry.priority != priorityFor(page)) && queue.remove(entry)) {
                    page.queuedToken.compareAndSet(entry.queueToken, -1)
                    if (!inWindow) {
                        page.slow = false
                        capacityLimitedPages.remove(page)
                    }
                    page.takeIf { inWindow && page.status == Page.State.Queue }
                } else {
                    null
                }
            }
        }
        pagesToRequeue.distinctBy { it.index }.forEach { enqueue(it, priorityFor(it)) }
    }

    private fun chapterPagePosition(page: ReaderPage): Int =
        chapterPageOrder.indexOfFirst { it === page || it.index == page.index }
            .takeIf { it >= 0 } ?: -1

    private fun isSuccessfullyCached(page: ReaderPage): Boolean {
        if (page in successfullyCachedPages) return true
        val imageUrl = page.imageUrl ?: return false
        return page.status == Page.State.Ready && chapterCache.isImageInCache(imageUrl)
    }

    private fun isPageSettled(page: ReaderPage): Boolean =
        isSuccessfullyCached(page) || page.status is Page.State.Error

    private fun fillRollingWindowLocked(
        visible: List<ReaderPage> = visiblePages,
        previouslyVisible: List<ReaderPage> = emptyList(),
    ): List<ReaderPage> {
        val unfinishedVisible = visible.count { !isPageSettled(it) }
        val capacity = if (preloadTaskLimit == 0) {
            (10 - unfinishedVisible).coerceAtLeast(0)
        } else {
            (preloadTaskLimit - unfinishedVisible).coerceAtLeast(0)
        }
        val candidates = if (preloadTaskLimit == 0) {
            (rollingPages + previouslyVisible + previouslyVisiblePages.values).asSequence()
                .filter { it !in visible && !isPageSettled(it) }
                .distinctBy { it.index }
                .sortedBy(::chapterPagePosition)
                .toList()
        } else {
            chapterPageOrder.filter { it !in visible && !isPageSettled(it) }
        }
        val result = candidates.take(capacity).toMutableList()
        result.sortBy(::chapterPagePosition)
        rollingPages = result
        return result
    }

    private fun refillRollingWindow() {
        if (!schedulingEnabled || isRecycled) return
        val next = synchronized(preloadLock) { fillRollingWindowLocked() }
        val window = (visiblePages + next).toSet()
        updateCurrentWindow(if (schedulingEnabled) window else emptySet())
        if (!schedulingEnabled) return
        next.forEach(::enqueueIfLoadable)
        wakeCapacityLimitedPages()
    }

    private fun enqueueIfLoadable(page: ReaderPage) {
        if (!schedulingEnabled || isRecycled) return
        if (page.status == Page.State.Ready && page.imageUrl != null && !chapterCache.isImageInCache(page.imageUrl!!)) {
            successfullyCachedPages.remove(page)
            page.status = Page.State.Queue
        }
        if (page.status == Page.State.Queue && page in currentWindow && page !in capacityLimitedPages) {
            enqueue(page, priorityFor(page))
        }
    }

    private fun onPageLoadFinished(page: ReaderPage) {
        if (!schedulingEnabled || isRecycled) return
        if (page.status == Page.State.Ready) {
            (visiblePages + rollingPages).distinctBy { it.index }.forEach(::enqueueIfLoadable)
        }
        if (isPageSettled(page)) {
            synchronized(preloadLock) {
                previouslyVisiblePages.remove(page.index)
                if (page !in visiblePages) {
                    rollingPages = rollingPages.filterNot { it.index == page.index }
                }
            }
            refillRollingWindow()
        }
        wakeCapacityLimitedPages()
    }

    /** Wake visible work first, then admit background work only while its in-flight budget allows. */
    private fun wakeCapacityLimitedPages() {
        if (!schedulingEnabled || isRecycled || capacityLimitedPages.isEmpty()) return
        val pagesToWake = synchronized(queueLock) {
            var remainingBackgroundCapacity = (
                preloadTaskLimit - activeScopes.values.count { it.page !in visiblePages }
                ).coerceAtLeast(0)
            capacityLimitedPages.toList().filter { page ->
                when {
                    page !in currentWindow || page.status != Page.State.Queue -> {
                        capacityLimitedPages.remove(page)
                        false
                    }
                    page in visiblePages -> {
                        capacityLimitedPages.remove(page)
                        true
                    }
                    remainingBackgroundCapacity > 0 -> {
                        remainingBackgroundCapacity--
                        capacityLimitedPages.remove(page)
                        true
                    }
                    else -> false
                }
            }
        }
        pagesToWake.forEach(::enqueueIfLoadable)
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
            workerScope.launchIO { loadPageWhenAllowed(page) }
        }
    }

    override fun retryPage(page: ReaderPage) {
        successfullyCachedPages.remove(page)
        val isJm = source === com.zycomic.app.reader.JmSource
        val sourceKey = if (isJm) {
            "jm:${com.zycomic.app.net.JmRouteManager.activeImageDomain}"
        } else {
            RouteManager.imgRewriteTarget ?: RouteManager.imgHost
        }
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
        val wasActive = cancelPage(page, ImageCancelReason.USER_RETRY, requeue = true)
        if (isJm) {
            // Only an explicit manual retry may revisit transiently failed JM hosts.
            // Do not reset automatic rounds: the existing manual/automatic limits still apply.
            page.attemptedImageEndpoints.clear()
            page.endpointsAttemptedInRound.set(0)
        }
        if (page.automaticRetryRound.get() >= PageAttemptScope.MAX_AUTOMATIC_RETRY_ROUNDS) {
            page.automaticRetryRound.incrementAndGet()
            page.endpointsAttemptedInRound.set(0)
        }
        page.automaticRetryExhausted = false
        val imageUrl = page.imageUrl
        if (!imageUrl.isNullOrBlank()) {
            chapterCache.remove(imageUrl)
            ImageCacheManager.remove(imageUrl)
        }
        page.stream = null
        page.slow = false
        page.loadStatus = ReaderPageLoadStatus.Queued
        page.progress = 0
        capacityLimitedPages.remove(page)
        if (wasActive) return
        synchronized(page) { page.generation.incrementAndGet() }
        if (source.isEhBasedSource()) page.imageUrl = null
        page.status = Page.State.Queue
        enqueue(page, priorityFor(page))
    }

    override fun retryAfterRuntimeRecovery(page: ReaderPage, expectedGeneration: Int) {
        if (isRecycled || !schedulingEnabled || page !in visiblePages || !page.readerVisible ||
            page.generation.get() != expectedGeneration
        ) {
            return
        }
        pendingRuntimeRecoveryRetries[page] = expectedGeneration
        finishPendingRuntimeRecoveryRetry(page)
    }

    private fun finishPendingRuntimeRecoveryRetry(page: ReaderPage) {
        val expectedGeneration = pendingRuntimeRecoveryRetries[page] ?: return
        if (isRecycled || !schedulingEnabled || page !in visiblePages || !page.readerVisible ||
            page.generation.get() != expectedGeneration
        ) {
            pendingRuntimeRecoveryRetries.remove(page, expectedGeneration)
            return
        }
        if (page.activeAttempt.get() != null || page.status !is Page.State.Error ||
            !pendingRuntimeRecoveryRetries.remove(page, expectedGeneration)
        ) {
            return
        }

        page.imageUrl?.let {
            chapterCache.remove(it)
            ImageCacheManager.remove(it)
        }
        page.attemptedImageEndpoints.clear()
        page.endpointsAttemptedInRound.set(0)
        page.automaticRetryRound.set(1)
        page.automaticRetryExhausted = false
        page.stream = null
        page.slow = false
        page.progress = 0
        page.loadStatus = ReaderPageLoadStatus.Queued
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
                onPageLoadFinished(page)
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

    @Synchronized
    override fun recycle() {
        if (isRecycled) return
        deactivateForReading()
        super.recycle()
        visiblePages.forEach { it.readerVisible = false }
        activeScopes.keys.toList().forEach { cancelPage(it, ImageCancelReason.PAGE_HIDDEN, requeue = false) }
        activeWorkerJobs.values.forEach { it.cancel(CancellationException("Reader loader recycled")) }
        workerScope.cancel()
        queue.clear()
        workerSignals.clear()
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

    private suspend fun internalLoadPage(page: ReaderPage, queuedAtNanos: Long) {
        if (!schedulingEnabled || !readingOrderInitialized || page !in currentWindow) return
        awaitImageLoadingBarrier()
        if (!schedulingEnabled || !readingOrderInitialized || page !in currentWindow) return
        val queueWaitNanos = (System.nanoTime() - queuedAtNanos).coerceAtLeast(0L)
        if (visiblePages.firstOrNull()?.let { it !== page } == true) {
            val homePage = visiblePages.first()
            val homeScope = homePage.activeAttempt.get()
            val homeStarted = homeScope?.hasOuterCall == true || homePage.status == Page.State.Ready || homePage.status is Page.State.Error
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
            if (!schedulingEnabled || !readingOrderInitialized || page !in currentWindow) return
            synchronized(page) { page.generation.incrementAndGet() }
        }
        var attempt: PageAttemptScope? = null
        var slowWatch: java.util.concurrent.ScheduledFuture<*>? = null
        var deferRetry = false
        var retryPageAfterAttempt = false
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
                var capacityLimited = false
                val currentAttempt = synchronized(queueLock) {
                    if (!schedulingEnabled || !readingOrderInitialized || page !in currentWindow || !owns(page, myGeneration)) {
                        null
                    } else if (page !in visiblePages &&
                        activeScopes.values.count { it.page !in visiblePages } >= preloadTaskLimit
                    ) {
                        capacityLimited = true
                        page.status = Page.State.Queue
                        page.loadStatus = ReaderPageLoadStatus.Queued
                        capacityLimitedPages.add(page)
                        null
                    } else {
                        page.status = Page.State.DownloadImage
                        page.loadStatus = ReaderPageLoadStatus.WaitingForRequest
                        page.progress = 0
                        capacityLimitedPages.remove(page)
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
                    if (!capacityLimited && owns(page, myGeneration)) {
                        page.status = Page.State.Queue
                        page.loadStatus = ReaderPageLoadStatus.Queued
                    }
                    return
                }
                attempt = currentAttempt
                slowWatch = coordinator.scheduleWatchdog {
                    val isNear = page in visiblePages || successors.take(nearForegroundSize).any { it === page }
                    val requestStartedAt = currentAttempt.requestStartedAtNanos.get()
                    val quietFor = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - currentAttempt.lastProgressAtNanos.get())
                    if (currentAttempt.isCurrent && currentAttempt.hasActiveEndpointRequest && requestStartedAt > 0L && isNear && quietFor >= 1_500L) {
                        page.slow = true
                    }
                }

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
                val waitingForUnifiedRecovery = attempt?.canRace == true && page.readerVisible
                if (!waitingForUnifiedRecovery && attempt?.retryAfterFailure(remainingEndpoints.isNotEmpty()) == true) {
                    page.automaticRetryExhausted = false
                    page.status = Page.State.Queue
                    page.loadStatus = ReaderPageLoadStatus.Queued
                    retryPageAfterAttempt = true
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
                synchronized(queueLock) { activeScopes.remove(page, current) }
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
                    if (terminalError == null && page in currentWindow && !isRecycled) {
                        enqueue(page, priorityFor(page))
                    }
                }
            }
            finishPendingRuntimeRecoveryRetry(page)
            if (deferRetry && page in currentWindow && !isRecycled) {
                runCatching {
                    coordinator.watchdog.schedule({
                        if (!isRecycled && page.status == Page.State.Queue && page in currentWindow) {
                            enqueue(page, priorityFor(page))
                        }
                    }, 100, TimeUnit.MILLISECONDS)
                }
            }
            if (retryPageAfterAttempt && page in currentWindow && !isRecycled) {
                enqueue(page, priorityFor(page))
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
        if (source === com.zycomic.app.reader.JmSource) {
            return com.zycomic.app.net.JmRouteManager.imageRetryEndpoints(tried)
        }
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
        schedulingEnabled && !isRecycled && page.generation.get() == generation

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
        val queueToken: Int,
        val queuedAtNanos: Long,
    ) : Comparable<PriorityPage> {
        override fun compareTo(other: PriorityPage): Int {
            val priorityOrder = other.priority.compareTo(priority)
            return if (priorityOrder != 0) priorityOrder else queueToken.compareTo(other.queueToken)
        }
    }
}
