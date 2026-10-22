#!/usr/bin/env python3
"""Compile/run focused Maccms coordinator tests using only locally cached jars; never invokes Gradle or downloads."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tools/offline-tests/maccms-coordinator"
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
LOCAL_DEPS = Path("/tmp/jm-targeted-check/deps")
OUT = Path("/tmp/maccms-coordinator-offline-runtime")
CLASSES = OUT / "classes"


def jar(group: str, name: str, version: str) -> str:
    matches = sorted((CACHE / group / name / version).rglob("*.jar"))
    local = LOCAL_DEPS / f"{name}-{version}.jar"
    if not matches and local.is_file():
        matches = [local]
    if not matches:
        raise RuntimeError(f"Cached dependency not found: {group}:{name}:{version}; this runner is offline")
    return str(matches[0])


def latest(group: str, name: str) -> str:
    base = CACHE / group / name
    for version in sorted(base.iterdir(), reverse=True):
        matches = sorted(version.rglob("*.jar"))
        if matches:
            return str(matches[0])
    raise RuntimeError(f"Cached dependency not found: {group}:{name}; this runner is offline")


def main() -> None:
    net = ROOT / "app/src/main/java/com/zycomic/app/net"
    app_tests = ROOT / "app/src/test/java/com/zycomic/app/net"
    offline_tests = HARNESS / "MaccmsCoordinatorRuntimeTest.kt"
    previous_cache_test = app_tests / "CompletedSpeedTestCoordinatorRuntimeTest.kt"
    pinned_transport = net / "MaccmsPinnedTransport.kt"
    sources = [
        HARNESS / "MaccmsCoordinatorAndroid.kt",
        HARNESS / "MaccmsCoordinatorConnectivity.kt",
        HARNESS / "Log.kt",
        HARNESS / "MaccmsCoordinatorPlatform.kt",
        HARNESS / "MaccmsCoordinatorFakes.kt",
        HARNESS / "MaccmsOfflineProbeExtension.kt",
        net / "DomainIp.kt",
        pinned_transport,
        net / "MaccmsSpeedTester.kt",
        net / "CompletedSpeedTestPhases.kt",
        net / "StartupSpeedTestGate.kt",
        net / "SpeedTestRunPolicy.kt",
        net / "FullSpeedTestRequest.kt",
        net / "PlatformStartupStatus.kt",
        net / "PlatformSpeedTestCoordinator.kt",
        net / "PlatformSpeedTestReport.kt",
        net / "SpeedTestTimings.kt",
        net / "SpeedTestTimingLog.kt",
        net / "SpeedTestUiTextPolicy.kt",
        net / "ImageSourceDisplayPolicy.kt",
        net / "ImgDomainPolicy.kt",
        previous_cache_test,
        offline_tests,
    ]
    missing = [str(path) for path in sources if not path.is_file()]
    if missing:
        raise RuntimeError("Missing coordinator runtime source(s): " + ", ".join(missing))

    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    coroutines = latest("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")
    junit = Path("/tmp/jm-targeted-check/junit-console.jar")
    json = Path("/tmp/zycomic-ui-check/json.jar")
    if not junit.is_file() or not json.is_file():
        raise RuntimeError("Cached JUnit/JSON runtime jars are missing; this runner is offline")
    compiler = ":".join([
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
        stdlib,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"),
        coroutines,
        jar("org.jetbrains", "annotations", "13.0"),
    ])
    okhttp = jar("com.squareup.okhttp3", "okhttp-jvm", "5.3.2")
    okio = jar("com.squareup.okio", "okio-jvm", "3.16.4")
    classpath = ":".join([stdlib, str(junit), str(json), coroutines, okhttp, okio])
    OUT.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(CLASSES, ignore_errors=True)
    CLASSES.mkdir(parents=True)
    compile_command = [
        "java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
        "-d", str(CLASSES), *map(str, sources),
    ]
    print(f"Compiling {len(sources)} coordinator production/fake/test sources with cached Kotlin 2.2.21", flush=True)
    subprocess.run(compile_command, cwd=ROOT, check=True)
    extension_service = CLASSES / "META-INF/services/org.junit.jupiter.api.extension.Extension"
    extension_service.parent.mkdir(parents=True, exist_ok=True)
    extension_service.write_text("com.zycomic.app.net.MaccmsOfflineProbeExtension\n", encoding="utf-8")

    custom_methods = [
        "complete phase cache still publishes a fresh current round IP for Maccms",
        "persisted historical selected IP is ignored in favor of this run",
        "failed Picacg run with old snapshot cannot fall back to old IP or probe Maccms",
        "unpublished returned snapshot is discarded before Maccms binding",
        "configuration race discards stale selection and retries with only latest generation IP",
        "cancellation propagates and never hands a cached selection to Maccms",
    ]
    old_methods = [
        "updated config runs full probes and duplicate no-sample request reuses other phases",
        "completed config update is not repeated as a quick test by duplicate startup",
        "normal startup retains independent top five preferences and platform toggles",
        "new real image sample probes Picacg API and image phases",
        "forceFresh retests all platforms and onlyPlatform retests just its platform",
        "network and remote config version changes invalidate phase reuse",
        "disabled platform does not start its underlying tester",
        "failed explicit single platform retest cannot be hidden by previously completed phases",
    ]
    selectors = [("MaccmsCoordinatorRuntimeTest", method) for method in custom_methods]
    selectors += [("CompletedSpeedTestCoordinatorRuntimeTest", method) for method in old_methods]
    for class_name, method in selectors:
        selector = f"com.zycomic.app.net.{class_name}#{method}"
        command = [
            "java", "-Djunit.jupiter.extensions.autodetection.enabled=true", "-jar", str(junit), "execute", "--class-path", str(CLASSES) + ":" + classpath,
            "--select-method=" + selector, "--disable-banner", "--details=summary", "--fail-if-no-tests",
        ]
        print("FRESH TEST JVM:", selector, flush=True)
        subprocess.run(command, cwd=ROOT, check=True)
    print(f"PASS: {len(selectors)} focused runtime methods (6 new coordinator cases, 8 existing coordinator cases)")


if __name__ == "__main__":
    main()
