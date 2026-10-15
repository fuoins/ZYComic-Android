package eu.kanade.tachiyomi.ui.reader.model

import com.zycomic.app.net.DomainIp
import com.zycomic.app.net.PageAttemptScope
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed interface ReaderPageLoadStatus {
    data object Queued : ReaderPageLoadStatus
    data object WaitingForRequest : ReaderPageLoadStatus
    data object Requesting : ReaderPageLoadStatus
    data class Receiving(val percentage: Int?) : ReaderPageLoadStatus
    data class TryingFallback(val attempt: Int, val total: Int) : ReaderPageLoadStatus
    data object Idle : ReaderPageLoadStatus

    val label: String
        get() = when (this) {
            Queued -> "排队中"
            WaitingForRequest -> "排队等待请求额度"
            Requesting -> "连接/请求中"
            is Receiving -> percentage?.let { "正在下载（$it%）" } ?: "正在接收图片数据"
            is TryingFallback -> "正在尝试备用图源（第 $attempt/$total 个）"
            Idle -> ""
        }
}

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    // SY -->
    /** Value to check if this page is used to as if it was too wide */
    var shiftedPage: Boolean = false,
    /** Value to check if a page is can be doubled up, but can't because the next page is too wide */
    var isolatedPage: Boolean = false,
    // SY <--
    var stream: (() -> InputStream)? = null,

) : Page(index, url, imageUrl, null), ReaderItem {

    open lateinit var chapter: ReaderChapter

    /** Ownership epoch. Stale workers cannot publish state or commit after this changes. */
    val generation = AtomicInteger(0)
    val activeAttempt = AtomicReference<PageAttemptScope?>(null)
    /** Generation currently represented by a queue entry; -1 means no queued entry. */
    val queuedToken = AtomicInteger(-1)
    val manualRetryCount = AtomicInteger(0)
    /** Successful speed-test endpoints already tried for this image URL; attempts never repeat. */
    val attemptedImageEndpoints: MutableSet<DomainIp> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Initial request is round one; automatic retries are capped at three total rounds. */
    val automaticRetryRound = AtomicInteger(1)
    val endpointsAttemptedInRound = AtomicInteger(0)
    @Volatile var automaticRetryExhausted: Boolean = false
    @Volatile var retryBudgetSource: String? = null
    private val _slowFlow = MutableStateFlow(false)
    val slowFlow = _slowFlow.asStateFlow()
    private val _readerVisibleFlow = MutableStateFlow(false)
    val readerVisibleFlow = _readerVisibleFlow.asStateFlow()
    private val _loadStatusFlow = MutableStateFlow<ReaderPageLoadStatus>(ReaderPageLoadStatus.Queued)
    val loadStatusFlow = _loadStatusFlow.asStateFlow()
    var loadStatus: ReaderPageLoadStatus
        get() = _loadStatusFlow.value
        set(value) {
            _loadStatusFlow.value = value
        }
    @Volatile
    var slow: Boolean = false
        set(value) {
            field = value
            _slowFlow.value = value
        }
    @Volatile
    var readerVisible: Boolean = false
        set(value) {
            field = value
            _readerVisibleFlow.value = value
        }
    @Volatile var raceActive: Boolean = false

    /** Value to check if the page is too wide to be doubled up */
    var fullPage: Boolean = false
        set(value) {
            field = value
            if (value) shiftedPage = false
        }
}
