#!/usr/bin/env python3
"""Compile and execute focused Manwa two-stage tests against real production Kotlin sources.

No Android SDK, Gradle app tasks, APK, or network access is used. Dependencies are loaded
from the repository's cached offline-test artifacts (see tools/test_adaptive_speed_probe.py).
"""
from pathlib import Path
import atexit
import importlib.util
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
OUT = Path(tempfile.mkdtemp(prefix="manwa-two-stage-"))
atexit.register(shutil.rmtree, OUT, ignore_errors=True)
DEPS = ROOT / "tools/offline-tests/build/deps"

spec = importlib.util.spec_from_file_location("adaptive_helper", ROOT / "tools/test_adaptive_speed_probe.py")
helper = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(helper)


def cached(group: str, name: str, version: str) -> str:
    prepared = DEPS / f"{name}-{version}.jar"
    if prepared.is_file():
        return str(prepared)
    return helper.jar(group, name, version)


def main() -> None:
    stdlib = cached("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    coroutines = cached("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")
    junit = cached("org.junit.platform", "junit-platform-console-standalone", "1.10.2")
    compiler = os.pathsep.join([
        cached("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
        stdlib,
        cached("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        cached("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        cached("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"),
        coroutines,
        cached("org.jetbrains", "annotations", "13.0"),
    ])
    okhttp = cached("com.squareup.okhttp3", "okhttp", "4.12.0")
    okio = cached("com.squareup.okio", "okio-jvm", "3.6.0")
    cp = os.pathsep.join([
        stdlib, coroutines, junit, okhttp, okio,
        cached("org.jetbrains", "annotations", "13.0"),
        str(OUT / "android-classes"),
        cached("org.json", "json", "20240303"),
    ])
    java_runtime = os.pathsep.join([str(OUT), cp])
    android_sources = sorted((HERE / "fakes").glob("*.java"))
    kotlin_sources = [
        HERE / "fakes/ComposeState.kt",
        HERE / "fakes/Platform.kt",
        HERE / "fakes/MemoryContext.kt",
        HERE / "fakes/Manga.kt",
        HERE / "fakes/ManwaNetworkFakes.kt",
        HERE / "fakes/ManwaInterceptor.kt",
    ]
    net = ROOT / "app/src/main/java/com/zycomic/app/net"
    kotlin_sources += [net / name for name in [
        "DomainIp.kt", "RouteListPolicy.kt", "ImageEndpointSelectionPolicy.kt", "ImgDomainPolicy.kt",
        "SpeedTestLogModels.kt", "ProbeAttemptDiagnostics.kt", "ReusableImageSpeedTestPolicy.kt",
        "SpeedTestHistoryPolicy.kt", "SpeedTestLogSelectionPolicy.kt", "DomainIpProbePolicy.kt", "ManwaSniPolicy.kt",
        "SpeedTestProbeBudget.kt", "SpeedTestProbeCall.kt", "ManwaImageFallbackProbePolicy.kt", "SpeedTestTimings.kt", "SpeedTestTimingLog.kt",
        "AdaptiveSpeedProbe.kt", "AdaptiveSpeedHistory.kt", "RouteManager.kt", "SpeedTester.kt",
    ]]
    kotlin_sources += [
        ROOT / "app/src/test/java/com/zycomic/app/net/SpeedTesterAdaptiveSelectionTest.kt",
        HERE / "ManwaTwoStageSpeedTesterRuntimeTest.kt",
    ]
    (OUT / "android-classes").mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "-d", str(OUT / "android-classes"), *map(str, android_sources)], cwd=ROOT, check=True)
    print(f"Compiling real SpeedTester.kt and RouteManager.kt with {len(kotlin_sources) - 6} production/test Kotlin sources", flush=True)
    subprocess.run([
        "java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp,
        "-d", str(OUT), *map(str, kotlin_sources),
    ], cwd=ROOT, check=True)
    subprocess.run([
        "java", "-jar", junit, "execute", "--class-path", java_runtime,
        "--select-class", "com.zycomic.app.net.ManwaTwoStageSpeedTesterRuntimeTest",
        "--select-class", "com.zycomic.app.net.SpeedTesterAdaptiveSelectionTest",
        "--disable-banner", "--details=summary", "--fail-if-no-tests",
    ], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
