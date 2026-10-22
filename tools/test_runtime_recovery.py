#!/usr/bin/env python3
"""Compile and run focused runtime recovery tests without invoking Gradle or Android tooling."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
OUT = Path("/tmp/jm-runtime-recovery-offline-tests")
STUBS = OUT / "RuntimeRecoveryTestStubs.kt"
CLASSES = OUT / "classes"


def jar(group: str, name: str, version: str) -> str:
    matches = sorted((CACHE / group / name / version).rglob("*.jar"))
    local = Path("/tmp/jm-targeted-check/deps") / f"{name}-{version}.jar"
    if not matches and local.is_file():
        matches = [local]
    if not matches:
        raise RuntimeError(f"Cached dependency not found: {group}:{name}:{version}; this runner is offline")
    return str(matches[0])


STUB_SOURCE = r'''package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow

internal object RuntimeNetworkGate {
    @Volatile private var online = false
    @Volatile var revision: Long = 0
        private set
    fun isOnline() = online
    fun ambiguousWriteMessage() = "请求结果不确定，可能已成功，请刷新确认"
    @Synchronized fun setOnline(value: Boolean) { if (online != value) { online = value; revision++ } }
}

internal object NetworkModule {
    val validations = AtomicInteger()
    fun validateCurrentApi(): Boolean { validations.incrementAndGet(); return true }
    fun rebindImageRoute(reason: String) = Unit
}
internal object JmNetworkModule {
    val validations = AtomicInteger()
    fun validateCurrentApi(): Boolean { validations.incrementAndGet(); return true }
}
internal object RouteManager {
    @Volatile var configRevision = 0L
    @Volatile var imgHost: String = "manwa-active.test"
    @Volatile var imgRewriteTarget: String? = "manwa-active.test"
    @Volatile var manualPinned = false
    @Volatile var imgPinDisplayRevision = 0L
    @Volatile var selectedImgIp: String? = null
    @Volatile var switchedEndpoint: DomainIp? = null
    @Volatile var candidates: List<DomainIp> = emptyList()
    fun runtimeImageRecoveryCandidates(source: String) = if (manualPinned || source != (imgRewriteTarget ?: imgHost)) emptyList() else candidates
    fun loadProbeImgPath() = "/probe.jpg"
    fun advanceRuntimeImageSourceAfterRecovery(source: String, config: Long, platform: Long, endpoint: DomainIp): Boolean {
        if (!RuntimeNetworkGate.isOnline() || configRevision != config || manualPinned || source != (imgRewriteTarget ?: imgHost) || endpoint !in candidates) return false
        switchedEndpoint = endpoint
        imgRewriteTarget = endpoint.domain
        return true
    }
}
internal object JmRouteManager {
    @Volatile var revision = 0L
    @Volatile var activeImageDomain = "jm-active.test"
    @Volatile var manualPinned = false
    @Volatile var switchedEndpoint: DomainIp? = null
    @Volatile var candidates: List<DomainIp> = emptyList()
    val baseUrl = "https://jm-api.test/"
    fun currentSpeedTestConfigRevision() = revision
    fun currentImageEndpoint(): DomainIp? = candidates.firstOrNull()
    fun runtimeImageRecoveryCandidates(source: String) = if (manualPinned || source != activeImageDomain) emptyList() else candidates
    fun advanceRuntimeImageDomainAfterRecovery(source: String, config: Long, platform: Long, endpoint: DomainIp): Boolean {
        if (!RuntimeNetworkGate.isOnline() || revision != config || manualPinned || source != activeImageDomain || endpoint !in candidates) return false
        switchedEndpoint = endpoint
        activeImageDomain = endpoint.domain
        return true
    }
}
internal object PicacgRouteManager {
    @Volatile var configRevision = 0L
    // Coordinator recovery only accepts an established active host; the real manager is nullable before configuration.
    @Volatile var currentImageHost: String = "picacg-active.test"
    @Volatile var coverImageHost: String = "picacg-active.test"
    @Volatile var bodyImageHost: String = "picacg-active.test"
    @Volatile var manualPinned = false
    @Volatile var switchedEndpoint: DomainIp? = null
    @Volatile var switchedCoverEndpoint: DomainIp? = null
    @Volatile var switchedBodyEndpoint: DomainIp? = null
    @Volatile var candidates: List<DomainIp> = emptyList()
    @Volatile var coverCandidates: List<DomainIp> = emptyList()
    @Volatile var bodyCandidates: List<DomainIp> = emptyList()
    data class State(val revision: Long = 0, val imageHost: String? = null)
    val state = MutableStateFlow(State())
    val apiBaseUrl = "https://picacg-api.test/"
    val apiValidations = AtomicInteger()
    data class ApiRecoveryCandidate(val url: String, val endpoint: DomainIp)
    @Volatile var apiEndpoint: DomainIp? = DomainIp("picacg-api.test", "198.51.100.70")
    @Volatile var apiCandidates: List<ApiRecoveryCandidate> = emptyList()
    @Volatile var manualApiPinned = false
    val apiSwitches = AtomicInteger()
    val selectedApiHost get() = apiEndpoint?.domain
    val isApiManuallyPinned get() = manualApiPinned
    fun currentApiEndpointFor(host: String) = apiEndpoint?.takeIf { it.domain.equals(host, true) }
    fun apiRecoveryCandidates(host: String) = if (manualApiPinned) emptyList() else apiCandidates
    fun advanceRuntimeApiAfterRecovery(expectedHost: String, expectedEndpoint: DomainIp, expectedConfigRevision: Long,
        expectedNetworkRevision: Long, expectedPlatformRevision: Long, validatedCandidate: ApiRecoveryCandidate): Boolean {
        if (!RuntimeNetworkGate.isOnline() || RuntimeNetworkGate.revision != expectedNetworkRevision ||
            configRevision != expectedConfigRevision || manualApiPinned || apiEndpoint != expectedEndpoint ||
            validatedCandidate !in apiCandidates) return false
        apiEndpoint = validatedCandidate.endpoint; apiSwitches.incrementAndGet(); return true
    }
    fun validateCurrentApi(): Boolean { apiValidations.incrementAndGet(); return true }
    fun imageSourceFor(content: Boolean): String = if (content) bodyImageHost else coverImageHost
    val imageHosts: List<String> get() = PicacgBuiltinNetwork.imageHosts + listOf(coverImageHost, bodyImageHost)
    fun candidateImageUrls(url: String): List<String> = listOf(url)
    fun runtimeImageRecoveryCandidates(source: String, service: PicacgImageService? = null): List<DomainIp> {
        if (manualPinned) return emptyList()
        val active = when (service) { PicacgImageService.BODY -> bodyImageHost; PicacgImageService.COVER -> coverImageHost; null -> currentImageHost }
        val pool = when (service) { PicacgImageService.BODY -> bodyCandidates; PicacgImageService.COVER -> coverCandidates; null -> candidates }
        return if (source == active) pool else emptyList()
    }
    fun advanceRuntimeImageSourceAfterRecovery(source: String, config: Long, platform: Long, endpoint: DomainIp,
        service: PicacgImageService? = null): Boolean {
        val active = when (service) { PicacgImageService.BODY -> bodyImageHost; PicacgImageService.COVER -> coverImageHost; null -> currentImageHost }
        if (!RuntimeNetworkGate.isOnline() || configRevision != config || manualPinned || source != active ||
            endpoint !in runtimeImageRecoveryCandidates(source, service)) return false
        when (service) {
            PicacgImageService.BODY -> { switchedBodyEndpoint = endpoint; bodyImageHost = endpoint.domain }
            PicacgImageService.COVER -> { switchedCoverEndpoint = endpoint; coverImageHost = endpoint.domain }
            null -> { switchedEndpoint = endpoint; currentImageHost = endpoint.domain }
        }
        return true
    }
}
internal object SpeedTester {
    val probeCalls = AtomicInteger()
    val probeEntered = CountDownLatch(1)
    val releaseProbe = CountDownLatch(1)
    val probeResults = java.util.concurrent.ConcurrentHashMap<DomainIp, Boolean>()
    @Volatile var blockFirstProbe = false
    fun probeForDeprecatedScan(endpoint: DomainIp, image: Boolean, imageSamplePath: String, runtimeRecovery: Boolean): Boolean {
        val index = probeCalls.incrementAndGet()
        if (blockFirstProbe && index == 1) { probeEntered.countDown(); releaseProbe.await(5, TimeUnit.SECONDS) }
        return probeResults[endpoint] ?: false
    }
}
internal object JmSpeedTester {
    val probeCalls = AtomicInteger()
    fun probeForDeprecatedScan(endpoint: DomainIp, image: Boolean, configuredLineUrl: String, runtimeRecovery: Boolean): Boolean {
        probeCalls.incrementAndGet(); return false
    }
}
internal object PicacgSpeedTester {
    val probeCalls = AtomicInteger()
    val coverProbeCalls = AtomicInteger()
    val bodyProbeCalls = AtomicInteger()
    val apiProbeCalls = AtomicInteger()
    val apiProbeResults = java.util.concurrent.ConcurrentHashMap<DomainIp, Boolean>()
    fun validateRuntimeApiEndpoint(endpoint: DomainIp, url: String): Boolean {
        apiProbeCalls.incrementAndGet(); return apiProbeResults[endpoint] ?: false
    }
    val probeResults = java.util.concurrent.ConcurrentHashMap<DomainIp, Boolean>()
    fun validateRuntimeImageEndpoint(endpoint: DomainIp, content: Boolean = false): Boolean {
        probeCalls.incrementAndGet()
        if (content) bodyProbeCalls.incrementAndGet() else coverProbeCalls.incrementAndGet()
        return probeResults[endpoint] ?: false
    }
}

internal class ImageAttemptCoordinator : AutoCloseable {
    private val timer = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "runtime-recovery-test-timer").apply { isDaemon = true } }
    @Volatile var watchdogTask: Runnable? = null
    val watchdog: ScheduledThreadPoolExecutor get() = timer
    fun scheduleWatchdog(task: Runnable): ScheduledFuture<*>? {
        watchdogTask = task
        return timer.scheduleAtFixedRate(task, 1, 1, TimeUnit.DAYS)
    }
    override fun close() { timer.shutdownNow() }
}
internal class PageAttemptScope(
    val page: eu.kanade.tachiyomi.ui.reader.model.ReaderPage,
    val generation: Int,
    val coordinator: ImageAttemptCoordinator,
) {
    val isCurrent: Boolean get() = page.generation.get() == generation
    fun registerCall(call: okhttp3.Call, index: Int): AutoCloseable = AutoCloseable { }
}

internal object RuntimeRecoveryTestFixtures {
    fun enableAll() { StartupPlatformPolicy.state.value = StartupPlatformPolicy.State(revision = 0) }
}
'''

def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(CLASSES, ignore_errors=True)
    CLASSES.mkdir(parents=True)
    STUBS.write_text(STUB_SOURCE, encoding="utf-8")
    # Kotlin source files cannot contain multiple package directives, so fixtures are split.
    platform_file = OUT / "RuntimeRecoveryPlatformStubs.kt"
    platform_file.write_text("""package com.zycomic.app.platform
