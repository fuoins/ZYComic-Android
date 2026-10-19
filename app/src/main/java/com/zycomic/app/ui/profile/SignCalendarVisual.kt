package com.zycomic.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Shared presentation-only day cell; data loading and check-in behavior remain platform-specific. */
@Composable
internal fun SignCalendarDayCell(
    dayNumber: Int?,
    isSigned: Boolean,
    isMissed: Boolean,
    isToday: Boolean,
    isFuture: Boolean,
    isPlaceholder: Boolean = false,
) {
    Box(contentAlignment = Alignment.Center) {
        if (isPlaceholder) {
            Box(Modifier.size(32.dp))
            return@Box
        }
        val background = when {
            isToday || isSigned -> MaterialTheme.colorScheme.primary
            isMissed -> MaterialTheme.colorScheme.surfaceVariant
            isFuture -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val foreground = if (isToday || isSigned) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isSigned) "✓" else (dayNumber?.toString() ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground,
                )
            }
            if (isToday) Box(Modifier.width(16.dp).height(3.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}
