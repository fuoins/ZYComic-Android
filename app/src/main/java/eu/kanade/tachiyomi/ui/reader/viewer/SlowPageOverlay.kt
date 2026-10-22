package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Neutral, non-clickable status placeholder shown while a visible page is recovering. */
class SlowPageOverlay(
    context: Context,
    initialStatus: String,
) : FrameLayout(context) {
    private val statusView = TextView(context).apply {
        text = initialStatus
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }

    init {
        setBackgroundColor(Color.rgb(38, 38, 38))
        isClickable = false
        isFocusable = false
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 20, 24, 20)
            isClickable = false
        }
        content.addView(statusView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(content, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }

    fun setStatus(status: String) {
        statusView.text = status
    }
}
