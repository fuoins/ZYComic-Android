package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Read-only per-platform diagnosis. Never selects, pins, persists, or resolves extra addresses. */
internal object DeprecatedEndpointScanner {
    data class Progress(
        val completedIps: Int,
        val totalIps: Int,
        val currentHost: String,
        val totalTargets: Int,
        val lineTargets: Int,
        val imageTargets: Int,
        val groups: List<Group>,
    )

    data class Group(val kind: DeprecatedEndpointScanPolicy.Kind, val host: String, val configuredIpCount: Int)

    data class Report(
        val platform: ContentPlatform,
        val entries: List<DeprecatedEndpointScanPolicy.TargetResult>,
        val configurationChanged: Boolean,
    )

    internal data class Snapshot(
        val revision: Long,
        val version: Int?,
        val lines: List<Pair<String, String>>,
        val images: List<String>,
        val lineRules: Map<String, List<String>>,
        val imageRules: Map<String, List<String>>,
        val imageSample: String?,
        val strategy: String = "",
        val imageSampleHost: String? = null,
    )

    /** Cancellation invalidates the whole scan; the UI explicitly displays an undetermined result. */
    suspend fun scan(
        platform: ContentPlatform,
        onProgress: ((Progress) -> Unit)? = null,
    ): Report = withContext(Dispatchers.IO) {
        scanReadOnly(
            platform = platform,
            readSnapshot = { snapshot(platform) },
            probe = { initial, kind, host, ip ->
                val endpoint = DomainIp(host, ip)
                val success = when (platform) {
                    ContentPlatform.MANWA -> SpeedTester.probeForDeprecatedScan(
                        endpoint = endpoint,
                        image = kind == DeprecatedEndpointScanPolicy.Kind.IMAGE,
                        imageSamplePath = initial.imageSample,
                    )
                    ContentPlatform.PICACG -> {
                        if (kind == DeprecatedEndpointScanPolicy.Kind.LINE) {
                            val baseUrl = initial.lines.first { it.first.equals(host, true) }.second
                            PicacgSpeedTester.probeForDeprecatedScan(endpoint, baseUrl, image = false)
                        } else {
                            val sample = requireNotNull(initial.imageSample)
                            PicacgSpeedTester.probeForDeprecatedScan(endpoint, replaceHost(sample, host), image = true)
                        }
                    }
                    ContentPlatform.JM -> {
                        val lineUrl = initial.lines.firstOrNull { it.first.equals(host, true) }?.second
                            ?: "https://$host"
                        JmSpeedTester.probeForDeprecatedScan(endpoint, kind == DeprecatedEndpointScanPolicy.Kind.IMAGE, lineUrl)
                    }
                }
                if (success) DeprecatedEndpointScanPolicy.ProbeResult.SUCCESS else DeprecatedEndpointScanPolicy.ProbeResult.EXPLICIT_FAILURE
            },
            onProgress = onProgress,
        )
    }

    /** Injectable read/probe boundary used by offline tests; no mutation callback exists. */
    internal suspend fun scanReadOnly(
        platform: ContentPlatform,
        readSnapshot: () -> Snapshot,
        probe: suspend (Snapshot, DeprecatedEndpointScanPolicy.Kind, String, String) -> DeprecatedEndpointScanPolicy.ProbeResult,
        onProgress: ((Progress) -> Unit)? = null,
    ): Report {
        coroutineContext.ensureActive()
        val initial = readSnapshot()
        val targets = targets(initial)
        val totalIps = targets.sumOf { it.configuredIps.size }
        val groups = targets.map { Group(it.kind, it.host, it.configuredIps.distinct().size) }
        onProgress?.invoke(
            Progress(
                completedIps = 0,
                totalIps = totalIps,
                currentHost = "",
                totalTargets = targets.size,
                lineTargets = targets.count { it.kind == DeprecatedEndpointScanPolicy.Kind.LINE },
                imageTargets = targets.count { it.kind == DeprecatedEndpointScanPolicy.Kind.IMAGE },
                groups = groups,
            ),
        )
        var configurationChanged = false
        fun current(): Boolean {
            if (readSnapshot() != initial) configurationChanged = true
            return !configurationChanged
        }
        val results = DeprecatedEndpointScanPolicy.scan(
            targets = targets,
            isConfigurationCurrent = ::current,
            probe = { kind, host, ip -> probe(initial, kind, host, ip) },
            onProgress = onProgress?.let { callback ->
                { value ->
                    callback(
                        Progress(
                            completedIps = value.completedIps,
                            totalIps = value.totalIps,
                            currentHost = value.currentHost,
                            totalTargets = targets.size,
                            lineTargets = targets.count { it.kind == DeprecatedEndpointScanPolicy.Kind.LINE },
                            imageTargets = targets.count { it.kind == DeprecatedEndpointScanPolicy.Kind.IMAGE },
                            groups = groups,
                        ),
                    )
                }
            },
        )
        coroutineContext.ensureActive()
        // A final read closes the gap after the core classifier's last configuration check.
        val finalResults = if (current()) {
            results
        } else {
            targets.map { target ->
                DeprecatedEndpointScanPolicy.TargetResult(
                    kind = target.kind,
                    host = target.host,
                    state = DeprecatedEndpointScanPolicy.State.UNDETERMINED,
                    configuredIpCount = target.configuredIps.distinct().size,
                    probedIpCount = 0,
                    successCount = 0,
                    failureCount = 0,
                    reason = DeprecatedEndpointScanPolicy.Reason.CONFIGURATION_CHANGED,
                )
            }
        }
        return Report(platform, finalResults, configurationChanged)
    }

