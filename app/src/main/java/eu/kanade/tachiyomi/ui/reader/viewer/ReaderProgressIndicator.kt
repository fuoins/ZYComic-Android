package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.IntRange
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.ui.reader.model.ReaderPageLoadStatus
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CombinedCircularProgressIndicator
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import com.google.android.material.progressindicator.CircularProgressIndicator as ViewCircularProgressIndicator

/**
 * A wrapper for [ViewCircularProgressIndicator] that always rotates and reports the current load stage.
 */
class ReaderProgressIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var showRequested = false
    private var showScheduled = false
    private var statusScheduled = false
    private var pendingStatusText = ""
    private val showIndicatorRunnable = Runnable {
        showScheduled = false
        if (showRequested) isVisible = true
    }
    private val showStatusRunnable = Runnable {
        statusScheduled = false
        if (showRequested) statusText = pendingStatusText
    }

    init {
        layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        isVisible = false
    }

    private var progress by mutableFloatStateOf(0f)
    private var progressPercentage by mutableStateOf<Int?>(null)
    private var statusText by mutableStateOf("")

    @Composable
    override fun Content() {
        // KMK -->
        val uiPreferences = Injekt.get<UiPreferences>()
        val themeCoverBased = uiPreferences.themeCoverBased().get()
        // KMK <--
        TachiyomiTheme(
            // KMK -->
            seedColor = seedColor?.let { Color(seedColor) }.takeIf { themeCoverBased },
            // KMK <--
        ) {
            Column(
                modifier = Modifier.wrapContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CombinedCircularProgressIndicator(progress = { progress })
                progressPercentage?.let { percentage ->
                    Text(
                        text = "$percentage%",
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                if (statusText.isNotBlank()) {
                    Text(
                        text = statusText,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    fun show(immediate: Boolean = false) {
        showRequested = true
        if (immediate) {
            mainHandler.removeCallbacks(showIndicatorRunnable)
            showScheduled = false
            isVisible = true
            mainHandler.removeCallbacks(showStatusRunnable)
            statusScheduled = false
            statusText = pendingStatusText
        } else if (!isVisible && !showScheduled) {
            showScheduled = true
            mainHandler.postDelayed(showIndicatorRunnable, 300L)
        }
        if (pendingStatusText.isNotBlank() && statusText != pendingStatusText && !statusScheduled) {
            statusScheduled = true
            mainHandler.postDelayed(showStatusRunnable, 500L)
        }
    }

    fun hide() {
        showRequested = false
        mainHandler.removeCallbacks(showIndicatorRunnable)
        mainHandler.removeCallbacks(showStatusRunnable)
        showScheduled = false
        statusScheduled = false
        isVisible = false
        pendingStatusText = ""
        statusText = ""
        progressPercentage = null
    }

    fun setProgress(@IntRange(from = 0, to = 100) progress: Int, showPercentage: Boolean = true) {
        val validProgress = progress.takeIf { it in 0..100 }
        this.progress = validProgress?.div(100f) ?: 0f
        // Zero may mean either a fresh transfer or unknown content length; do not label it as a percentage.
        progressPercentage = validProgress?.takeIf { showPercentage && it in 1..99 }
    }

    // KMK --> Reader page stages are shared; only Picacg supplies byte-count labels.
    data class PageStatus(
        val number: Int,
        val pageCount: Int?,
        val status: ReaderPageLoadStatus,
        val progressPercentage: Int?,
    )

    fun setLoadStatus(
        status: ReaderPageLoadStatus,
        pageNumber: Int? = null,
        pageCount: Int? = null,
    ) {
        val label = loadStatusText(status)
        if (label.isBlank()) {
            setStageStatus("")
            return
        }
        setStageStatus(label, pageNumber, pageCount)
    }

    fun setPageLoadStatuses(statuses: List<PageStatus>) {
        setStatus(
            statuses.filter { it.status != ReaderPageLoadStatus.Idle }.joinToString("\n") { pageStatus ->
                val label = loadStatusText(pageStatus.status)
                val page = readerPagePosition(pageStatus.number, pageStatus.pageCount)
                val progress = pageStatus.progressPercentage
                    ?.takeIf { it in 1..99 && pageStatus.status !is ReaderPageLoadStatus.PicacgReceiving }
                    ?.let { "$it%" }
                listOfNotNull(page, label, progress).joinToString(" · ")
            },
            immediate = true,
        )
    }

    fun setStageStatus(stage: String, pageNumber: Int? = null, pageCount: Int? = null) {
        val label = if (stage.isBlank()) {
            ""
        } else {
            listOfNotNull(pageNumber?.let { readerPagePosition(it, pageCount) }, stage).joinToString(" · ")
        }
        setStatus(label, immediate = true)
    }

    private fun loadStatusText(status: ReaderPageLoadStatus): String = when (status) {
        is ReaderPageLoadStatus.PicacgRequesting -> if (status.manualRetry > 0) {
            context.stringResource(KMR.strings.picacg_image_retry_request, status.manualRetry)
        } else {
            context.stringResource(KMR.strings.picacg_image_request)
        }
        is ReaderPageLoadStatus.PicacgReceiving -> status.percentage?.let { percentage ->
            context.stringResource(
                KMR.strings.picacg_image_receiving_known,
                percentage,
                status.bytesRead,
                status.contentLength,
            )
        } ?: context.stringResource(KMR.strings.picacg_image_receiving_unknown, status.bytesRead)
        else -> status.label
    }
    // KMK <--

    fun setStatus(status: String, immediate: Boolean = false) {
        if (status == pendingStatusText) return
        pendingStatusText = status
        mainHandler.removeCallbacks(showStatusRunnable)
        statusScheduled = false
        if (immediate) {
            statusText = status
        } else if (status.isBlank()) {
            statusText = ""
        } else if (showRequested) {
            statusScheduled = true
            mainHandler.postDelayed(showStatusRunnable, 500L)
        }
    }
}

internal fun readerPagePosition(pageNumber: Int, pageCount: Int?): String =
    pageCount?.takeIf { it > 0 }?.let { "$pageNumber/$it" } ?: pageNumber.toString()