import kotlinx.coroutines.flow.MutableStateFlow
enum class ContentPlatform { MANWA, JM, PICACG }
object StartupPlatformPolicy { data class State(val revision: Long = 0); val state = MutableStateFlow(State()); fun isEnabled(platform: ContentPlatform) = true }
""", encoding="utf-8")
    android_base64_file = OUT / "AndroidBase64Stub.kt"
    android_base64_file.write_text("""package android.util
object Base64 {
    const val URL_SAFE = 8
    const val NO_WRAP = 2
    const val NO_PADDING = 1
    fun encodeToString(bytes: ByteArray, flags: Int): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
""", encoding="utf-8")
    source_page_file = OUT / "SourcePageStub.kt"
    source_page_file.write_text("""package eu.kanade.tachiyomi.source.model
class Page { sealed interface State { class Error : State } }
""", encoding="utf-8")
    reader_page_file = OUT / "ReaderPageStub.kt"
    reader_page_file.write_text("""package eu.kanade.tachiyomi.ui.reader.model
import eu.kanade.tachiyomi.source.model.Page
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
class ReaderPage { val generation = AtomicInteger(0); @Volatile var readerVisible = true; @Volatile var activeAttempt = AtomicReference<Any?>(null); @Volatile var status: Page.State = Page.State.Error(); val chapter = Chapter() }
class Chapter { val pageLoader = TestPageLoader() }
class TestPageLoader { val retryCalls = AtomicInteger(); fun retryAfterRuntimeRecovery(page: ReaderPage, expectedGeneration: Int) { retryCalls.incrementAndGet() } }
""", encoding="utf-8")
    source_ids_file = OUT / "ReaderSourceIdsStub.kt"
    source_ids_file.write_text("""package com.zycomic.app.reader
object ManwaSource { const val id = 101L }
object JmSource { const val id = 102L }
object PicacgSource { const val id = 103L }
""", encoding="utf-8")

    auth_file = OUT / "PicacgAuthStub.kt"
    auth_file.write_text("""package com.zycomic.app.picacg
object PicacgAuthStore { data class Session(val token: String?, val revision: Long); fun token(): String? = null; fun session() = Session(null, 0); fun clearIfRevision(revision: Long) = false }
""", encoding="utf-8")
    logging_file = OUT / "LoggingStub.kt"
    logging_file.write_text("""package tachiyomi.core.common.util.system
fun logcat(message: () -> String) = Unit
""", encoding="utf-8")
    signer_file = OUT / "PicacgSignerExtract.kt"
    models = (ROOT / "app/src/main/java/com/zycomic/app/picacg/PicacgModels.kt").read_text()
    signer = models[models.index("object PicacgSigner {"):models.index("/** Keeps an API-provided")]
    signer_file.write_text("package com.zycomic.app.picacg\nimport javax.crypto.Mac\nimport javax.crypto.spec.SecretKeySpec\n" + signer, encoding="utf-8")

    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")
    okhttp = jar("com.squareup.okhttp3", "okhttp-jvm", "5.3.2")
    okio = jar("com.squareup.okio", "okio-jvm", "3.16.4")
    junit = "/tmp/jm-targeted-check/junit-console.jar"
    compiler = ":".join([
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
        stdlib,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"),
        coroutines,
        jar("org.jetbrains", "annotations", "13.0"),
    ])
    main = ROOT / "app/src/main/java/com/zycomic/app/net"
    reader = ROOT / "app/src/main/java/com/zycomic/app/reader"
    test = ROOT / "app/src/test/java/com/zycomic/app/net"
    reader_test = ROOT / "app/src/test/java/com/zycomic/app/reader"
    sources = [
        main / "DomainIp.kt",
        main / "PicacgBuiltinNetwork.kt",
        main / "PicacgImagePayloadPolicy.kt",
        main / "PicacgSpeedTestTargetPolicy.kt",
        main / "PicacgSmartDns.kt",
        main / "RuntimeNetworkPolicy.kt",
        main / "RuntimeRecoveryPolicy.kt",
        main / "RuntimeRetryAfterPolicy.kt",
        main / "JmApiEndpointRecovery.kt",
        main / "RuntimeImageRateLimit.kt",
        main / "RuntimeImageRecovery.kt",
        main / "PicacgRuntimeImageInterceptor.kt",
        main / "PicacgImageFallbackInterceptor.kt",
        main / "ApiWriteSafetyInterceptor.kt",
        main / "PicacgApiCandidatePolicy.kt",
        main / "PicacgApiFailureWindow.kt",
        main / "PicacgApiRuntimeRecovery.kt",
        main.parent / "picacg/PicacgClient.kt",
        main.parent / "picacg/PicacgRedirectPolicy.kt",
        main.parent / "picacg/PicacgDiagnosticPolicy.kt",
        main.parent / "picacg/PicacgLoginErrors.kt",
        reader / "ReaderPlatformRetryPolicy.kt",
        test / "RuntimeRecoveryIsolationTest.kt",
        ROOT / "tools/offline-tests/RuntimeImageRecoveryIntegrationTest.kt",
        ROOT / "tools/offline-tests/PicacgSharedDnsPoolOfflineTest.kt",
        test / "ApiWriteSafetyInterceptorTest.kt",
        test / "PicacgApiCandidatePolicyTest.kt",
        test / "PicacgApiFailureWindowTest.kt",
        ROOT / "tools/offline-tests/PicacgApiRuntimeRecoveryTest.kt",
        test.parent / "picacg/PicacgClientRedirectTest.kt",
        reader_test / "ReaderPlatformRetryPolicyTest.kt",
        STUBS,
        platform_file,
        android_base64_file,
        source_page_file,
        reader_page_file,
        source_ids_file,
        auth_file,
        logging_file,
        signer_file,
    ]
    mockwebserver = jar("com.squareup.okhttp3", "mockwebserver3", "5.3.2")
    cp = ":".join([stdlib, coroutines, okhttp, okio, junit, "/tmp/jm-targeted-check/deps/json-20240303.jar", mockwebserver])
    subprocess.run([
        "java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp,
        "-d", str(CLASSES), *map(str, sources),
    ], cwd=ROOT, check=True)
    subprocess.run([
        "java", "-jar", junit, "execute", "--class-path", ":".join([str(CLASSES), cp]),
        "--scan-class-path", "--disable-banner", "--fail-if-no-tests",
        "--reports-dir", str(OUT / "reports"),
    ], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