    private fun targets(snapshot: Snapshot): List<DeprecatedEndpointScanPolicy.Target> = buildList {
        snapshot.lines.map { it.first }.distinct().forEach { host ->
            add(configuredTarget(snapshot.lineRules, DeprecatedEndpointScanPolicy.Kind.LINE, host))
        }
        snapshot.images.distinct().forEach { host ->
            val target = configuredTarget(snapshot.imageRules, DeprecatedEndpointScanPolicy.Kind.IMAGE, host)
            add(
                if (snapshot.imageSample.isNullOrBlank()) {
                    target.copy(blockedReason = DeprecatedEndpointScanPolicy.Reason.MISSING_IMAGE_SAMPLE)
                } else if (snapshot.imageSampleHost != null && !snapshot.imageSampleHost.equals(host, true)) {
                    target.copy(blockedReason = DeprecatedEndpointScanPolicy.Reason.MISSING_IMAGE_SAMPLE)
                } else {
                    target
                },
            )
        }
    }

    private fun configuredTarget(
        rules: Map<String, List<String>>,
        kind: DeprecatedEndpointScanPolicy.Kind,
        host: String,
    ): DeprecatedEndpointScanPolicy.Target {
        val mapping = rules.entries.firstOrNull { it.key.equals(host, true) }?.value.orEmpty()
        val values = mapping.map(String::trim).filter(String::isNotEmpty).distinct()
        val literalIps = values.filter(::isIpLiteral)
        return DeprecatedEndpointScanPolicy.Target(
            kind = kind,
            host = host,
            configuredIps = literalIps,
            blockedReason = if (values.size != literalIps.size) DeprecatedEndpointScanPolicy.Reason.INVALID_IP_MAPPING else null,
        )
    }

    private fun snapshot(platform: ContentPlatform): Snapshot = when (platform) {
        ContentPlatform.MANWA -> {
            val rules = copyRules(RouteManager.customRule)
            Snapshot(
                revision = RouteManager.configRevision,
                version = RouteManager.activeRemoteConfigVersion,
                lines = lines(RouteManager.lineHosts),
                images = RouteManager.imgDomains.map { it.lowercase(Locale.ROOT) },
                lineRules = rules,
                imageRules = rules,
                imageSample = RouteManager.loadProbeImgPath(),
                strategy = "${PlatformSpeedTestCoordinator.currentNetworkIdentity()}|${RouteManager.sniDomains.sorted()}",
            )
        }
        ContentPlatform.PICACG -> {
            val state = PicacgRouteManager.state.value
            val imageSample = PicacgRouteManager.verifiedImageSampleUrl
            Snapshot(
                revision = PicacgRouteManager.configRevision,
                version = state.remoteVersion,
                lines = lines(state.apiUrls),
                images = state.imageHosts.map { it.lowercase(Locale.ROOT) },
                lineRules = copyRules(PicacgRouteManager.apiRouteRules()),
                imageRules = copyRules(PicacgRouteManager.imageRouteRules()),
                imageSample = imageSample,
                strategy = PlatformSpeedTestCoordinator.currentNetworkIdentity(),
                imageSampleHost = imageSample?.let { sample ->
                    runCatching { URI(sample).host }.getOrNull()
                },
            )
        }
        ContentPlatform.JM -> {
            val lines = lines(JmRouteManager.lineHosts)
            val images = JmRouteManager.imageDomains.map { it.lowercase(Locale.ROOT) }
            val speedSnapshot = JmRouteManager.speedTestSnapshot.value
            val verifiedImageSampleHost = (speedSnapshot.imageIpVerdicts.entries + speedSnapshot.untestedImageIpVerdicts.entries)
                .firstOrNull { (endpoint, verdict) ->
                    verdict == JmSpeedTester.ImgVerdict.OK && images.any { it.equals(endpoint.domain, true) }
                }
                ?.key?.domain
            Snapshot(
                revision = JmRouteManager.currentSpeedTestConfigRevision(),
                version = JmRouteManager.state.value.remoteVersion,
                lines = lines,
                images = images,
                // Never merge lines2/sources2: one hostname can have different mappings for each role.
                lineRules = lines.map { it.first }.distinct().associateWith { JmRouteManager.resolveLineIp(it).toList() },
                imageRules = images.distinct().associateWith { JmRouteManager.resolveImageIp(it).toList() },
                imageSample = JmSpeedProbeProtocol.IMAGE_PATH.takeIf { verifiedImageSampleHost != null },
                strategy = "${PlatformSpeedTestCoordinator.currentNetworkIdentity()}|${JmRouteManager.sniDomains.sorted()}",
                imageSampleHost = verifiedImageSampleHost,
            )
        }
    }

    private fun lines(urls: List<String>): List<Pair<String, String>> = urls.mapNotNull { url ->
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return@mapNotNull null
        host to url
    }

    private fun copyRules(rules: Map<String, List<String>>): Map<String, List<String>> =
        rules.mapKeys { it.key.lowercase(Locale.ROOT) }.mapValues { it.value.toList() }

    private fun isIpLiteral(value: String): Boolean {
        if (':' in value) {
            // A colon and this restricted alphabet cannot invoke hostname DNS resolution.
            if (!value.matches(Regex("[0-9a-fA-F:.]+"))) return false
            return runCatching {
                InetAddress.getByName(value)
                true
            }.getOrDefault(false)
        }
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
    }

    private fun replaceHost(url: String, host: String): String {
        val uri = URI(url)
        return URI(uri.scheme, null, host, uri.port, uri.path, uri.query, uri.fragment).toASCIIString()
    }
}
