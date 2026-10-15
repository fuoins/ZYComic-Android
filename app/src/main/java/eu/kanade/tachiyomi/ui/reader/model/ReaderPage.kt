package eu.kanade.tachiyomi.ui.reader.model

import com.zycomic.app.net.PageAttemptScope
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    @Volatile var retryBudgetSource: String? = null
    private val _slowFlow = MutableStateFlow(false)
    val slowFlow = _slowFlow.asStateFlow()
    private val _readerVisibleFlow = MutableStateFlow(false)
    val readerVisibleFlow = _readerVisibleFlow.asStateFlow()
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
