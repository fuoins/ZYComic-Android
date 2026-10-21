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
    }

    fun setProgress(@IntRange(from = 0, to = 100) progress: Int) {
        this.progress = progress / 100f
    }

    // KMK --> Only Picacg byte observations use these labels; other sources retain their stages.
    fun setLoadStatus(status: ReaderPageLoadStatus) {
        setStatus(
            loadStatusText(status),
            immediate = status is ReaderPageLoadStatus.PicacgReceiving || status is ReaderPageLoadStatus.PicacgRequesting,
        )
    }

    fun setPageLoadStatuses(statuses: List<Pair<Int, ReaderPageLoadStatus>>) {
        setStatus(
            statuses.filter { it.second != ReaderPageLoadStatus.Idle }.joinToString("\n") { (page, status) ->
                context.stringResource(KMR.strings.picacg_image_page_status, page, loadStatusText(status))
            },
            immediate = true,
        )
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
