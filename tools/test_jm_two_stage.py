#!/usr/bin/env python3
"""Compile and run actual JM route/tester production code without Android SDK or network."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "tools/offline-tests/jm"
MAIN = ROOT / "app/src/main/java/com/zycomic/app/net"
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
DEPS = ROOT / "tools/offline-tests/build/deps"


def jar(group: str, name: str, version: str) -> str:
    prepared = DEPS / f"{name}-{version}.jar"
    if prepared.is_file():
        return str(prepared)
    found = sorted((CACHE / group / name / version).rglob("*.jar"))
    if not found:
        raise RuntimeError(f"Missing cached dependency {group}:{name}:{version}; offline runner will not download")
    return str(found[0])


def main() -> None:
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")
    junit = jar("org.junit.platform", "junit-platform-console-standalone", "1.10.2")
    okhttp = jar("com.squareup.okhttp3", "okhttp", "4.12.0")
    okio = jar("com.squareup.okio", "okio-jvm", "3.6.0")
    json = jar("org.json", "json", "20240303")
    serialization = [jar("org.jetbrains.kotlinx", f"kotlinx-serialization-{kind}-jvm", "1.6.1") for kind in ["core", "json"]]
    annotations = jar("org.jetbrains", "annotations", "13.0")
    compiler = os.pathsep.join([
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"), stdlib,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"), coroutines, annotations,
    ])
    classpath = os.pathsep.join(map(str, [stdlib, junit, coroutines, okhttp, okio, json, annotations, *serialization]))
    sources = [
        FIXTURE / "platform-stubs.kt", FIXTURE / "network-stubs.kt", FIXTURE / "fake-context.kt",
        FIXTURE / "JmTwoStageRuntimeTest.kt",
        MAIN / "DomainIp.kt", MAIN / "DomainIpProbePolicy.kt", MAIN / "SpeedTestProbeBudget.kt",
        MAIN / "AdaptiveSpeedProbe.kt", MAIN / "AdaptiveSpeedHistory.kt",
        MAIN / "SpeedTestRunPolicy.kt", MAIN / "SpeedTestTimings.kt", MAIN / "SpeedTestTimingLog.kt",
        MAIN / "JmSpeedTester.kt", MAIN / "JmSpeedTestQuickProbePolicy.kt", MAIN / "JmSpeedTestValidityPolicy.kt",
        MAIN / "SpeedTestHistoryPolicy.kt", MAIN / "JmApiEndpointRecovery.kt", MAIN / "RuntimeRecoveryPolicy.kt",
        MAIN / "JmSpeedTestHistory.kt",
        MAIN / "JmProbeDnsResolver.kt", MAIN / "JmEndpointProbeRunner.kt", MAIN / "JmEndpointSelectionPolicy.kt",
        MAIN / "JmImagePolicy.kt", MAIN / "JmRouteManager.kt", MAIN / "RuntimeRetryAfterPolicy.kt",
        MAIN / "JmSpeedTestSubmission.kt", MAIN / "JmSpeedProbeProtocol.kt",
    ]
    missing = [str(p) for p in sources if not p.is_file()]
    if missing:
        raise RuntimeError("Missing source(s): " + ", ".join(missing))
    with tempfile.TemporaryDirectory(prefix="jm-two-stage-") as temp:
        temp = Path(temp)
        classes, android_classes = temp / "classes", temp / "android-classes"
        classes.mkdir(); android_classes.mkdir()
        java_sources = list((FIXTURE / "android-runtime").rglob("*.java"))
        subprocess.run(["javac", "-d", str(android_classes), *map(str, java_sources)], check=True)
        print(f"Compiling {len(sources)} JM production/test Kotlin sources", flush=True)
        subprocess.run([
            "java", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath",
            classpath + os.pathsep + str(android_classes),
            "-d", str(classes), *map(str, sources),
        ], cwd=ROOT, check=True)
        subprocess.run([
            "java", "-jar", junit, "execute", "--class-path",
            os.pathsep.join(map(str, [classes, android_classes])) + os.pathsep + classpath,
            "--select-class", "com.zycomic.jmcache.JmTwoStageRuntimeTest",
            "--disable-banner", "--details=tree", "--fail-if-no-tests",
        ], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
