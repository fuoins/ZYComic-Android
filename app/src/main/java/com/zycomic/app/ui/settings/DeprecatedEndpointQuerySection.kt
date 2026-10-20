package com.zycomic.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.DeprecatedEndpointScanPolicy
import com.zycomic.app.net.DeprecatedEndpointScanner
import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** Read-only diagnosis, deliberately separate from retests that apply endpoint selections. */
@Composable
internal fun DeprecatedEndpointQuerySection(platform: ContentPlatform, enabled: Boolean = true) {
    val scope = rememberCoroutineScope()
    var scanning by remember(platform) { mutableStateOf(false) }
    var resultText by remember(platform) { mutableStateOf<String?>(null) }
    var report by remember(platform) { mutableStateOf<DeprecatedEndpointScanner.Report?>(null) }
    var progress by remember(platform) { mutableStateOf<DeprecatedEndpointScanner.Progress?>(null) }
    var scanJob by remember(platform) { mutableStateOf<Job?>(null) }
    val cancelledText = stringResource(KMR.strings.deprecated_endpoint_query_cancelled)
    val failedText = stringResource(KMR.strings.deprecated_endpoint_query_failed)
    val groupingText = stringResource(KMR.strings.deprecated_endpoint_query_grouping)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(KMR.strings.deprecated_endpoint_query_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            enabled = enabled && !scanning,
            onClick = {
                scanning = true
                resultText = null
                report = null
                progress = null
                scanJob = scope.launch {
                    try {
                        val result = DeprecatedEndpointScanner.scan(platform) { progress = it }
                        coroutineContext.ensureActive()
                        report = result
                    } catch (cancelled: CancellationException) {
                        resultText = cancelledText
                        throw cancelled
                    } catch (_: Exception) {
                        resultText = failedText
                    } finally {
                        scanning = false
                    }
                }
            },
        ) {
            Text(stringResource(if (scanning) KMR.strings.deprecated_endpoint_query_running else KMR.strings.deprecated_endpoint_query_title))
        }
        if (scanning) {
            val currentProgress = progress
            Text(
                currentProgress?.let {
                    stringResource(
                        KMR.strings.deprecated_endpoint_query_progress,
                        it.lineTargets,
                        it.imageTargets,
                        it.completedIps,
                        it.totalIps,
                        it.currentHost.takeIf(String::isNotBlank)?.let { host -> " · $host" }.orEmpty(),
                    )
                } ?: groupingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            currentProgress?.groups?.forEach { group ->
                val kind = stringResource(
                    if (group.kind == DeprecatedEndpointScanPolicy.Kind.LINE) KMR.strings.deprecated_endpoint_kind_line else KMR.strings.deprecated_endpoint_kind_image,
                )
                Text(
                    "$kind · ${group.host} · IP ${group.configuredIpCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { scanJob?.cancel() }) {
                Text(stringResource(KMR.strings.deprecated_endpoint_query_cancel))
            }
        }
        resultText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        report?.let { result ->
            if (result.entries.isEmpty()) {
                Text(stringResource(KMR.strings.deprecated_endpoint_query_empty), style = MaterialTheme.typography.bodySmall)
            }
            result.entries.forEach { entry ->
                val kind = stringResource(
                    if (entry.kind == DeprecatedEndpointScanPolicy.Kind.LINE) KMR.strings.deprecated_endpoint_kind_line else KMR.strings.deprecated_endpoint_kind_image,
                )
                val state = stringResource(
                    when (entry.state) {
                        DeprecatedEndpointScanPolicy.State.FULLY_FAILED -> KMR.strings.deprecated_endpoint_state_all_failed
                        DeprecatedEndpointScanPolicy.State.HAS_SUCCESS -> KMR.strings.deprecated_endpoint_state_has_success
                        DeprecatedEndpointScanPolicy.State.UNDETERMINED -> KMR.strings.deprecated_endpoint_state_unknown
                    },
                )
                val reason = stringResource(
                    when (entry.reason) {
                        DeprecatedEndpointScanPolicy.Reason.CONFIGURATION_CHANGED -> KMR.strings.deprecated_endpoint_reason_config_changed
                        DeprecatedEndpointScanPolicy.Reason.MISSING_IP -> KMR.strings.deprecated_endpoint_reason_missing_ip
                        DeprecatedEndpointScanPolicy.Reason.MISSING_IMAGE_SAMPLE -> KMR.strings.deprecated_endpoint_reason_missing_sample
                        DeprecatedEndpointScanPolicy.Reason.INVALID_IP_MAPPING -> KMR.strings.deprecated_endpoint_reason_invalid_ip
                        DeprecatedEndpointScanPolicy.Reason.ALL_FAILED -> KMR.strings.deprecated_endpoint_reason_all_failed
                        DeprecatedEndpointScanPolicy.Reason.HAS_SUCCESS -> KMR.strings.deprecated_endpoint_reason_has_success
                        DeprecatedEndpointScanPolicy.Reason.INCOMPLETE_PROBE, null -> KMR.strings.deprecated_endpoint_reason_incomplete
                    },
                )
                Text(
                    stringResource(
                        KMR.strings.deprecated_endpoint_query_result,
                        kind, entry.host, state, entry.probedIpCount, entry.configuredIpCount,
                        entry.successCount, entry.failureCount, reason,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                    color = if (entry.state == DeprecatedEndpointScanPolicy.State.FULLY_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
