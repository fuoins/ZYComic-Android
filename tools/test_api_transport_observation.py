#!/usr/bin/env python3
"""Compile real API transport observers and run deterministic response-body tests offline."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
OUT = Path("/tmp/zycomic-api-transport-observation")


def jar(group, name, version):
    matches = sorted((CACHE / group / name / version).rglob("*.jar"))
    fallback = Path("/tmp/jm-targeted-check/deps") / f"{name}-{version}.jar"
    if not matches and fallback.is_file():
        matches = [fallback]
    if not matches:
        raise RuntimeError(f"Missing offline dependency: {group}:{name}:{version}")
    return str(matches[0])


STUBS = r'''package com.zycomic.app.net
import java.io.IOException
internal object RuntimeNetworkGate {
    var online = true
    var revision = 1L
    fun isOnline() = online
    fun ambiguousWriteMessage() = "请求结果不确定，请刷新确认"
}
internal class PageAttemptScope { val isCurrent = true }
internal object JmNetworkModule {
    fun prepareApiRoute(host: String) = Unit
    fun prepareImageRoute(host: String) = Unit
}
internal object JmRouteManager {
    var runtimeConfigRevision = 1L
    fun currentLineEndpoint(host: String) = DomainIp(host, "198.51.100.2")
    fun currentImageEndpoint(host: String): DomainIp? = null
    fun recordApiEndpointFailure(endpoint: DomainIp, detail: String) = Unit
    fun recordImageEndpointFailure(endpoint: DomainIp, detail: String) = Unit
    fun hasEndpointMeasurements(host: String, image: Boolean) = true
    fun rememberLineEndpoint(endpoint: DomainIp) = Unit
    fun rememberImageEndpoint(endpoint: DomainIp) = Unit
    fun recordApiTransportResponse(endpoint: DomainIp) = Unit
    fun recordApiTransportResponse(endpoint: DomainIp, code: Int, retry: String?) = Unit
}
internal object JmEndpointRequestContext {
    data class Pin(val host: String, val image: Boolean, val endpoint: DomainIp?, val bootstrapAllowed: Boolean)
    private val pin = ThreadLocal<Pin?>()
    fun install(value: Pin): Pin? = pin.get().also { pin.set(value) }
    fun current(host: String, image: Boolean): Pin? = pin.get()?.takeIf { it.host == host && it.image == image }
    fun restore(value: Pin?) { pin.set(value) }
}
internal object RouteManager {
    var configRevision = 1L
    var failures = 0
    var responses = 0
    fun lineIpFor(host: String): String? = "198.51.100.2"
    fun resolveIp(host: String) = listOf("198.51.100.2")
    fun recordApiConnectionFailure(method: String, error: IOException, endpoint: DomainIp, config: Long, network: Long) { failures++ }
    fun recordApiResponse(endpoint: DomainIp, config: Long, network: Long, code: Int, retry: String?) { responses++ }
}
'''
PLATFORM = r'''package com.zycomic.app.platform
internal object StartupPlatformPolicy {
    data class Value(val revision: Long = 1L)
    object state { val value = Value() }
}
'''


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    stubs = OUT / "TransportStubs.kt"
    platform = OUT / "PlatformStub.kt"
    stubs.write_text(STUBS)
    platform.write_text(PLATFORM)
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")
    junit = Path("/tmp/jm-targeted-check/junit-console.jar")
    if not junit.is_file():
        raise RuntimeError("Missing offline JUnit console: " + str(junit))
    cp = ":".join([stdlib, coroutines, str(junit),
                   jar("com.squareup.okhttp3", "okhttp-jvm", "5.3.2"),
                   jar("com.squareup.okio", "okio-jvm", "3.16.4")])
    compiler = ":".join([jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"), stdlib,
                         jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
                         jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                         jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"), coroutines,
                         jar("org.jetbrains", "annotations", "13.0")])
    net = ROOT / "app/src/main/java/com/zycomic/app/net"
    sources = [net / name for name in ["DomainIp.kt", "RuntimeRecoveryPolicy.kt", "ApiWriteSafetyInterceptor.kt",
                                      "LineFailoverInterceptor.kt", "JmEndpointTransportInterceptor.kt"]]
    sources += [ROOT / "tools/offline-tests/ApiTransportObservationTest.kt", stubs, platform]
    classes = OUT / "classes"
    subprocess.run(["java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib",
                    "-no-reflect", "-jvm-target", "17", "-classpath", cp, "-d", str(classes),
                    *map(str, sources)], cwd=ROOT, check=True)
    subprocess.run(["java", "-jar", str(junit), "execute", "--class-path", str(classes) + ":" + cp,
                    "--scan-class-path", "--disable-banner", "--fail-if-no-tests"], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
