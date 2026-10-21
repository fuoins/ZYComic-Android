package com.zycomic.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** Display provenance independently of the current route and the quick-test membership. */
@Composable
internal fun SpeedTestEndpointRow(
    label: String,
    result: String,
    measuredAtEpochMs: Long?,
    testedThisRound: Boolean,
    hasPreviousResult: Boolean = false,
    blocked: Boolean = false,
    quickSelected: Boolean = false,
    selectionLabel: String? = null,
    failed: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val selected = selectionLabel != null
    val source = when {
        blocked -> stringResource(KMR.strings.speed_log_source_blocked)
        testedThisRound -> stringResource(KMR.strings.speed_log_source_fresh)
        hasPreviousResult -> stringResource(KMR.strings.manwa_speed_log_reused)
        else -> stringResource(KMR.strings.manwa_speed_log_untested)
    }
    val time = measuredAtEpochMs?.takeIf { it > 0L }?.let {
        stringResource(KMR.strings.speed_log_measured_at, formatSpeedTestLogTime(it))
    } ?: stringResource(KMR.strings.speed_log_time_unknown)
    val quickMarker = if (quickSelected) stringResource(KMR.strings.manwa_speed_log_quick_selected) else null
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text = listOf(result, time, source).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                !testedThisRound || blocked -> MaterialTheme.colorScheme.onSurfaceVariant
                failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (quickMarker != null || selectionLabel != null) {
            Text(
                text = listOfNotNull(quickMarker, selectionLabel).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatSpeedTestLogTime(epochMs: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
