package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.MaccmsSpeedTester
import com.zycomic.app.net.PlatformSpeedTestCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

/** This log never reads or mutates Picacg API, cover or reader source snapshots. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaccmsSpeedTestLogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by MaccmsSpeedTester.snapshot.collectAsState()
    val running by MaccmsSpeedTester.running.collectAsState()
    val selected by MaccmsSpeedTester.selectedRoute.collectAsState()
    val logRevision by MaccmsSpeedTester.logRevision.collectAsState()
    val events = remember(logRevision) {
        val records = MaccmsSpeedTester.logJson().getJSONArray("events")
        (records.length() - 1 downTo (records.length() - 20).coerceAtLeast(0)).map { records.getJSONObject(it) }
    }
    val platformRunning by PlatformSpeedTestCoordinator.running.collectAsState()
    val pendingExport = remember { mutableStateOf<String?>(null) }
    val exportSuccess = stringResource(KMR.strings.maccms_speed_log_exported)
    val exportFailed = stringResource(KMR.strings.maccms_speed_log_export_failed)
    val testFailed = stringResource(KMR.strings.maccms_speed_log_failed)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExport.value
        pendingExport.value = null
        if (uri != null && json != null) {
            scope.launch {
                val succeeded = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = requireNotNull(context.contentResolver.openOutputStream(uri))
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                    }.isSuccess
                }
                Toast.makeText(context, if (succeeded) exportSuccess else exportFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(KMR.strings.maccms_speed_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        ScrollbarLazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(KMR.strings.maccms_speed_log_scope), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(KMR.strings.maccms_speed_log_quick), style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text(stringResource(KMR.strings.maccms_speed_log_current, selected.orEmpty()))
                if (running) Text(stringResource(KMR.strings.maccms_speed_log_running))
                snapshot.completedAtEpochMs?.let { completed ->
                    Text(
                        stringResource(
                            KMR.strings.maccms_speed_log_round,
                            stringResource(if (snapshot.quick) KMR.strings.maccms_speed_log_quick_mode else KMR.strings.maccms_speed_log_full_mode),
                            formatSpeedTestLogTime(completed),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Button(
                    enabled = !running && !platformRunning,
                    onClick = {
                        scope.launch {
                            try {
                                MaccmsSpeedTester.run(quick = false)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                Toast.makeText(context, testFailed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) { Text(stringResource(KMR.strings.maccms_speed_log_full)) }
                Button(
                    onClick = {
                        // Capture the exact independent log at click time, not after the file picker.
                        pendingExport.value = MaccmsSpeedTester.logJson().toString(2)
                        export.launch("maccms-speed-test-log.json")
                    },
                ) { Text(stringResource(KMR.strings.maccms_speed_log_export)) }
            }
            if (snapshot.probes.isEmpty()) {
                item { Text(stringResource(KMR.strings.maccms_speed_log_none)) }
            }
            items(snapshot.probes) { probe ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(probe.route, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            KMR.strings.maccms_speed_log_result,
                            stringResource(if (probe.success) KMR.strings.maccms_speed_log_reachable else KMR.strings.maccms_speed_log_unreachable),
                            probe.elapsedMs?.let { "$it ms" } ?: "—",
                            probe.httpStatus?.toString() ?: "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item { Text(stringResource(KMR.strings.maccms_speed_log_events), style = MaterialTheme.typography.titleSmall) }
            items(events) { event ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(formatSpeedTestLogTime(event.getLong("eventAtEpochMs")), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(
                            KMR.strings.maccms_speed_log_request_result,
                            event.getString("route"),
                            stringResource(if (event.optBoolean("recommendationSuccess")) KMR.strings.maccms_speed_log_request_ok else KMR.strings.maccms_speed_log_request_failed),
                            if (event.isNull("httpStatus")) "—" else event.getInt("httpStatus").toString(),
                            event.getLong("elapsedMs"),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (event.optBoolean("failover")) {
                        Text(
                            stringResource(
                                KMR.strings.maccms_speed_log_failover,
                                event.optString("selectedRouteBefore"),
                                event.optString("selectedRouteAfter"),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
