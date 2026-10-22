package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed class ReaderPageLoadStatus {
    data object Idle : ReaderPageLoadStatus()
    data class PicacgRequesting(val manualRetry: Int) : ReaderPageLoadStatus()
    data class PicacgReceiving(val bytesRead: Long, val contentLength: Long, val percentage: Int?) : ReaderPageLoadStatus()
}

class ReaderPage(index: Int, url: String, imageUrl: String?) : Page(index, url, imageUrl) {
    val generation = AtomicLong(0L)
    val activeAttempt = AtomicReference<com.zycomic.app.net.PageAttemptScope?>(null)
    val manualRetryCount = AtomicInteger(0)
    var progress: Int = 0
    var loadStatus: ReaderPageLoadStatus = ReaderPageLoadStatus.Idle
}
