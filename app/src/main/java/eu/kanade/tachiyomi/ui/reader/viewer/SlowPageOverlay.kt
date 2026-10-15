package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

/** Neutral, non-clickable placeholder; only the small retry button consumes touch input. */
class SlowPageOverlay(
    context: Context,
    onRetry: () -> Unit,
) : FrameLayout(context) {
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
        content.addView(TextView(context).apply {
            text = "页面加载较慢"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        content.addView(Button(context).apply {
            text = "重试"
            isAllCaps = false
            setOnClickListener { onRetry() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(content, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }
}
